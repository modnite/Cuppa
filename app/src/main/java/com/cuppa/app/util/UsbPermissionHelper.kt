package com.cuppa.app.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.cuppa.app.discovery.PrinterDriverDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Safely get the USB device name without throwing a SecurityException on Android 10+.
 * In Android 10+ (API 29+), calling UsbDevice.productName or manufacturerName
 * throws java.lang.SecurityException if the caller does not hold USB permission.
 */
fun UsbDevice.safeProductName(): String {
    return try {
        productName?.takeIf { it.isNotBlank() }
            ?: "USB Device (VID 0x${Integer.toHexString(vendorId)}, PID 0x${Integer.toHexString(productId)})"
    } catch (_: Exception) {
        "USB Device (VID 0x${Integer.toHexString(vendorId)}, PID 0x${Integer.toHexString(productId)})"
    }
}

fun UsbDevice.safeManufacturerName(): String {
    return try {
        manufacturerName ?: ""
    } catch (_: Exception) {
        ""
    }
}

fun UsbDevice.safeDescription(): String {
    val vidHex = Integer.toHexString(vendorId).padStart(4, '0')
    val pidHex = Integer.toHexString(productId).padStart(4, '0')
    val name = try {
        productName?.takeIf { it.isNotBlank() } ?: "USB Printer/Device"
    } catch (_: Exception) {
        "USB Device"
    }
    return "$name (VID 0x$vidHex, PID 0x$pidHex)"
}

/**
 * UsbPermissionHelper — Centralized USB permission dispatcher and state tracker.
 */
object UsbPermissionHelper {

    private const val TAG = "UsbPermissionHelper"
    const val ACTION_USB_PERMISSION = "com.cuppa.app.USB_PERMISSION"

    private val _permissionEvent = MutableStateFlow(0L)
    val permissionEvent: StateFlow<Long> = _permissionEvent.asStateFlow()

    fun notifyPermissionChanged() {
        _permissionEvent.value = System.currentTimeMillis()
    }

    /**
     * Check if the app has permission for a specific UsbDevice.
     */
    fun hasPermission(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return try {
            manager.hasPermission(device)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Request user permission for a UsbDevice via the system prompt dialog.
     */
    fun requestUsbPermission(context: Context, device: UsbDevice) {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return

        try {
            if (manager.hasPermission(device)) {
                CuppaLog.i(TAG, "Already have permission for USB device: ${device.safeDescription()}")
                notifyPermissionChanged()
                return
            }
        } catch (_: Exception) {}

        CuppaLog.i(TAG, "Requesting system USB permission dialog for ${device.safeDescription()}")

        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context.applicationContext,
            device.deviceId,
            intent,
            flags
        )

        try {
            manager.requestPermission(device, pendingIntent)
        } catch (e: Exception) {
            CuppaLog.e(TAG, "Error invoking UsbManager.requestPermission: ${e.message}", e)
        }

        // Failsafe for rooted devices: also grant root node permissions
        if (RootHelper.isRootAvailable()) {
            CoroutineScope(Dispatchers.IO).launch {
                RootHelper.grantUsbPermissions(context.packageName)
            }
        }
    }

    /**
     * Scan all connected USB devices and automatically prompt for any device without permission.
     */
    fun checkAndRequestAllPrinters(context: Context) {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        try {
            val devices = manager.deviceList.values
            for (device in devices) {
                if (isPrinterDevice(device)) {
                    val hasPerm = try { manager.hasPermission(device) } catch (_: Exception) { false }
                    if (!hasPerm) {
                        CuppaLog.i(TAG, "Auto-requesting USB permission for attached printer: ${device.safeDescription()}")
                        requestUsbPermission(context, device)
                    }
                }
            }
        } catch (e: Exception) {
            CuppaLog.w(TAG, "Error in checkAndRequestAllPrinters: ${e.message}")
        }
    }

    /**
     * Determine if a USB device is a printer we should ask permission for.
     *
     * A device qualifies if it declares the USB Printer class (7) on any interface, or it is a
     * known thermal/label printer by VID/PID. Deliberately NOT "has a bulk OUT endpoint" or
     * "device class is per-interface/vendor-specific": those match nearly every USB peripheral
     * (audio adapters, game controllers, Ethernet dongles, hubs), and each false match costs the
     * user a system permission dialog.
     */
    fun isPrinterDevice(device: UsbDevice): Boolean {
        if (PrinterDriverDatabase.lookupPrinter(device.vendorId, device.productId)?.let {
                it.printerType != PrinterDriverDatabase.PrinterType.STANDARD
            } == true) {
            return true
        }
        try {
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) return true
            }
        } catch (_: Exception) {}
        return device.deviceClass == UsbConstants.USB_CLASS_PRINTER
    }
}
