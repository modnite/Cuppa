package com.cuppa.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.cuppa.app.service.CupsPrintService
import com.cuppa.app.util.CuppaLog

/**
 * BootReceiver — Listens for system boot completion to auto-start the CUPS print service.
 *
 * Checks the persistent user preference "auto_start_boot" and, if enabled, launches
 * CupsPrintService as a foreground service.
 *
 * Speed matters here: BOOT_COMPLETED is dispatched to every registered app on the device, and
 * third-party receivers can end up queued tens of seconds behind system/pre-installed apps'
 * receivers. Two things are done to minimize our own added latency on top of that queueing:
 *  1. The intent-filter below declares a high priority so the OS delivers to us earlier relative
 *     to other apps that didn't bother setting one.
 *  2. startForegroundService() is called synchronously and immediately — it is a fire-and-forget
 *     Binder call, not a blocking wait for the service to finish starting, so there is no reason
 *     to hop onto a coroutine/background thread first. The previous version pre-emptively shelled
 *     out to `su` here to unlock port 631, which is redundant: IppServer.start() already does that
 *     same root/sysctl fallback itself if binding to 631 fails, so doing it again here only added
 *     boot-time latency before the service was even started.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        CuppaLog.i(TAG, "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getSharedPreferences("cuppa_settings", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_boot", false)
            val port = prefs.getInt("server_port", 631)

            CuppaLog.i(TAG, "Auto-start on boot setting: $autoStart (configured port: $port)")

            if (autoStart) {
                try {
                    val serviceIntent = Intent(context, CupsPrintService::class.java).apply {
                        this.action = CupsPrintService.ACTION_START
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                    prefs.edit().putBoolean("server_should_be_running", true).apply()
                    CuppaLog.i(TAG, "Successfully triggered CupsPrintService foreground start on boot")
                } catch (e: Exception) {
                    CuppaLog.e(TAG, "Failed to start CupsPrintService on boot", e)
                }
            } else {
                CuppaLog.d(TAG, "Auto-start on boot is disabled in settings; skipping service launch")
            }
        }
    }
}
