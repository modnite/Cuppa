package com.cuppa.app.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.cuppa.app.util.CuppaLog as Log
import com.cuppa.app.data.CupsRepository
import com.cuppa.cups.PrinterInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * NetworkPrinterAdvertiser — Advertises Cuppa's managed printers via mDNS/DNS-SD.
 *
 * Registers `_ipp._tcp` (with the `_universal` AirPrint subtype) services on the local network
 * so that modern operating systems (macOS, Windows, iOS, Linux) can discover the Cuppa print
 * server and configure it automatically as an IPP Everywhere / AirPrint-compatible printer.
 *
 * Re-registers automatically whenever [CupsRepository.printers] changes, since printers are
 * usually added *after* the server/service is already running — without this, a printer added
 * post-startup would be reachable by IP but invisible to driverless discovery.
 */
class NetworkPrinterAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "NetworkPrinterAdvertiser"

        // Registering with a comma-separated subtype is Android NsdManager's documented way to
        // additionally advertise `_universal._sub._ipp._tcp`, the subtype Apple's AirPrint
        // discovery specifically looks for on top of the plain `_ipp._tcp` type.
        private const val SERVICE_TYPE_WITH_AIRPRINT = "_ipp._tcp,_universal"
        private const val SERVICE_TYPE_PLAIN = "_ipp._tcp"
        private const val DEFAULT_SERVICE_NAME = "Cuppa Print Server"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val activeListeners = CopyOnWriteArrayList<NsdManager.RegistrationListener>()
    private val advertiseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null
    private var lastPort: Int = 631
    private var lastTlsEnabled: Boolean = false
    private var lastAirprintCompat: Boolean = true

    var isRegistered: Boolean = false
        private set

    /**
     * Start broadcasting the IPP server and its configured printers on the local network, and
     * keep watching [CupsRepository.printers] so newly added/removed printers are re-advertised
     * without requiring a service restart.
     * @param port The port the IPP Server is listening on (e.g. 631 or fallback 8631).
     * @param tlsEnabled Whether the IPP listener also offers IPPS on this same port, advertised
     *   via the "TLS" TXT record so IPP Everywhere / AirPrint clients that check for it know
     *   they can request encryption (see IppServer's opportunistic-TLS handling).
     * @param airprintCompat Whether to additionally advertise the `_universal` subtype Apple's
     *   AirPrint discovery looks for. Off means plain `_ipp._tcp` only — still fully usable by
     *   any standard IPP Everywhere client, just not Apple's own discovery shortcut.
     */
    fun startAdvertising(port: Int = 631, tlsEnabled: Boolean = false, airprintCompat: Boolean = true) {
        val manager = nsdManager ?: run {
            Log.w(TAG, "NsdManager is not available on this device")
            return
        }

        lastPort = port
        lastTlsEnabled = tlsEnabled
        lastAirprintCompat = airprintCompat
        acquireMulticastLock()

        val repository = CupsRepository.getInstance(context)
        registerAll(manager, repository.printers.value, port, tlsEnabled, airprintCompat)

        // React to printers being added/removed after the server has already started.
        watchJob?.cancel()
        watchJob = advertiseScope.launch {
            repository.printers.drop(1).collect { printers ->
                Log.i(TAG, "Printer list changed (${printers.size} printer(s)) — refreshing mDNS advertisements")
                unregisterAll(manager)
                registerAll(manager, printers, lastPort, lastTlsEnabled, lastAirprintCompat)
            }
        }
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("cuppa_advertiser")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock: ${e.message}")
        }
    }

    private fun registerAll(manager: NsdManager, configuredPrinters: List<PrinterInfo>, port: Int, tlsEnabled: Boolean = false, airprintCompat: Boolean = true) {
        if (configuredPrinters.isEmpty()) {
            // Advertise a default placeholder queue so the server is still discoverable before
            // any printer has been added.
            registerSinglePrinter(manager, DEFAULT_SERVICE_NAME, "ipp/print", "Cuppa IPP Server", port, false, DEFAULT_SERVICE_NAME, tlsEnabled = tlsEnabled, airprintCompat = airprintCompat)
        } else {
            for (printer in configuredPrinters) {
                val serviceName = "${printer.name} (Cuppa)"
                val cleanName = printer.name.replace(" ", "_")
                val rp = "printers/$cleanName"
                registerSinglePrinter(
                    manager,
                    serviceName,
                    rp,
                    printer.makeAndModel.ifEmpty { "Generic IPP Printer" },
                    port,
                    printer.colorSupported,
                    printer.name,
                    printer.supportedFormats,
                    tlsEnabled,
                    airprintCompat
                )
            }
        }
    }

    private fun registerSinglePrinter(
        manager: NsdManager,
        serviceName: String,
        resourcePath: String,
        model: String,
        port: Int,
        colorSupported: Boolean,
        uuidSeed: String,
        supportedFormats: List<String> = emptyList(),
        tlsEnabled: Boolean = false,
        airprintCompat: Boolean = true
    ) {
        // Only claim formats the print pipeline can actually turn into printer output. Do NOT
        // advertise image/pwg-raster or image/urf here — there is no PWG-Raster/URF decoder, and
        // claiming them makes AirPrint/IPP-Everywhere clients (which prefer raster formats for
        // driverless queues) send a format the pipeline can't decode, so the job silently produces
        // no output. See the matching note on PrinterInfo::supportedFormats in cups_server.h.
        val pdl = supportedFormats.ifEmpty { listOf("application/pdf", "application/octet-stream") }
            .joinToString(",")

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = if (airprintCompat) SERVICE_TYPE_WITH_AIRPRINT else SERVICE_TYPE_PLAIN
            this.port = port

            // Standard IPP Everywhere / AirPrint TXT records
            setAttribute("rp", resourcePath)
            setAttribute("pdl", pdl)
            setAttribute("ty", model)
            setAttribute("Color", if (colorSupported) "T" else "F")
            setAttribute("Duplex", "F")
            setAttribute("txtvers", "1")
            setAttribute("qtotal", "1")
            setAttribute("note", "Local Android CUPS Print Server")
            // Signals opportunistic TLS availability on this same port, per the same "TLS" TXT
            // key convention real IPP Everywhere printers use (PWG 5100.16 section 5.4).
            if (tlsEnabled) setAttribute("TLS", "1.2")
            // Must match the printer-uuid IPP attribute the native engine returns for the same
            // printer name (see makeDeterministicUuid in cups_server.cpp) so strict AirPrint/IPP
            // Everywhere validators that cross-check the two see a consistent identity.
            setAttribute("UUID", makeDeterministicUuid(uuidSeed))
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS service registered: ${registeredInfo.serviceName} on port $port")
                isRegistered = true
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS registration failed for ${serviceInfo.serviceName}: error code $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.i(TAG, "mDNS service unregistered: ${arg0.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS unregistration failed for ${serviceInfo.serviceName}: error code $errorCode")
            }
        }

        activeListeners.add(listener)

        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            Log.i(TAG, "Requested DNS-SD registration for $serviceName (rp=$resourcePath, pdl=$pdl)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service for $serviceName", e)
            activeListeners.remove(listener)
        }
    }

    private fun unregisterAll(manager: NsdManager) {
        for (listener in activeListeners) {
            try {
                manager.unregisterService(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering mDNS listener", e)
            }
        }
        activeListeners.clear()
        isRegistered = false
    }

    /**
     * Stop broadcasting all advertised printers.
     */
    fun stopAdvertising() {
        watchJob?.cancel()
        watchJob = null

        val manager = nsdManager
        if (manager != null) {
            unregisterAll(manager)
        }

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}

        Log.i(TAG, "All mDNS printer advertisements stopped")
    }

    /**
     * Deterministically derives an RFC-4122-shaped UUID string from [seed] (typically the printer
     * name), using the exact same FNV-1a-based algorithm as makeDeterministicUuid in
     * cups_server.cpp, so the mDNS TXT "UUID" record and the IPP "printer-uuid" attribute for the
     * same printer always agree.
     */
    private fun makeDeterministicUuid(seed: String): String {
        fun fnv1a(s: String, offset: Long): Long {
            var hash = offset
            for (b in s.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (b.toLong() and 0xFF)
                hash *= 1099511628211L
            }
            return hash
        }

        var hi = fnv1a("cuppa-printer-uuid:$seed", -3750763034362895579L) // FNV offset basis, signed bit pattern
        var lo = fnv1a("$seed:cuppa-printer-uuid", 1469598103934665603L)

        hi = (hi and 0xF000L.inv()) or 0x4000L // version 4
        lo = (lo and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE // RFC 4122 variant

        val hiHigh = hi ushr 32
        val hiMidA = (hi ushr 16) and 0xFFFFL
        val hiMidB = hi and 0xFFFFL
        val loHigh = (lo ushr 48) and 0xFFFFL
        val loLow = lo and 0xFFFFFFFFFFFFL

        return "%08x-%04x-%04x-%04x-%012x".format(hiHigh, hiMidA, hiMidB, loHigh, loLow)
    }
}
