package com.cuppa.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.cuppa.app.util.CuppaLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * CuppaApplication — Custom Application class.
 *
 * Handles one-time initialization:
 * - Notification channel creation (required for foreground service)
 * - Future: DI initialization, CUPS config directory setup
 */
class CuppaApplication : Application() {

    companion object {
        const val TAG = "Cuppa"
        const val NOTIFICATION_CHANNEL_ID = "cuppa_print_server"
        const val NOTIFICATION_ID = 1
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Cuppa Application starting...")

        createNotificationChannel()

        // Initialize Theme preferences
        com.cuppa.app.ui.theme.ThemeState.initialize(this)

        // Initialize Shizuku binder and permission lifecycle listeners
        com.cuppa.app.util.ShizukuHelper.initialize()

        // Keep printer reachability current: on network changes, when the app comes forward and on
        // a timer that is faster while the app is on screen.
        val reachability = com.cuppa.app.server.PrinterReachability.getInstance(this)
        reachability.start()
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) {
                started++
                reachability.setForeground(true)
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) reachability.setForeground(false)
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })

        // Asynchronously initialize and unpack bundled PPD driver assets
        appScope.launch {
            com.cuppa.app.data.ppd.PpdManager(this@CuppaApplication).initializeBundledPpds()
        }

        Log.i(TAG, "Cuppa Application initialized")
    }

    /**
     * Creates the notification channel for the foreground service.
     * This is required on Android 8.0+ (API 26) for persistent notifications.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW, // Low: no sound, shows in status bar
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false) // Don't show badge on app icon
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created: $NOTIFICATION_CHANNEL_ID")
    }
}
