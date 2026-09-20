package com.cuppa.app.server

import android.content.Context
import com.cuppa.app.backend.usb.UsbPrinterBackend
import com.cuppa.app.util.CuppaLog
import com.cuppa.cups.PrinterInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.cuppa.app.data.CupsRepository
import com.cuppa.app.data.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
        private const val RETRY_TIMEOUT_MS = 4_000
        private const val FOREGROUND_INTERVAL_MS = 10_000L
        private const val BACKGROUND_INTERVAL_MS = 30_000L
        private const val IDLE_WAKE_MS = 60_000L
        // Routes take a moment to settle after a VPN or Wi-Fi change. Probing sooner reads stale state.
        private const val NETWORK_SETTLE_MS = 1_500L

        @Volatile private var instance: PrinterReachability? = null

        fun getInstance(context: Context): PrinterReachability =
            instance ?: synchronized(this) {
                instance ?: PrinterReachability(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context
    private val usb = UsbPrinterBackend(context)
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var started = false
    @Volatile private var foreground = false

    private val _checks = MutableStateFlow(0L)

    /** Counts finished checks so listeners can recompute what to advertise after each one. */
    val checks: StateFlow<Long> = _checks.asStateFlow()

    /**
     * Starts the monitor. Safe to call more than once. Checks run every 10 seconds while the app is
     * on screen, and every 30 seconds while the server is on but the app is in the background. They
     * also run at once when the network changes (Wi-Fi, mobile data or a VPN coming or going), when
     * the app comes to the front and when the saved printers change. With the server off and the app
     * in the background nothing is probed.
     */
    fun start() {
        if (started) return
        started = true
        val repository = CupsRepository.getInstance(appContext)

        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = checkSoon()
                override fun onLost(network: Network) = checkSoon()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = checkSoon()
            })
        } catch (e: Exception) {
            CuppaLog.w(TAG, "Could not watch network changes: ${e.message}")
        }

        scope.launch {
            repository.printers.collect { checkNow() }
        }

        scope.launch {
            while (true) {
                val serverOn = repository.serverState.value is ServerState.Running
                val active = foreground || serverOn
                if (active) {
                    val printers = repository.printers.value
                    refresh(printers)
                    _checks.value = _checks.value + 1
                    CuppaLog.d(TAG, "Checked ${printers.size} printer(s), foreground=$foreground, serverOn=$serverOn")
                }
                val wait = when {
                    foreground -> FOREGROUND_INTERVAL_MS
                    serverOn -> BACKGROUND_INTERVAL_MS
                    else -> IDLE_WAKE_MS
                }
                withTimeoutOrNull(wait) { trigger.receive() }
            }
        }
    }

    /** Called when the app moves to or from the foreground. Coming forward checks at once. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (value) checkNow()
    }

    fun checkNow() {
        trigger.trySend(Unit)
    }

    private fun checkSoon() {
        scope.launch {
            delay(NETWORK_SETTLE_MS)
            checkNow()
        }
    }

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
            if (p.uri.startsWith("usb", ignoreCase = true)) {
                usbPresent(p.uri)
            } else {
                // A printer waking its Wi-Fi radio can miss a 2 second connect. Without a second try
                // the label flickered between Idle and Offline on alternate checks.
                tcpAnswers(p.uri, CONNECT_TIMEOUT_MS) || tcpAnswers(p.uri, RETRY_TIMEOUT_MS)
            }
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

    private fun tcpAnswers(uri: String, timeoutMs: Int): Boolean {
        val parsed = URI(uri)
        val host = parsed.host ?: return false
        val port = when {
            parsed.port > 0 -> parsed.port
            parsed.scheme.equals("https", true) -> 443
            parsed.scheme.equals("http", true) -> 80
            else -> 631
        }
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            return true
        }
    }
}
