package com.cuppa.app.discovery

/**
 * Transport type for a discovered printer.
 */
enum class PrinterTransport {
    /** Connected via USB cable. */
    USB,
    /** Discovered on the network via mDNS/DNS-SD. */
    NETWORK,
    /** Manually entered by the user (IP address). */
    MANUAL
}

/**
 * Unified data model for a discovered printer, regardless of transport.
 *
 * This represents a printer that has been *found* but not necessarily *added*
 * to the managed printer list in [CupsRepository].
 *
 * @param id Unique identifier: "usb:VID:PID:serial" or "net:hostname:port" or "manual:uri"
 * @param name Display name (from mDNS service name, USB descriptor, or user entry)
 * @param transport How the printer was discovered
 * @param uri Connection URI (e.g. "ipp://192.168.1.50:631/ipp/print" or "usb://0x0483/0x5720")
 * @param makeAndModel Human-readable make and model string
 * @param suggestedDriver Recommended driver name based on VID/PID or model string
 * @param isAlreadyAdded Whether this printer is already in the managed list
 * @param capabilities Raw attributes: TXT records for network, descriptor info for USB
 */
data class DiscoveredPrinter(
    val id: String,
    val name: String,
    val transport: PrinterTransport,
    val uri: String,
    val makeAndModel: String = "Unknown Printer",
    val suggestedDriver: String = "Generic IPP",
    val isAlreadyAdded: Boolean = false,
    val capabilities: Map<String, String> = emptyMap()
) {
    /** Icon-friendly transport label. */
    val transportLabel: String
        get() = when (transport) {
            PrinterTransport.USB -> "USB"
            PrinterTransport.NETWORK -> "Network"
            PrinterTransport.MANUAL -> "Manual"
        }

    /** Whether this is a thermal printer based on the suggested driver. */
    val isThermal: Boolean
        get() = suggestedDriver.let {
            it.contains("ZPL", ignoreCase = true) ||
            it.contains("ESC/POS", ignoreCase = true) ||
            it.contains("EPL", ignoreCase = true) ||
            it.contains("Thermal", ignoreCase = true)
        }
}
