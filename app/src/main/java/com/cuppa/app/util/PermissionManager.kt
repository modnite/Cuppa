package com.cuppa.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * PermissionManager — Comprehensive permission authority for Cuppa Print Server.
 *
 * Tracks and requests all runtime, hardware, and system privileges required
 * for continuous background printing:
 * 1. USB Host permissions (UsbManager)
 * 2. Notification permissions (POST_NOTIFICATIONS for foreground service)
 * 3. Battery optimization exclusion (PowerManager Doze mode bypass)
 */
object PermissionManager {

    /**
     * Check if notification permission is granted (required for Android 13+ foreground service).
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Check if the app is exempt from battery optimizations (essential for 24/7 print servers).
     */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Request battery optimization exemption via system intent.
     */
    fun requestIgnoreBatteryOptimization(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            CuppaLog.w("PermissionManager", "Direct battery optimization intent failed, opening battery settings", e)
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {}
        }
    }

    /**
     * Get all connected USB devices along with their current permission status.
     */
    fun getConnectedUsbDevices(context: Context): List<Pair<UsbDevice, Boolean>> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return try {
            manager.deviceList.values.map { device ->
                val hasPerm = UsbPermissionHelper.hasPermission(context, device)
                Pair(device, hasPerm)
            }
        } catch (e: Exception) {
            CuppaLog.w("PermissionManager", "Error querying connected USB devices: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get only attached printer devices that lack USB permission.
     */
    fun getUnpermittedUsbPrinters(context: Context): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return try {
            manager.deviceList.values.filter { device ->
                !UsbPermissionHelper.hasPermission(context, device) &&
                    UsbPermissionHelper.isPrinterDevice(device)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
