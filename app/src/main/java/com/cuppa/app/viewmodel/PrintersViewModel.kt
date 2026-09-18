package com.cuppa.app.viewmodel

import android.app.Application
import com.cuppa.app.util.CuppaLog as Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cuppa.app.data.CupsRepository
import com.cuppa.app.discovery.DiscoveredPrinter
import com.cuppa.app.discovery.NetworkPrinterDiscovery
import com.cuppa.app.discovery.PrinterTransport
import com.cuppa.app.discovery.UsbPrinterDiscovery
import com.cuppa.cups.PrinterInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * UI state for the Printers screen.
 */
data class PrintersUiState(
    /** Printers that have been added and are managed by the app. */
    val addedPrinters: List<PrinterInfo> = emptyList(),
    /** USB printers discovered but not yet added. */
    val discoveredUsbPrinters: List<DiscoveredPrinter> = emptyList(),
    /** Network printers discovered but not yet added. */
    val discoveredNetworkPrinters: List<DiscoveredPrinter> = emptyList(),
    /** Whether an active scan window is in progress. */
    val isScanning: Boolean = false,
    /** Whether a pull-to-refresh action is currently animating. */
    val isRefreshing: Boolean = false,
    /** Whether a printer is currently being tested/added. */
    val isAdding: Boolean = false,
    /** Result message from the last add/test operation. */
    val operationResult: String? = null,
    /** Whether the Add Printer sheet is open. */
    val showAddSheet: Boolean = false,
    /** Active IPP server port. */
    val activePort: Int = 631,
)

/**
 * ViewModel for the Printers screen.
 *
 * Manages USB and network printer discovery, and provides actions
 * for adding, removing, and testing printers.
 */
class PrintersViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PrintersViewModel"
    }

    private val repository = CupsRepository.getInstance(application)
    private val usbDiscovery = UsbPrinterDiscovery(application)
    private val networkDiscovery = NetworkPrinterDiscovery(application)

    private val _isRefreshing = MutableStateFlow(false)
    private val _isAdding = MutableStateFlow(false)
    private val _operationResult = MutableStateFlow<String?>(null)
    private val _showAddSheet = MutableStateFlow(false)

    val uiState: StateFlow<PrintersUiState> = combine(
        combine(repository.printers, usbDiscovery.discoveredPrinters, networkDiscovery.discoveredPrinters) { added, usb, net ->
            Triple(added, usb, net)
        },
        combine(networkDiscovery.isScanning, _isRefreshing, repository.serverState) { scanning, refreshing, serverState ->
            val port = when (serverState) {
                is com.cuppa.app.data.ServerState.Running -> serverState.port
                else -> application.getSharedPreferences("cuppa_settings", android.content.Context.MODE_PRIVATE).getInt("server_port", 631)
            }
            Triple(scanning, refreshing, port)
        },
        _isAdding,
        _operationResult,
        _showAddSheet
    ) { (addedPrinters, usbPrinters, netPrinters), (isNetScanning, isRefreshing, port), isAdding, result, showSheet ->
        // "Available" should only list things that could still be added. Three kinds of
        // discovered network entries are NOT that, and used to show up as confusing duplicates:
        //  1. Cuppa itself. Once a printer is added, Cuppa re-advertises it over mDNS as
        //     "<name> (Cuppa)" so other devices can find it, and Cuppa's own scan sees that too
        //     (mDNS may append "(2)" on a name conflict). Anything resolving to one of this
        //     device's own addresses is Cuppa, never a printer to add.
        //  2. The original network printer behind one already added (recognized by its address
        //     or mDNS UUID, never by name).
        //  3. Already-added URIs.
        val addedUris = addedPrinters.map { it.uri }.toSet()
        val addedHosts = addedPrinters.mapNotNull { added ->
            if (added.uri.startsWith("ipp", ignoreCase = true) || added.uri.startsWith("http", ignoreCase = true)) {
                Regex("^[a-zA-Z]+://\\[?([^\\]/:?]+)").find(added.uri)?.groupValues?.getOrNull(1)?.lowercase()
            } else null
        }.toSet()
        val localAddresses = com.cuppa.app.util.NetworkUtils.getAllLocalAddresses()

        fun hostOf(p: DiscoveredPrinter) = p.capabilities["host"]?.substringBefore('%')?.lowercase()
        fun uuidOf(p: DiscoveredPrinter) = p.capabilities.entries
            .firstOrNull { it.key.equals("UUID", ignoreCase = true) }?.value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        // One physical printer can be reachable at several addresses (Wi-Fi and Ethernet) under
        // several names. The mDNS UUID is what ties those together, and what tells two printers of
        // the same model apart, so an "already added" printer is recognized by the UUID of any
        // discovered entry that sits at one of the added printers' addresses. Names are never
        // compared: two identical-model printers differ only by an " (2)" suffix.
        val addedUuids = netPrinters.filter { hostOf(it) in addedHosts }.mapNotNull { uuidOf(it) }.toSet()

        val filteredUsb = usbPrinters.map { printer ->
            printer.copy(isAlreadyAdded = addedUris.contains(printer.uri))
        }
        val filteredNet = netPrinters.map { printer ->
            val host = hostOf(printer)
            val isSelf = host != null && host in localAddresses
            val sameHost = host != null && host in addedHosts
            val sameDevice = uuidOf(printer)?.let { it in addedUuids } == true
            printer.copy(isAlreadyAdded = addedUris.contains(printer.uri) || isSelf || sameHost || sameDevice)
        }

        PrintersUiState(
            addedPrinters = addedPrinters,
            discoveredUsbPrinters = filteredUsb,
            discoveredNetworkPrinters = filteredNet,
            isScanning = isNetScanning,
            isRefreshing = isRefreshing,
            isAdding = isAdding,
            operationResult = result,
            showAddSheet = showSheet,
            activePort = port,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PrintersUiState()
    )

    init {
        // Start discovery automatically
        startDiscovery()
        // Load persisted printers
        repository.loadPrinters(application)
    }

    /**
     * Start both USB and network discovery.
     */
    fun startDiscovery() {
        usbDiscovery.start()
        networkDiscovery.startDiscovery()
        Log.i(TAG, "Started printer discovery")
    }

    /**
     * Stop both USB and network discovery.
     */
    fun stopDiscovery() {
        usbDiscovery.stop()
        networkDiscovery.stopDiscovery()
        Log.i(TAG, "Stopped printer discovery")
    }

    /**
     * Refresh: re-scan USB and restart network discovery.
     */
    fun refreshDiscovery() {
        viewModelScope.launch {
            _isRefreshing.value = true
            usbDiscovery.rescan()
            networkDiscovery.refresh()
            delay(1200L)
            _isRefreshing.value = false
            Log.i(TAG, "Refreshed printer discovery")
        }
    }

    /**
     * Add a discovered printer to the managed list.
     * Tests the connection first for network printers.
     */
    /**
     * Add a discovered printer to the managed list.
     * Discovered printers (USB and Network) are added immediately using their discovery metadata.
     * Network attributes are asynchronously enriched in the background.
     * Manual entries are connection-tested first with a strict timeout.
     */
    /**
     * Printers are keyed by name everywhere (the repository, the native queue registry, the
     * share URI), so two different printers can't share one. Two printers of the same model (say,
     * two Brother MFC-L2717DW in one office) advertise the same name, and adding the second would
     * silently replace the first. When a different printer already holds the name, tell them
     * apart by address.
     */
    private fun uniquePrinterName(base: String, uri: String): String {
        val clash = repository.printers.value.any { it.name.equals(base, ignoreCase = true) && it.uri != uri }
        if (!clash) return base
        val host = Regex("^[a-zA-Z]+://\\[?([^\\]/:?]+)").find(uri)?.groupValues?.getOrNull(1)
        return if (host != null) "$base ($host)" else "$base (2)"
    }

    fun addPrinter(printer: DiscoveredPrinter, forceAdd: Boolean = false) {
        viewModelScope.launch {
            _isAdding.value = true
            _operationResult.value = null

            try {
                val printerName = uniquePrinterName(printer.name, printer.uri)
                when (printer.transport) {
                    PrinterTransport.USB -> {
                        val info = PrinterInfo(
                            name = printerName,
                            uri = printer.uri,
                            makeAndModel = printer.makeAndModel,
                            state = 3, // idle
                            // Only formats the USB pipeline can actually produce: PDF is rasterized
                            // locally and converted per-driver (ESC/POS/ZPL/TSPL/EPL), and
                            // octet-stream covers an already-native payload sent as-is. Do not
                            // advertise image/pwg-raster or image/urf — there is no raster decoder,
                            // so claiming them would make driverless clients send an undecodable
                            // format and the print would silently produce nothing.
                            supportedFormats = listOf("application/pdf", "application/octet-stream")
                        )
                        repository.addPrinter(info)
                        repository.savePrinters(getApplication())
                        _operationResult.value = "✓ Added: ${printer.name}"
                        Log.i(TAG, "Added USB printer: ${printer.name}")
                    }

                    PrinterTransport.NETWORK -> {
                        // Discovered network printer — we already have mDNS metadata.
                        // Add immediately so the user is never blocked or rejected.
                        val formats = printer.capabilities["pdl"]
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?: listOf("application/pdf", "image/pwg-raster", "image/urf")

                        val initialInfo = PrinterInfo(
                            name = printerName,
                            uri = printer.uri,
                            makeAndModel = printer.makeAndModel,
                            info = printer.capabilities["note"] ?: printer.capabilities["product"] ?: "",
                            isAcceptingJobs = true,
                            state = 3, // idle
                            supportedFormats = formats,
                            colorSupported = printer.capabilities["Color"]?.equals("T", ignoreCase = true) == true
                        )
                        repository.addPrinter(initialInfo)
                        repository.savePrinters(getApplication())
                        _operationResult.value = "✓ Added: ${initialInfo.name}"
                        Log.i(TAG, "Added discovered network printer: ${initialInfo.name} (${initialInfo.uri})")

                        // Asynchronously enrich printer attributes in the background without blocking the UI
                        launch {
                            try {
                                val result = repository.testPrinterConnection(printer.uri)
                                result.onSuccess { enriched ->
                                    // Keep the name and address chosen when the printer was added.
                                    // The printer reports its own name (identical on two units of the
                                    // same model), and re-adding under that would overwrite the other.
                                    repository.addPrinter(enriched.copy(name = initialInfo.name, uri = initialInfo.uri))
                                    repository.savePrinters(getApplication())
                                    Log.i(TAG, "Enriched attributes for: ${enriched.name}")
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "Background attribute enrichment completed with note: ${e.message}")
                            }
                        }
                    }

                    PrinterTransport.MANUAL -> {
                        if (forceAdd) {
                            val info = PrinterInfo(
                                name = printerName,
                                uri = printer.uri,
                                makeAndModel = printer.makeAndModel.ifEmpty { "Manual IPP Printer" },
                                state = 3
                            )
                            repository.addPrinter(info)
                            repository.savePrinters(getApplication())
                            _operationResult.value = "✓ Added: ${info.name}"
                            Log.i(TAG, "Force added manual printer: ${info.name}")
                        } else {
                            // Test the connection with a strict timeout
                            val result = withTimeoutOrNull(4000L) {
                                repository.testPrinterConnection(printer.uri)
                            }
                            if (result != null && result.isSuccess) {
                                val info = result.getOrThrow()
                                repository.addPrinter(info)
                                repository.savePrinters(getApplication())
                                _operationResult.value = "✓ Added: ${info.name} (${info.makeAndModel})"
                                Log.i(TAG, "Verified and added manual printer: ${info.name}")
                            } else {
                                val err = result?.exceptionOrNull()?.message ?: "Connection timed out"
                                _operationResult.value = "✗ Connection failed: $err. You can tap 'Add Anyway' to add without verification."
                                Log.w(TAG, "Failed to connect to manual printer: $err")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _operationResult.value = "✗ Error: ${e.message}"
                Log.e(TAG, "Error adding printer", e)
            } finally {
                _isAdding.value = false
            }
        }
    }

    /**
     * Add a printer by manual URI entry.
     */
    fun addManualPrinter(uri: String, forceAdd: Boolean = false) {
        if (uri.isBlank()) return

        val trimmed = uri.trim()
        val formattedUri = if (!trimmed.contains("://")) {
            "ipp://$trimmed"
        } else {
            trimmed
        }

        val discoveredPrinter = DiscoveredPrinter(
            id = "manual:$formattedUri",
            name = formattedUri,
            transport = PrinterTransport.MANUAL,
            uri = formattedUri,
            makeAndModel = "Manual IPP Printer",
            suggestedDriver = "Generic IPP Everywhere"
        )
        addPrinter(discoveredPrinter, forceAdd)
    }

    /**
     * Remove a printer from the managed list.
     */
    fun removePrinter(uri: String) {
        repository.removePrinter(uri)
        repository.savePrinters(getApplication())
        _operationResult.value = "Printer removed"
        Log.i(TAG, "Removed printer: $uri")
    }

    /**
     * Request USB permission for a discovered USB printer.
     */
    fun requestUsbPermission(printerId: String) {
        usbDiscovery.requestPermissionForPrinter(printerId)
    }

    /**
     * Show or hide the Add Printer bottom sheet.
     */
    fun setShowAddSheet(show: Boolean) {
        _showAddSheet.value = show
    }

    /**
     * Clear the operation result message.
     */
    fun clearOperationResult() {
        _operationResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }
}
