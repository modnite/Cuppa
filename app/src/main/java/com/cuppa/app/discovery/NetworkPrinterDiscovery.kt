package com.cuppa.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.cuppa.app.util.CuppaLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * NetworkPrinterDiscovery — Discovers IPP printers on the LAN via mDNS/DNS-SD.
 *
 * Uses Android's [NsdManager] to discover services of type:
 * - `_ipp._tcp` — Standard IPP printers (unencrypted, port 631)
 * - `_ipps._tcp` — IPP over TLS printers
 *
 * Parses standard IPP mDNS TXT records to extract printer metadata:
 * `ty`, `product`, `pdl`, `rp`, `UUID`, `Color`, `Duplex`, `adminurl`
 *
 * Usage:
 * ```
 * val discovery = NetworkPrinterDiscovery(context)
 * discovery.startDiscovery()
 * // observe discovery.discoveredPrinters
 * discovery.stopDiscovery()
 * ```
 */
class NetworkPrinterDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "NetworkPrinterDiscovery"
        private const val SERVICE_TYPE_IPP = "_ipp._tcp"
        private const val SERVICE_TYPE_IPPS = "_ipps._tcp"
        private const val SCAN_WINDOW_MS = 4000L
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scanJob: Job? = null

    /** Thread-safe map of discovered printers keyed by a deduplicated ID. */
    private val printersMap = ConcurrentHashMap<String, DiscoveredPrinter>()

    private val _discoveredPrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
    val discoveredPrinters: StateFlow<List<DiscoveredPrinter>> = _discoveredPrinters.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var ippListener: NsdManager.DiscoveryListener? = null
    private var ippsListener: NsdManager.DiscoveryListener? = null

    // Track pending resolves to avoid calling resolve on the same service concurrently
    private val pendingResolves = ConcurrentHashMap<String, Boolean>()

    // mDNS service name -> the identity key it resolved to in printersMap. One physical printer
    // often advertises several names (its own plus a " [macaddress]" one, and both _ipp and
    // _ipps), and two different printers of the same model can differ ONLY by mDNS's " (2)"
    // suffix. Entries are therefore keyed by the printer's identity, never by its name.
    private val nameToKey = ConcurrentHashMap<String, String>()

    /**
     * Start discovering IPP printers on the local network.
     */
    fun startDiscovery() {
        val manager = nsdManager ?: run {
            Log.w(TAG, "NsdManager not available on this device")
            return
        }

        if (ippListener != null || ippsListener != null) {
            Log.w(TAG, "Discovery already running")
            return
        }

        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("cuppa_discovery")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock: ${e.message}")
        }

        Log.i(TAG, "Starting network printer discovery")
        printersMap.clear()
        _discoveredPrinters.value = emptyList()
        _isScanning.value = true

        // Automatically end the active scanning indicator after the discovery window
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(SCAN_WINDOW_MS)
            _isScanning.value = false
            Log.i(TAG, "Active network scan window completed")
        }

        // Discover _ipp._tcp
        ippListener = createDiscoveryListener("ipp")
        try {
            manager.discoverServices(SERVICE_TYPE_IPP, NsdManager.PROTOCOL_DNS_SD, ippListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start _ipp._tcp discovery: ${e.message}")
        }

        // Discover _ipps._tcp
        ippsListener = createDiscoveryListener("ipps")
        try {
            manager.discoverServices(SERVICE_TYPE_IPPS, NsdManager.PROTOCOL_DNS_SD, ippsListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start _ipps._tcp discovery: ${e.message}")
        }
    }

    /**
     * Stop all active network discovery.
     */
    fun stopDiscovery() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}

        nsdManager?.let { manager ->
            ippListener?.let { listener ->
                try {
                    manager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "IPP listener stop error: ${e.message}")
                }
            }
            ippsListener?.let { listener ->
                try {
                    manager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "IPPS listener stop error: ${e.message}")
                }
            }
        }
        ippListener = null
        ippsListener = null

        pendingResolves.clear()
        Log.i(TAG, "Network printer discovery stopped")
    }

    /**
     * Clear all discovered printers and restart discovery.
     */
    fun refresh() {
        stopDiscovery()
        startDiscovery()
    }

    // ---- Private ----

    private fun createDiscoveryListener(scheme: String): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                resolveService(serviceInfo, scheme)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                val key = nameToKey.remove("$scheme:${serviceInfo.serviceName}") ?: return
                // Only drop the entry once no other advertised name still resolves to it.
                if (!nameToKey.containsValue(key)) {
                    printersMap.remove(key)
                    publishPrinters()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed for $serviceType, error=$errorCode")
                _isScanning.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed for $serviceType, error=$errorCode")
            }
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo, scheme: String) {
        val manager = nsdManager ?: return
        val key = "$scheme:${serviceInfo.serviceName}"

        // Avoid concurrent resolves for the same service
        if (pendingResolves.putIfAbsent(key, true) != null) {
            return
        }

        try {
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${service.serviceName}, error=$errorCode")
                    pendingResolves.remove(key)
                }

                override fun onServiceResolved(service: NsdServiceInfo) {
                    pendingResolves.remove(key)

                    val host = service.host?.hostAddress ?: return
                    val port = service.port
                    val txtRecords = parseTxtRecords(service)

                    val rawRp = txtRecords["rp"]?.trim()?.removePrefix("/") ?: "ipp/print"
                    val cleanRp = if (rawRp.isEmpty()) "ipp/print" else rawRp
                    val formattedHost = if (host.contains(":") && !host.startsWith("[")) {
                        val cleanHost = host.substringBefore("%")
                        "[$cleanHost]"
                    } else {
                        host
                    }
                    val uri = "$scheme://$formattedHost:$port/$cleanRp"

                    val printerName = service.serviceName
                    val makeAndModel = txtRecords["ty"]
                        ?: txtRecords["product"]?.removeSurrounding("(", ")")
                        ?: "Network Printer"

                    val driver = PrinterDriverDatabase.suggestDriverByModel(makeAndModel)

                    val capabilities = buildMap {
                        putAll(txtRecords)
                        put("host", host)
                        put("port", port.toString())
                        put("scheme", scheme)
                    }

                    val printer = DiscoveredPrinter(
                        // Includes the resource path, not just host:port. Cuppa hosts every
                        // added printer's queue on the same shared IPP port, so once more than
                        // one printer is added, their self-advertised mDNS entries all resolve
                        // to the same host:port and would otherwise collapse into one id, which
                        // crashes the Printers tab's LazyColumn (duplicate key).
                        id = "net:$uri",
                        name = printerName,
                        transport = PrinterTransport.NETWORK,
                        uri = uri,
                        makeAndModel = makeAndModel,
                        suggestedDriver = driver,
                        capabilities = capabilities
                    )

                    Log.i(TAG, "Resolved: $printerName → $uri ($makeAndModel, driver=$driver)")

                    // Identity of the physical printer: its mDNS UUID when it advertises one (the
                    // same across every name and interface it uses, and different between two
                    // printers of the same model), otherwise where it lives. Prefer ipps over ipp.
                    val uuid = txtRecords.entries.firstOrNull { it.key.equals("UUID", ignoreCase = true) }
                        ?.value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                    val identity = if (uuid != null) "uuid:$uuid" else "addr:$host:$port/$cleanRp"
                    nameToKey[key] = identity
                    val existing = printersMap[identity]
                    if (existing == null || scheme == "ipps") {
                        printersMap[identity] = printer
                    }
                    publishPrinters()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception resolving service: ${e.message}")
            pendingResolves.remove(key)
        }
    }

    /**
     * Parse TXT records from an NsdServiceInfo into a map.
     *
     * Standard IPP mDNS TXT record keys:
     * - `ty`: printer type/model (e.g. "Brother HL-L2350DW")
     * - `product`: product name, usually in parens (e.g. "(Brother HL-L2350DW)")
     * - `pdl`: comma-separated list of supported page description languages
     * - `rp`: resource path (e.g. "ipp/print")
     * - `UUID`: printer UUID
     * - `Color`: "T" or "F"
     * - `Duplex`: "T" or "F"
     * - `adminurl`: admin page URL
     * - `txtvers`: TXT record version (usually "1")
     * - `qtotal`: number of queues
     */
    private fun parseTxtRecords(service: NsdServiceInfo): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val attributes = service.attributes
            for ((key, value) in attributes) {
                val strValue = value?.let { String(it, Charsets.UTF_8) } ?: ""
                result[key] = strValue
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing TXT records: ${e.message}")
        }
        return result
    }

    /**
     * Generate a deduplication key for a printer service name.
     * Strips common suffixes to merge _ipp and _ipps entries for the same printer.
     */
    private fun deduplicationKey(serviceName: String): String {
        return serviceName
            .replace(Regex("\\s*\\(\\d+\\)$"), "")  // Remove trailing "(2)" etc.
            .trim()
            .lowercase()
    }

    private fun publishPrinters() {
        _discoveredPrinters.value = printersMap.values.toList()
            .sortedBy { it.name.lowercase() }
    }
}
