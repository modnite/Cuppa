package com.cuppa.app.server

import android.content.Context
import com.cuppa.app.backend.usb.UsbPrinterBackend
import com.cuppa.app.util.CuppaLog
import com.cuppa.cups.PrinterInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which saved printers can actually be reached right now, so Cuppa only advertises to the
 * network what works. A network printer is reachable when it accepts a TCP connection on its port.
 * A USB printer is reachable when that exact device is plugged in and permission is granted.
 *
 * A printer that stops answering stays advertised for [GRACE_MS] first, so a printer waking from
 * sleep or a Wi-Fi blip does not make it vanish from other people's lists.
 */
class PrinterReachability private constructor(context: Context) {

    companion object {
        private const val TAG = "PrinterReachability"
        const val GRACE_MS = 5 * 60_000L
        private const val CONNECT_TIMEOUT_MS = 2_000

        @Volatile private var instance: PrinterReachability? = null

        fun getInstance(context: Context): PrinterReachability =
            instance ?: synchronized(this) {
                instance ?: PrinterReachability(context.applicationContext).also { instance = it }
            }
    }

    private val usb = UsbPrinterBackend(context)
    private val lastSeen = ConcurrentHashMap<String, Long>()

    private val _offline = MutableStateFlow<Set<String>>(emptySet())

    /** Names of saved printers that did not answer the latest check. Empty until a check has run. */
    val offline: StateFlow<Set<String>> = _offline.asStateFlow()

    /** Probes every printer in parallel and records the ones that answered. */
    suspend fun refresh(printers: List<PrinterInfo>) {
        val now = System.currentTimeMillis()
        val results = coroutineScope {
            printers.map { p -> async(Dispatchers.IO) { p.name to isUp(p) } }.awaitAll()
        }
        val down = mutableSetOf<String>()
        for ((name, up) in results) {
            if (up) lastSeen[name] = now else down += name
        }
        if (down != _offline.value) {
            CuppaLog.i(TAG, "Printers not answering: ${down.ifEmpty { "none" }}")
        }
        _offline.value = down
    }

    /** The printers worth advertising: reachable now, or reachable within the grace period. */
    fun advertisable(printers: List<PrinterInfo>): List<PrinterInfo> {
        val now = System.currentTimeMillis()
        return printers.filter { p -> lastSeen[p.name]?.let { now - it <= GRACE_MS } == true }
    }

    private suspend fun isUp(p: PrinterInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            if (p.uri.startsWith("usb", ignoreCase = true)) usbPresent(p.uri) else tcpAnswers(p.uri)
        } catch (e: Exception) {
            false
        }
    }

    /** Strict match on vendor and product ID. findDeviceByUri would fall back to any USB printer. */
    private fun usbPresent(uri: String): Boolean {
        val m = Regex("^usb:(?://)?(0x[0-9a-fA-F]+|\\d+)[/:](0x[0-9a-fA-F]+|\\d+)", RegexOption.IGNORE_CASE).find(uri) ?: return false
        fun num(s: String) = if (s.startsWith("0x", ignoreCase = true)) s.substring(2).toIntOrNull(16) else s.toIntOrNull()
        val vid = num(m.groupValues[1]) ?: return false
        val pid = num(m.groupValues[2]) ?: return false
        val device = usb.findDevice(vid, pid) ?: return false
        return usb.hasPermission(device)
    }

    private fun tcpAnswers(uri: String): Boolean {
        val parsed = URI(uri)
        val host = parsed.host ?: return false
        val port = when {
            parsed.port > 0 -> parsed.port
            parsed.scheme.equals("https", true) -> 443
            parsed.scheme.equals("http", true) -> 80
            else -> 631
        }
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            return true
        }
    }
}
