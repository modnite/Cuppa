package com.modnite.cuppa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class PrintServerService : Service() {

    private val binder = LocalBinder()
    var ippServer: IppServer? = null
        private set
    var rawSocketServer: RawSocketServer? = null
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private var statusCallback: ((Boolean, String?) -> Unit)? = null
    private var loggerCallback: ((String) -> Unit)? = null

    companion object {
        const val CHANNEL_ID = "rollo_print_server_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.rolloprint.START_SERVER"
        const val ACTION_STOP = "com.example.rolloprint.STOP_SERVER"
        const val PORT = 8631
        const val SERVICE_TYPE = "_ipp._tcp."
        const val SERVICE_NAME = "Rollo Printer"

        var isServerRunning = false
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): PrintServerService = this@PrintServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
            }
            ACTION_START, null -> {
                startForegroundServer()
            }
        }
        return START_STICKY
    }

    private fun startForegroundServer() {
        val notification = createNotification("Initializing IPP Print Server...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isServerRunning = true

        acquirePowerLocks()
    }

    fun initializeServer(
        usbPrintManager: UsbPrintManager,
        jobQueueManager: JobQueueManager,
        logger: (String) -> Unit,
        onStatusChanged: (Boolean, String?) -> Unit,
        onNetworkBitmapRendered: ((Bitmap) -> Unit)? = null
    ) {
        this.statusCallback = onStatusChanged
        this.loggerCallback = logger

        if (ippServer == null) {
            ippServer = IppServer(this, usbPrintManager, jobQueueManager, { logMsg ->
                loggerCallback?.invoke(logMsg)
            }, { running, ip ->
                isServerRunning = running
                updateNotification(if (running) "Active on $ip:$PORT" else "Server Stopped")
                statusCallback?.invoke(running, ip)
            }, onNetworkBitmapRendered)
        }
        ippServer?.start()

        val activeIp = ippServer?.getLocalIpAddress() ?: "127.0.0.1"
        isServerRunning = true
        updateNotification("Active on $activeIp:$PORT")
        statusCallback?.invoke(true, activeIp)

        val prefs = getSharedPreferences("rollo_prefs", MODE_PRIVATE)
        val enableRawPort9100 = prefs.getBoolean("PREF_RAW_PORT_9100", true)
        if (enableRawPort9100) {
            if (rawSocketServer == null) {
                rawSocketServer = RawSocketServer(this, usbPrintManager, jobQueueManager) { logMsg ->
                    loggerCallback?.invoke(logMsg)
                }
            }
            rawSocketServer?.start()
        }

        registerNsdService { logMsg -> loggerCallback?.invoke(logMsg) }
    }

    private fun registerNsdService(logger: (String) -> Unit) {
        try {
            unregisterNsdService() // Ensure previous registration listener is safely cleared
            nsdManager = getSystemService(NSD_SERVICE) as NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = SERVICE_NAME
                serviceType = SERVICE_TYPE
                port = PORT
                setAttribute("txtvers", "1")
                setAttribute("ty", "Rollo Thermal Printer 4x6")
                setAttribute("product", "(Rollo Thermal Printer 4x6)")
                setAttribute("rp", "ipp/print")
                setAttribute("pdl", "application/pdf,image/pwg-raster,image/jpeg,image/png")
                setAttribute("kind", "document,label")
                setAttribute("URF", "CP1,SM1,RS203")
                setAttribute("papercustom", "4x6in")
                setAttribute("qtotal", "1")
                setAttribute("printer-state", "3")
                setAttribute("printer-type", "0x4000000")
                setAttribute("note", "Rollo Thermal 4x6")
                setAttribute("UUID", "e5b02130-1c4b-483b-9a99-000000000001")
                setAttribute("Color", "F")
                setAttribute("Duplex", "F")
                setAttribute("Copies", "T")
                setAttribute("air", "none")
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    logger("[mDNS] Registered _ipp._tcp & AirPrint service: ${serviceInfo.serviceName} on port $PORT")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    logger("[mDNS] Registration failed: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    logger("[mDNS] Unregistered _ipp._tcp service")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            logger("[mDNS] Setup error: ${e.message}")
        }
    }

    private fun unregisterNsdService() {
        try {
            if (registrationListener != null) {
                nsdManager?.unregisterService(registrationListener)
                registrationListener = null
            }
        } catch (_: Exception) {}
    }

    fun stopServer() {
        ippServer?.stop()
        rawSocketServer?.stop()
        rawSocketServer = null
        unregisterNsdService()
        releasePowerLocks()
        isServerRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquirePowerLocks() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RolloPrint::IppServerWakeLock").apply {
                acquire(12 * 60 * 60 * 1000L)
            }

            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RolloPrint::IppServerWifiLock").apply {
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releasePowerLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
        } catch (_: Exception) {}
    }

    private fun updateNotification(statusText: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(NOTIFICATION_ID, createNotification(statusText))
        } catch (_: Exception) {}
    }

    private fun createNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PrintServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rollo IPP Print Server")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rollo IPP Server Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground notification for IPP driverless print server"
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
