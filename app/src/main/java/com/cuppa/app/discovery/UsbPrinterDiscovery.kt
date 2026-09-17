package com.cuppa.app.discovery

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.cuppa.app.util.CuppaLog as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UsbPrinterDiscovery — Detects USB printers via Android's UsbManager.
 *
 * Functionality:
 * - Enumerates currently connected USB devices that match printer class (7) or known VID/PIDs
 * - Registers a BroadcastReceiver for USB attach/detach/permission events
 * - Provides a reactive [discoveredPrinters] StateFlow
 * - Manages USB permission requests
 *
 * Usage:
 * ```
 * val discovery = UsbPrinterDiscovery(context)
 * discovery.start()
 * // observe discovery.discoveredPrinters
 * discovery.stop()
 * ```
 */
class UsbPrinterDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "UsbPrinterDiscovery"
        private const val ACTION_USB_PERMISSION = "com.cuppa.app.USB_PERMISSION"
        private const val USB_CLASS_PRINTER = 7
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _discoveredPrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    val discoveredPrinters: StateFlow<List<DiscoveredPrinter>> = _discoveredPrinters.asStateFlow()

    private var isStarted = false
    private var receiver: BroadcastReceiver? = null

    /**
     * Start USB printer discovery.
     * Scans currently connected devices and registers for attach/detach events.
     */
    fun start() {
        if (isStarted) return
        isStarted = true

        Log.i(TAG, "Starting USB printer discovery")

        // Scan currently connected devices safely
        scanConnectedDevices()

        // Register for USB events safely
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        device?.let { onDeviceDetached(it) }
                    }
                    ACTION_USB_PERMISSION -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Log.i(TAG, "USB permission result: device=${device?.deviceName}, granted=$granted")
                        if (granted && device != null) {
                            scanConnectedDevices() // Refresh with updated permission state
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register USB broadcast receiver: ${e.message}")
        }

        Log.i(TAG, "USB printer discovery started, ${_discoveredPrinters.value.size} printer(s) found")
    }

    /**
     * Stop USB printer discovery and unregister the BroadcastReceiver.
     */
    fun stop() {
        if (!isStarted) return
        isStarted = false

        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered
            }
        }
        receiver = null

        Log.i(TAG, "USB printer discovery stopped")
    }

    /**
     * Request USB permission for a specific device.
     */
    fun requestPermission(device: UsbDevice) {
        com.cuppa.app.util.UsbPermissionHelper.requestUsbPermission(context, device)
    }

    /**
     * Request permission for a discovered printer by its ID.
     */
    fun requestPermissionForPrinter(printerId: String) {
        val device = findUsbDeviceForPrinter(printerId)
        if (device != null) {
            requestPermission(device)
        } else {
            Log.w(TAG, "No USB device found for printer ID: $printerId")
        }
    }

    /**
     * Check if we have permission for a specific USB device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager?.hasPermission(device) == true
    }

    /**
     * Re-scan all connected USB devices.
     */
    fun rescan() {
        scanConnectedDevices()
    }

    // ---- Private ----

    private fun scanConnectedDevices() {
        val manager = usbManager ?: return
        val printers = mutableListOf<DiscoveredPrinter>()

        try {
            for ((_, device) in manager.deviceList) {
                if (isPrinterDevice(device)) {
                    val printer = createDiscoveredPrinter(device)
                    printers.add(printer)
                    Log.d(TAG, "Found USB printer: ${printer.name} (${printer.makeAndModel}) at ${device.deviceName}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning USB devices: ${e.message}", e)
        }

        _discoveredPrinters.value = printers
    }

    private fun onDeviceAttached(device: UsbDevice) {
        if (!isPrinterDevice(device)) return

        val printer = createDiscoveredPrinter(device)
        Log.i(TAG, "USB printer attached: ${printer.name}")

        val current = _discoveredPrinters.value.toMutableList()
        current.removeAll { it.id == printer.id }
        current.add(printer)
        _discoveredPrinters.value = current
    }

    private fun onDeviceDetached(device: UsbDevice) {
        val id = makeDeviceId(device)
        Log.i(TAG, "USB printer detached: ${device.deviceName}")

        val current = _discoveredPrinters.value.toMutableList()
        current.removeAll { it.id == id }
        _discoveredPrinters.value = current
    }

    /**
     * Determine if a USB device is a printer.
     * Checks device class, interface class, or known VID/PID.
     */
    private fun isPrinterDevice(device: UsbDevice): Boolean {
        // Check device-level class
        if (device.deviceClass == USB_CLASS_PRINTER) return true

        // Check interface-level class (most printers report class at interface level)
        for (i in 0 until device.interfaceCount) {
            try {
                if (device.getInterface(i).interfaceClass == USB_CLASS_PRINTER) return true
            } catch (_: Exception) {}
        }

        // Check known VID/PID
        if (PrinterDriverDatabase.lookupPrinter(device.vendorId, device.productId) != null) return true

        return false
    }

    private fun createDiscoveredPrinter(device: UsbDevice): DiscoveredPrinter {
        val vid = device.vendorId
        val pid = device.productId
        val (driver, knownName) = PrinterDriverDatabase.suggestDriver(vid, pid)

        val hasPerm = try {
            usbManager?.hasPermission(device) == true
        } catch (_: Exception) {
            false
        }

        val rawProductName = try {
            device.productName?.trim()
        } catch (_: Exception) {
            null
        }

        // If the USB descriptor string is generic ("Printer", "USB Printer"), prefer our database name
        val name = if (rawProductName.isNullOrBlank() ||
            rawProductName.equals("Printer", ignoreCase = true) ||
            rawProductName.equals("USB Printer", ignoreCase = true) ||
            rawProductName.equals("USB Printing Support", ignoreCase = true)
        ) {
            knownName
        } else {
            rawProductName
        }

        val manufacturer = try {
            device.manufacturerName ?: ""
        } catch (_: Exception) {
            ""
        }

        val makeAndModel = if (manufacturer.isNotBlank() && !name.startsWith(manufacturer, ignoreCase = true)) {
            "$manufacturer $name"
        } else {
            name
        }

        val serial = try {
            if (hasPerm) device.serialNumber ?: "" else ""
        } catch (_: Exception) {
            ""
        }

        val capabilities = buildMap {
            put("vendorId", String.format("0x%04X", vid))
            put("productId", String.format("0x%04X", pid))
            put("deviceName", device.deviceName)
            if (manufacturer.isNotBlank()) put("manufacturer", manufacturer)
            if (serial.isNotBlank()) put("serialNumber", serial)
            put("interfaceCount", device.interfaceCount.toString())
            put("hasPermission", hasPerm.toString())
            PrinterDriverDatabase.getDefaultLabelSize(vid, pid)?.let {
                put("defaultLabelSize", it)
            }
            put("isThermal", PrinterDriverDatabase.isThermalPrinter(vid, pid).toString())
        }

        return DiscoveredPrinter(
            id = makeDeviceId(device),
            name = name,
            transport = PrinterTransport.USB,
            uri = "usb://${String.format("0x%04X", vid)}/${String.format("0x%04X", pid)}${if (serial.isNotBlank()) "?serial=$serial" else ""}",
            makeAndModel = makeAndModel,
            suggestedDriver = driver,
            capabilities = capabilities
        )
    }

    private fun makeDeviceId(device: UsbDevice): String {
        val hasPerm = try {
            usbManager?.hasPermission(device) == true
        } catch (_: Exception) {
            false
        }
        val serial = try {
            if (hasPerm) device.serialNumber ?: device.deviceId.toString() else device.deviceId.toString()
        } catch (_: Exception) {
            device.deviceId.toString()
        }
        return "usb:${String.format("0x%04X", device.vendorId)}:${String.format("0x%04X", device.productId)}:$serial"
    }

    private fun findUsbDeviceForPrinter(printerId: String): UsbDevice? {
        val manager = usbManager ?: return null
        try {
            for ((_, device) in manager.deviceList) {
                if (makeDeviceId(device) == printerId) return device
            }
        } catch (_: Exception) {}
        return null
    }
}
