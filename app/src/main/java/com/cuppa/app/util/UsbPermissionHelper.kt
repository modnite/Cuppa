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
     * Determine if a USB device is a printer (class 7, bulk OUT interface, or known thermal printer).
     */
    fun isPrinterDevice(device: UsbDevice): Boolean {
        // 1. Check known printer database (by VID/PID or known VIDs)
        val knownVids = setOf(
            0x0483, // Rollo / STMicroelectronics
            0x0fe6, // ICS Advent
            0x1fc9, // NXP / Thermal
            0x04b8, // Epson
            0x04f9, // Brother
            0x0519, // Star Micronics
            0x0dd4, // Custom Engineering
            0x1504, // Nippon
            0x1a86, // QinHeng (CH340/CH341 USB-to-Serial / Thermal)
            0x20d1, // Besta
            0x28e9, // GigaDevice
            0x6868, // Xprinter
            0x03f0, // HP
            0x04a9, // Canon
            0x043d, // Lexmark
            0x0922, // Dymo
            0x0a5f, // Zebra
            0x1208, // POSIFLEX
            0x0525, // Netchip
            0x10c4, // Silicon Labs (CP210x)
            0x0403  // FTDI
        )
        if (knownVids.contains(device.vendorId)) return true
        if (PrinterDriverDatabase.lookupPrinter(device.vendorId, device.productId) != null) return true

        // 2. Check interface classes (Class 7 = Printer, or any bulk OUT endpoint)
        try {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) return true
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback: if device class is 0 (per-interface), 7 (printer), or 255 (vendor-specific)
        if (device.deviceClass == UsbConstants.USB_CLASS_PER_INTERFACE ||
            device.deviceClass == UsbConstants.USB_CLASS_PRINTER ||
            device.deviceClass == 255) {
            return true
        }

        return false
    }
}
