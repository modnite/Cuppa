package com.cuppa.app.backend.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import com.cuppa.app.util.CuppaLog
import com.cuppa.app.util.safeDescription
import com.cuppa.app.util.safeProductName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * UsbPrinterBackend — Direct USB transport for printers over Android's UsbManager.
 *
 * Provides direct printing for USB thermal label printers (such as the Rollo X1038,
 * Zebra ZD/GK series, and ESC/POS receipt printers) bypassing network IPP overhead.
 */
class UsbPrinterBackend(private val context: Context) {

    companion object {
        private const val TAG = "UsbPrinterBackend"
        private const val ACTION_USB_PERMISSION = "com.cuppa.app.USB_PERMISSION"
        private const val USB_CLASS_PRINTER = 7
        // Confirmed against a real Rollo X1038 (VID 0x09C5 / PID 0x0588): a large unpaced chunk
        // (previously 16KB) reports a clean bulk transfer with no error, but the printer's own
        // onboard buffer silently drops it and never fires the head. Small chunks with a short
        // pause between each — matching an earlier working implementation for this exact
        // hardware — give its buffer time to actually drain.
        private const val CHUNK_SIZE = 1024
        private const val INTER_CHUNK_DELAY_MS = 10L
        private const val TIMEOUT_MS = 5000  // 5 seconds bulk transfer timeout
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /**
     * Check if the app has permission to access the specified USB device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return com.cuppa.app.util.UsbPermissionHelper.hasPermission(context, device)
    }

    /**
     * Request user permission for a USB device via Android's UsbManager system dialog.
     */
    fun requestPermission(device: UsbDevice) {
        com.cuppa.app.util.UsbPermissionHelper.requestUsbPermission(context, device)
    }

    /**
     * Find a connected UsbDevice by its CUPS-style or Cuppa USB URI.
     * Supported formats:
     * - usb://0x0483/0x5720
     * - usb://0x0483/0x5720?serial=...
     * - usb:0x0483:0x5720:...
     *
     * If URI matching fails, falls back gracefully to the first connected USB printer.
     */
    fun findDeviceByUri(uri: String): UsbDevice? {
        val manager = usbManager ?: return null
        // Case-insensitive: a URI typed into a UI text field can arrive with its scheme
        // autocapitalized ("USB://...") by the field's own capitalization rules, which a plain
        // removePrefix (case-sensitive) silently fails to strip, leaving the VID/PID unparsed and
        // falling through to the fallback match below.
        val clean = uri.replaceFirst(Regex("^usb://", RegexOption.IGNORE_CASE), "")
            .replaceFirst(Regex("^usb:", RegexOption.IGNORE_CASE), "")
        val parts = clean.split('/', '?', ':')

        if (parts.size >= 2) {
            val vid = parseHexOrDec(parts[0])
            val pid = parseHexOrDec(parts[1])
            if (vid != null && pid != null) {
                try {
                    for ((_, device) in manager.deviceList) {
                        if (device.vendorId == vid && device.productId == pid) {
                            CuppaLog.i(TAG, "Matched USB device by VID/PID: ${device.safeDescription()}")
                            return device
                        }
                    }
                } catch (e: Exception) {
                    CuppaLog.w(TAG, "Exception scanning deviceList for VID/PID", e)
                }
            }
        }

        // Fallback: check if any attached USB device declares an actual USB Printer Class
        // interface. Deliberately stricter than findPrinterInterfaceAndEndpoint's own fallback
        // (which also accepts any interface with a bulk OUT endpoint) — that looser check is
        // fine once we already know which specific device we're talking to, but here it's
        // choosing WHICH device to target in the first place, and plenty of non-printer USB
        // peripherals (a USB Ethernet adapter, confirmed live) also expose a bulk OUT endpoint.
        try {
            for ((_, device) in manager.deviceList) {
                val hasPrinterClassInterface = (0 until device.interfaceCount).any { i ->
                    device.getInterface(i).interfaceClass == USB_CLASS_PRINTER
                }
                if (hasPrinterClassInterface) {
                    CuppaLog.i(TAG, "Fallback match to attached USB printer: ${device.safeDescription()}")
                    return device
                }
            }
        } catch (e: Exception) {
            CuppaLog.w(TAG, "Exception scanning deviceList for fallback printer", e)
        }

        return null
    }

    /**
     * Find a connected UsbDevice by VID and PID.
     */
    fun findDevice(vendorId: Int, productId: Int): UsbDevice? {
        val manager = usbManager ?: return null
        try {
            for ((_, device) in manager.deviceList) {
                if (device.vendorId == vendorId && device.productId == productId) {
                    return device
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Send raw driver bytes (TSPL, ZPL, ESC/POS, EPL, PCL) directly to a USB printer bulk OUT endpoint.
     *
     * @param device Target USB printer device.
     * @param data Raw byte payload to transfer.
     * @param progress Optional progress callback reporting bytes sent so far.
     * @return Result containing total number of bytes successfully transferred.
     */
    suspend fun sendRawBytes(
        device: UsbDevice,
        data: ByteArray,
        progress: ((bytesSent: Int, total: Int) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        val manager = usbManager ?: return@withContext Result.failure(IOException("USB manager not available"))
        if (!manager.hasPermission(device)) {
            CuppaLog.w(TAG, "USB permission missing for ${device.safeDescription()} - Requesting prompt now")
            com.cuppa.app.util.UsbPermissionHelper.requestUsbPermission(context, device)
            return@withContext Result.failure(
                SecurityException("USB permission prompt requested for ${device.safeDescription()}. Please tap 'Allow' on the dialog, then tap Print again.")
            )
        }

        val (printerInterface, bulkOutEndpoint) = findPrinterInterfaceAndEndpoint(device)
            ?: return@withContext Result.failure(
                IOException("Could not locate a valid printer bulk OUT endpoint on ${device.safeDescription()}")
            )

        var connection: UsbDeviceConnection? = null
        try {
            connection = manager.openDevice(device)
                ?: return@withContext Result.failure(
                    IOException("Failed to open USB connection to ${device.safeDescription()}")
                )

            val claimed = connection.claimInterface(printerInterface, true)
            if (!claimed) {
                return@withContext Result.failure(
                    IOException("Failed to claim USB interface ${printerInterface.id} on ${device.safeDescription()}")
                )
            }

            CuppaLog.i(TAG, "Streaming ${data.size} bytes to ${device.safeDescription()} over USB bulk OUT (endpoint 0x${Integer.toHexString(bulkOutEndpoint.address)})")

            var totalSent = 0
            val totalBytes = data.size

            while (totalSent < totalBytes) {
                val chunkSize = minOf(CHUNK_SIZE, totalBytes - totalSent)
                val transferred = connection.bulkTransfer(
                    bulkOutEndpoint,
                    data,
                    totalSent,
                    chunkSize,
                    TIMEOUT_MS
                )

                if (transferred < 0) {
                    CuppaLog.e(TAG, "USB bulk transfer error ($transferred) after sending $totalSent of $totalBytes bytes")
                    return@withContext Result.failure(
                        IOException("USB bulk transfer error ($transferred) after sending $totalSent of $totalBytes bytes")
                    )
                }

                totalSent += transferred
                progress?.invoke(totalSent, totalBytes)

                if (totalSent < totalBytes) {
                    delay(INTER_CHUNK_DELAY_MS)
                }
            }

            CuppaLog.i(TAG, "Successfully transferred $totalSent bytes to ${device.safeDescription()}")
            Result.success(totalSent)
        } catch (e: Exception) {
            CuppaLog.e(TAG, "Error during USB print transfer", e)
            Result.failure(e)
        } finally {
            try {
                connection?.releaseInterface(printerInterface)
                connection?.close()
            } catch (e: Exception) {
                CuppaLog.w(TAG, "Error closing USB connection", e)
            }
        }
    }

    /**
     * Ask a Rollo-family printer for its real-time hardware status with `<ESC>!?` and read the
     * one status byte back over bulk IN. Protocol recovered from an earlier from-scratch
     * reverse-engineering pass on a real Rollo X1038 (VID 0x09C5 / PID 0x0588):
     * 0x00 ready, bit0 head open, bits1-2 media out, bit5 paused, bit6 printing.
     */
    suspend fun queryHardwareStatus(device: UsbDevice): Result<String> = withContext(Dispatchers.IO) {
        val manager = usbManager ?: return@withContext Result.failure(IOException("USB manager not available"))
        if (!manager.hasPermission(device)) return@withContext Result.failure(SecurityException("No USB permission"))
        val (iface, outEp) = findPrinterInterfaceAndEndpoint(device)
            ?: return@withContext Result.failure(IOException("No printer endpoint"))
        var inEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) inEp = ep
        }
        val connection = manager.openDevice(device)
            ?: return@withContext Result.failure(IOException("Failed to open device"))
        try {
            if (!connection.claimInterface(iface, true)) return@withContext Result.failure(IOException("Failed to claim interface"))
            if (inEp == null) return@withContext Result.failure(IOException("No bulk IN endpoint, cannot read status"))
            val cmd = byteArrayOf(0x1B, 0x21, 0x3F)
            val sent = connection.bulkTransfer(outEp, cmd, cmd.size, 1000)
            if (sent <= 0) return@withContext Result.failure(IOException("Status query send failed ($sent)"))
            val buf = ByteArray(8)
            val read = connection.bulkTransfer(inEp, buf, buf.size, 1500)
            if (read <= 0) return@withContext Result.failure(IOException("No status reply ($read)"))
            val s = buf[0].toInt() and 0xFF
            val flags = buildList {
                if (s == 0) add("ready")
                if (s and 0x01 != 0) add("head/cover open")
                if (s and 0x06 != 0) add("media out / gap not found")
                if (s and 0x20 != 0) add("paused")
                if (s and 0x40 != 0) add("printing")
            }
            val msg = "0x%02X (%s)".format(s, flags.joinToString(", ").ifEmpty { "unknown bits" })
            CuppaLog.i(TAG, "Hardware status: $msg")
            connection.releaseInterface(iface)
            Result.success(msg)
        } finally {
            connection.close()
        }
    }

    /**
     * Query IEEE 1284 Device ID via USB Class-Specific Control Request (0xA1, GET_DEVICE_ID).
     */
    fun queryDeviceId(device: UsbDevice): String? {
        val manager = usbManager ?: return null
        if (!manager.hasPermission(device)) return null

        val printerInterface = findPrinterInterface(device) ?: return null
        val connection = manager.openDevice(device) ?: return null

        return try {
            val buffer = ByteArray(1024)
            val length = connection.controlTransfer(
                0xA1, // requestType: Device-to-host, Class, Interface
                0,    // request: GET_DEVICE_ID
                0,    // value: config index
                printerInterface.id, // index: interface number
                buffer,
                buffer.size,
                1000  // timeout 1s
            )

            if (length > 2) {
                val strLen = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
                val actualLen = minOf(strLen - 2, length - 2)
                if (actualLen > 0) {
                    val idStr = String(buffer, 2, actualLen, Charsets.US_ASCII)
                    CuppaLog.i(TAG, "Queried IEEE 1284 Device ID: $idStr")
                    idStr
                } else null
            } else null
        } catch (e: Exception) {
            CuppaLog.w(TAG, "Failed to query IEEE 1284 device ID", e)
            null
        } finally {
            try { connection.close() } catch (_: Exception) {}
        }
    }

    private fun findPrinterInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_PRINTER) {
                return iface
            }
        }
        return if (device.interfaceCount > 0) device.getInterface(0) else null
    }

    private fun findPrinterInterfaceAndEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        // Priority 1: Interface with class 7 (Printer) and bulk OUT endpoint
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_PRINTER) {
                val endpoint = findBulkOutEndpoint(iface)
                if (endpoint != null) {
                    return Pair(iface, endpoint)
                }
            }
        }

        // Priority 2: Any interface with a bulk OUT endpoint
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val endpoint = findBulkOutEndpoint(iface)
            if (endpoint != null) {
                return Pair(iface, endpoint)
            }
        }

        return null
    }

    private fun findBulkOutEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                return ep
            }
        }
        return null
    }

    private fun parseHexOrDec(s: String): Int? {
        return try {
            if (s.startsWith("0x", ignoreCase = true)) {
                s.substring(2).toInt(16)
            } else {
                s.toInt()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
