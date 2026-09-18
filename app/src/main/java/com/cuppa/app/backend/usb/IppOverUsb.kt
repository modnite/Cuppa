package com.cuppa.app.backend.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * IPP over USB (USB printer class, subclass 1, protocol 4). Driverless printers such as the HP
 * DeskJet 2700 expose this next to the plain printer interface. It carries ordinary HTTP with IPP
 * bodies over two bulk endpoints, so a job is a normal Print-Job with a PDF or PWG-Raster document
 * instead of a printer language the model may not understand.
 */
object IppOverUsb {

    class Channel(val iface: UsbInterface, val out: UsbEndpoint, val input: UsbEndpoint)

    class Response(val status: Int, val attrs: Map<String, List<String>>)

    fun findChannel(device: UsbDevice): Channel? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_PRINTER ||
                iface.interfaceSubclass != 1 || iface.interfaceProtocol != 4) continue
            var out: UsbEndpoint? = null
            var inp: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_OUT) { if (out == null) out = ep } else if (inp == null) inp = ep
            }
            if (out != null && inp != null) return Channel(iface, out, inp)
        }
        return null
    }

    // ---- IPP encoding ----

    class Request(operation: Int, requestId: Int = 1) {
        private val out = ByteArrayOutputStream()

        init {
            out.write(byteArrayOf(2, 0))
            out.write(operation shr 8); out.write(operation and 0xFF)
            out.write(byteArrayOf((requestId ushr 24).toByte(), (requestId ushr 16).toByte(), (requestId ushr 8).toByte(), requestId.toByte()))
            group(0x01)
            string(0x47, "attributes-charset", "utf-8")
            string(0x48, "attributes-natural-language", "en")
            string(0x45, "printer-uri", "ipp://localhost/ipp/print")
        }

        fun group(tag: Int) = apply { out.write(tag) }

        fun string(tag: Int, name: String, value: String) = apply {
            out.write(tag)
            val n = name.toByteArray(); val v = value.toByteArray()
            out.write(n.size shr 8); out.write(n.size and 0xFF); out.write(n)
            out.write(v.size shr 8); out.write(v.size and 0xFF); out.write(v)
        }

        fun more(tag: Int, value: String) = apply {
            out.write(tag)
            out.write(0); out.write(0)
            val v = value.toByteArray()
            out.write(v.size shr 8); out.write(v.size and 0xFF); out.write(v)
        }

        fun integer(tag: Int, name: String, value: Int) = apply {
            out.write(tag)
            val n = name.toByteArray()
            out.write(n.size shr 8); out.write(n.size and 0xFF); out.write(n)
            out.write(0); out.write(4)
            out.write(byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()))
        }

        fun build(document: ByteArray? = null): ByteArray {
            out.write(0x03)
            if (document != null) out.write(document)
            return out.toByteArray()
        }
    }

    private fun parseIpp(body: ByteArray): Response {
        if (body.size < 9) throw IOException("IPP reply too short (${body.size} bytes)")
        val status = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
        val attrs = LinkedHashMap<String, MutableList<String>>()
        var pos = 8
        var lastName = ""
        while (pos < body.size) {
            val tag = body[pos++].toInt() and 0xFF
            if (tag == 0x03) break
            if (tag <= 0x05) continue
            if (pos + 2 > body.size) break
            val nameLen = ((body[pos].toInt() and 0xFF) shl 8) or (body[pos + 1].toInt() and 0xFF)
            pos += 2
            val name = if (nameLen > 0) String(body, pos, nameLen) else lastName
            pos += nameLen
            if (pos + 2 > body.size) break
            val valLen = ((body[pos].toInt() and 0xFF) shl 8) or (body[pos + 1].toInt() and 0xFF)
            pos += 2
            if (pos + valLen > body.size) break
            if (nameLen > 0) lastName = name
            val text: String? = when (tag) {
                0x21, 0x23 -> if (valLen == 4) {
                    (((body[pos].toInt() and 0xFF) shl 24) or ((body[pos + 1].toInt() and 0xFF) shl 16) or
                        ((body[pos + 2].toInt() and 0xFF) shl 8) or (body[pos + 3].toInt() and 0xFF)).toString()
                } else null
                0x22 -> if (valLen == 1) (body[pos].toInt() != 0).toString() else null
                0x41, 0x42, 0x44, 0x45, 0x47, 0x48, 0x49 -> String(body, pos, valLen)
                else -> null
            }
            if (text != null) attrs.getOrPut(name) { mutableListOf() }.add(text)
            pos += valLen
        }
        return Response(status, attrs)
    }

    // ---- transport ----

    private const val CHUNK = 16 * 1024
    private const val WRITE_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Sends one IPP message (with any document data attached) and returns the parsed reply. */
    fun exchange(manager: UsbManager, device: UsbDevice, ch: Channel, ipp: ByteArray): Result<Response> {
        val conn = manager.openDevice(device) ?: return Result.failure(IOException("Could not open the USB device"))
        try {
            if (!conn.claimInterface(ch.iface, true)) return Result.failure(IOException("Could not claim the IPP-over-USB interface"))
            if (!conn.setInterface(ch.iface)) return Result.failure(IOException("Could not select the IPP-over-USB alternate setting"))

            val header = ("POST /ipp/print HTTP/1.1\r\nHost: localhost\r\nUser-Agent: Cuppa\r\n" +
                "Content-Type: application/ipp\r\nContent-Length: ${ipp.size}\r\n\r\n").toByteArray()
            write(conn, ch.out, header)
            write(conn, ch.out, ipp)

            val body = readHttpBody(conn, ch.input)
            return Result.success(parseIpp(body))
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            // IPP-over-USB closes a connection by selecting alternate setting 0 again. Skipping
            // this after a failed request (a paper jam mid-upload, for one) leaves the printer
            // waiting for the rest of a request that never comes, and every later request then gets
            // no reply until the cable is replugged.
            try { device.findAltZero(ch.iface)?.let { conn.setInterface(it) } } catch (_: Exception) {}
            try { conn.releaseInterface(ch.iface) } catch (_: Exception) {}
            conn.close()
        }
    }

    private fun UsbDevice.findAltZero(active: UsbInterface): UsbInterface? {
        for (i in 0 until interfaceCount) {
            val candidate = getInterface(i)
            if (candidate.id == active.id && candidate.alternateSetting == 0) return candidate
        }
        return null
    }

    private fun write(conn: android.hardware.usb.UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = minOf(CHUNK, data.size - off)
            val sent = conn.bulkTransfer(ep, data, off, n, WRITE_TIMEOUT_MS)
            if (sent < 0) throw IOException("USB write failed after $off of ${data.size} bytes")
            off += sent
        }
    }

    private fun readHttpBody(conn: android.hardware.usb.UsbDeviceConnection, ep: UsbEndpoint): ByteArray {
        val buf = ByteArray(16 * 1024) // usbfs rejects larger single transfers (bulkTransfer returns -1 at once)
        val acc = ByteArrayOutputStream()
        var headerEnd = -1
        var contentLength = -1
        var chunked = false
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS * 2
        while (System.currentTimeMillis() < deadline) {
            val n = conn.bulkTransfer(ep, buf, buf.size, READ_TIMEOUT_MS)
            if (n > 0) acc.write(buf, 0, n)
            val bytes = acc.toByteArray()
            if (headerEnd < 0) {
                val idx = indexOf(bytes, "\r\n\r\n".toByteArray())
                if (idx >= 0) {
                    headerEnd = idx + 4
                    val head = String(bytes, 0, idx)
                    if (!head.startsWith("HTTP/1.1 200") && !head.startsWith("HTTP/1.0 200")) {
                        throw IOException("Printer replied: ${head.lineSequence().first()}")
                    }
                    contentLength = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: -1
                    chunked = Regex("(?i)transfer-encoding:\\s*chunked").containsMatchIn(head)
                }
            }
            if (headerEnd >= 0) {
                if (chunked) {
                    decodeChunked(bytes, headerEnd)?.let { return it }
                } else if (contentLength >= 0 && bytes.size - headerEnd >= contentLength) {
                    return bytes.copyOfRange(headerEnd, headerEnd + contentLength)
                }
            }
            if (n < 0 && acc.size() == 0) throw IOException("No reply from the printer")
        }
        throw IOException("Timed out waiting for the printer's reply")
    }

    private fun decodeChunked(bytes: ByteArray, start: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        var pos = start
        while (true) {
            val eol = indexOf(bytes, "\r\n".toByteArray(), pos)
            if (eol < 0) return null
            val size = String(bytes, pos, eol - pos).substringBefore(';').trim().toIntOrNull(16) ?: return null
            pos = eol + 2
            if (size == 0) return out.toByteArray()
            if (pos + size + 2 > bytes.size) return null
            out.write(bytes, pos, size)
            pos += size + 2
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
