package com.cuppa.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cuppa.app.MainActivity
import com.cuppa.app.R
import com.cuppa.app.server.IppServer
import com.cuppa.app.server.NetworkPrinterAdvertiser
import com.cuppa.app.server.PrintJobDispatcher
import com.cuppa.app.util.CuppaLog
import com.cuppa.cups.CupsEngine

/**
 * CupsPrintService — Android Foreground Service that hosts the local CUPS server,
 * IPP HTTP listener, and mDNS network advertiser.
 */
class CupsPrintService : Service() {

    companion object {
        private const val TAG = "CupsPrintService"

        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "cuppa_server_channel"

        const val ACTION_START = "com.cuppa.action.START_SERVER"
        const val ACTION_STOP = "com.cuppa.action.STOP_SERVER"
        const val ACTION_RESTART = "com.cuppa.action.RESTART_SERVER"

        /**
         * Start the CUPS print server foreground service.
         */
        fun start(context: Context) {
            val intent = Intent(context, CupsPrintService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
            CuppaLog.i(TAG, "Start command sent to CupsPrintService")
        }

        /**
         * Stop the CUPS print server foreground service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, CupsPrintService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
            CuppaLog.i(TAG, "Stop command sent to CupsPrintService")
        }

        /**
         * Restart the CUPS print server foreground service with new port/settings.
         */
        fun restart(context: Context) {
            val intent = Intent(context, CupsPrintService::class.java).apply {
                action = ACTION_RESTART
            }
            context.startForegroundService(intent)
            CuppaLog.i(TAG, "Restart command sent to CupsPrintService")
        }
    }

    private var ippServer: IppServer? = null
    private var advertiser: NetworkPrinterAdvertiser? = null
    private var dispatcher: PrintJobDispatcher? = null

    override fun onCreate() {
        super.onCreate()
        CuppaLog.d(TAG, "CupsPrintService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                CuppaLog.i(TAG, "Starting CUPS print server...")
                startForegroundWithNotification()
                startCupsServer()
            }
            ACTION_STOP -> {
                CuppaLog.i(TAG, "Stopping CUPS print server...")
                stopCupsServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_RESTART -> {
                CuppaLog.i(TAG, "Restarting CUPS print server with updated settings...")
                startForegroundWithNotification()
                stopCupsServer()
                startCupsServer()
            }
            else -> {
                CuppaLog.w(TAG, "Unknown action received: ${intent.action}")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        CuppaLog.i(TAG, "CupsPrintService onDestroy")
        stopCupsServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val prefs = getSharedPreferences("cuppa_settings", Context.MODE_PRIVATE)
        val preferredPort = prefs.getInt("server_port", 631)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("Listening on port $preferredPort · Ready to print")
            .setSmallIcon(R.drawable.ic_print_server)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        CuppaLog.d(TAG, "Foreground notification shown")
    }

    private fun startCupsServer() {
        // onStartCommand can legitimately fire more than once for the same live service (e.g.
        // BootReceiver reacting to MY_PACKAGE_REPLACED and MainActivity's own auto-resume check
        // both firing around the same time after an install/update) — without this guard, a
        // second overlapping ACTION_START created a second IppServer while the first was still
        // bound, which then failed to bind the same port and silently fell back further (631 ->
        // 8631 -> 9100), which is exactly the "port stuck on a fallback" symptom this fixes.
        if (ippServer?.isBound == true) {
            CuppaLog.w(TAG, "startCupsServer() called while already bound on port ${ippServer?.actualPort} — ignoring duplicate start")
            return
        }

        val configPath = filesDir.resolve("cups").absolutePath
        CuppaLog.i(TAG, "CUPS config path: $configPath")
        CuppaLog.i(TAG, "Native engine version: ${CupsEngine.getVersion()}")

        val prefs = getSharedPreferences("cuppa_settings", Context.MODE_PRIVATE)
        val preferredPort = prefs.getInt("server_port", 631)
        val tlsEnabled = prefs.getBoolean("tls_enabled", false)
        val mdnsEnabled = prefs.getBoolean("mdns_enabled", true)
        val airprintCompat = prefs.getBoolean("airprint_compat", true)
        val accessMode = prefs.getInt("access_mode", 0)
        val httpAuth = prefs.getBoolean("http_auth", false)
        val httpAuthUsername = if (httpAuth) prefs.getString("http_auth_username", null) else null
        val httpAuthPassword = if (httpAuth) prefs.getString("http_auth_password", null) else null

        val repo = com.cuppa.app.data.CupsRepository.getInstance(this)
        repo.loadPrinters(this)
        repo.loadJobHistory(this)
        repo.syncAllPrintersToNative()

        val success = CupsEngine.startServer(port = preferredPort, configPath = configPath)
        CuppaLog.i(TAG, "Native CUPS server start status: $success (port: $preferredPort)")

        CupsEngine.setTlsRequired(tlsEnabled)

        val localIp = com.cuppa.app.util.NetworkUtils.getLocalIpAddress()
        if (!localIp.isNullOrBlank()) {
            CupsEngine.setServerHost(localIp)
            CuppaLog.i(TAG, "Native CUPS server host dynamically set to $localIp")
        }

        var activePort = preferredPort
        if (success) {
            ippServer = IppServer(
                preferredPort,
                context = this,
                tlsEnabled = tlsEnabled,
                accessMode = accessMode,
                httpAuthUsername = httpAuthUsername,
                httpAuthPassword = httpAuthPassword
            )
            ippServer?.start()

            activePort = ippServer?.actualPort ?: preferredPort

            if (mdnsEnabled) {
                advertiser = NetworkPrinterAdvertiser(this)
                advertiser?.startAdvertising(activePort, tlsEnabled = tlsEnabled, airprintCompat = airprintCompat)
            } else {
                CuppaLog.i(TAG, "mDNS advertising disabled by user preference — server is IP-reachable only")
            }

            dispatcher = PrintJobDispatcher(this)
            dispatcher?.start()
        }

        repo.refreshServerState(activePort, requestedPort = preferredPort)
        updateNotification(isRunning = success, port = activePort, tlsEnabled = tlsEnabled, fellBackFromPrivilegedPort = success && preferredPort < 1024 && activePort != preferredPort)
    }

    private fun stopCupsServer() {
        CuppaLog.i(TAG, "Stopping CUPS server and all subsystems")
        
        dispatcher?.stop()
        dispatcher = null
        
        advertiser?.stopAdvertising()
        advertiser = null
        
        ippServer?.stop()
        ippServer = null
        
        CupsEngine.stopServer()
        com.cuppa.app.data.CupsRepository.getInstance(this).refreshServerState()
    }

    private fun updateNotification(
        isRunning: Boolean,
        port: Int = 631,
        tlsEnabled: Boolean = false,
        fellBackFromPrivilegedPort: Boolean = false
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (isRunning) {
            buildString {
                append("Active on port $port")
                if (fellBackFromPrivilegedPort) append(" (fallback — no root/Shizuku privilege)")
                append(" · IPP Everywhere")
                append(if (tlsEnabled) " · IPPS (TLS required)" else " · Unencrypted")
            }
        } else {
            "Server stopped"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cuppa Print Server")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_print_server)
            .setContentIntent(pendingIntent)
            .setOngoing(isRunning)
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }
}
