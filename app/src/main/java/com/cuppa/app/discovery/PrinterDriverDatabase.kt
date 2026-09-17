package com.cuppa.app.discovery

/**
 * Static lookup database mapping USB Vendor/Product IDs and model strings to
 * driver suggestions.
 *
 * This enables the app to recommend the best driver when a printer is discovered,
 * without requiring the user to manually select one.
 */
object PrinterDriverDatabase {

    /**
     * Known USB printer entry with VID/PID and driver recommendation.
     */
    data class KnownPrinter(
        val vendorId: Int,
        val productId: Int? = null,  // null = matches all products from this vendor
        val name: String,
        val driver: String,
        val printerType: PrinterType = PrinterType.STANDARD,
        val defaultLabelSize: String? = null  // e.g. "4x6" for shipping labels
    )

    enum class PrinterType {
        STANDARD,      // Inkjet, laser, etc.
        THERMAL_ZPL,   // Zebra ZPL thermal
        THERMAL_ESCPOS, // ESC/POS receipt/label
        THERMAL_EPL,   // Eltron EPL thermal
        THERMAL_STAR,  // Star Line Mode
        THERMAL_GENERIC
    }

    // ---- VID/PID Database ----

    /**
     * Known printers by specific VID/PID pair.
     * Checked first (most specific match).
     */
    private val knownByVidPid: List<KnownPrinter> = listOf(
        // ---- Rollo ----
        KnownPrinter(
            vendorId = 0x0483, productId = 0x5720,
            name = "Rollo X1038",
            driver = "ZPL",
            printerType = PrinterType.THERMAL_ZPL,
            defaultLabelSize = "4x6"
        ),

        // ---- Zebra (common models) ----
        KnownPrinter(
            vendorId = 0x0A5F, productId = 0x0081,
            name = "Zebra ZD420",
            driver = "ZPL",
            printerType = PrinterType.THERMAL_ZPL,
            defaultLabelSize = "4x6"
        ),
        KnownPrinter(
            vendorId = 0x0A5F, productId = 0x008B,
            name = "Zebra GK420d",
            driver = "ZPL",
            printerType = PrinterType.THERMAL_ZPL,
            defaultLabelSize = "4x6"
        ),

        // ---- DYMO ----
        KnownPrinter(
            vendorId = 0x0922, productId = 0x0020,
            name = "DYMO LabelWriter 450",
            driver = "DYMO LabelWriter",
            printerType = PrinterType.THERMAL_GENERIC,
            defaultLabelSize = "1.125x3.5"
        ),

        // ---- Brother (thermal) ----
        KnownPrinter(
            vendorId = 0x04F9, productId = 0x2042,
            name = "Brother QL-800",
            driver = "Brother QL Raster",
            printerType = PrinterType.THERMAL_GENERIC,
            defaultLabelSize = "2.4"
        ),
    )

    /**
     * Known printer vendors by VID only (fallback when no specific PID match).
     */
    private val knownByVendor: List<KnownPrinter> = listOf(
        // Thermal / receipt vendors
        KnownPrinter(vendorId = 0x0483, name = "Rollo Printer", driver = "ZPL", printerType = PrinterType.THERMAL_ZPL),
        KnownPrinter(vendorId = 0x0A5F, name = "Zebra Printer", driver = "ZPL", printerType = PrinterType.THERMAL_ZPL),
        KnownPrinter(vendorId = 0x0922, name = "DYMO Printer", driver = "DYMO LabelWriter", printerType = PrinterType.THERMAL_GENERIC),
        KnownPrinter(vendorId = 0x0519, name = "Star Micronics Printer", driver = "Star Line Mode", printerType = PrinterType.THERMAL_STAR),
        KnownPrinter(vendorId = 0x04B8, name = "Epson POS Printer", driver = "ESC/POS", printerType = PrinterType.THERMAL_ESCPOS),
        KnownPrinter(vendorId = 0x0DD4, name = "Custom (Italy) Printer", driver = "ESC/POS", printerType = PrinterType.THERMAL_ESCPOS),
        KnownPrinter(vendorId = 0x0416, name = "Winbond Printer", driver = "ESC/POS", printerType = PrinterType.THERMAL_ESCPOS),
        KnownPrinter(vendorId = 0x0FE6, name = "ICS Advent Printer", driver = "ESC/POS", printerType = PrinterType.THERMAL_ESCPOS),
        KnownPrinter(vendorId = 0x1504, name = "SNBC Printer", driver = "ESC/POS", printerType = PrinterType.THERMAL_ESCPOS),
        KnownPrinter(vendorId = 0x0493, name = "Citizens Printer", driver = "ESC/POS", printerType = PrinterType.THERMAL_ESCPOS),

        // Standard printer vendors
        KnownPrinter(vendorId = 0x03F0, name = "HP Printer", driver = "Generic PCL 6"),
        KnownPrinter(vendorId = 0x04A9, name = "Canon Printer", driver = "Generic IPP Everywhere"),
        KnownPrinter(vendorId = 0x04B8, name = "Epson Printer", driver = "Generic IPP Everywhere"),  // lower priority than POS
        KnownPrinter(vendorId = 0x04F9, name = "Brother Printer", driver = "Generic IPP Everywhere"),
        KnownPrinter(vendorId = 0x413C, name = "Dell Printer", driver = "Generic PCL 6"),
        KnownPrinter(vendorId = 0x04E8, name = "Samsung Printer", driver = "Generic PostScript"),
        KnownPrinter(vendorId = 0x0482, name = "Kyocera Printer", driver = "Generic PostScript"),
        KnownPrinter(vendorId = 0x04DA, name = "Panasonic Printer", driver = "Generic PCL 6"),
        KnownPrinter(vendorId = 0x06BC, name = "Oki Printer", driver = "Generic PostScript"),
        KnownPrinter(vendorId = 0x0924, name = "Xerox Printer", driver = "Generic PostScript"),
        KnownPrinter(vendorId = 0x043D, name = "Lexmark Printer", driver = "Generic PCL 6"),
        KnownPrinter(vendorId = 0x0550, name = "Fuji Xerox Printer", driver = "Generic PostScript"),
        KnownPrinter(vendorId = 0x04F2, name = "Ricoh Printer", driver = "Generic PostScript"),
        KnownPrinter(vendorId = 0x0409, name = "NEC Printer", driver = "Generic PCL 6"),
        KnownPrinter(vendorId = 0x04C5, name = "Fujitsu Printer", driver = "Generic PostScript"),
        KnownPrinter(vendorId = 0x1A86, name = "QinHeng USB-Serial Printer", driver = "ESC/POS", printerType = PrinterType.THERMAL_ESCPOS),
    )

    // ---- Model String Patterns ----

    private data class ModelPattern(
        val pattern: Regex,
        val driver: String,
        val printerType: PrinterType = PrinterType.STANDARD
    )

    private val modelPatterns: List<ModelPattern> = listOf(
        // Thermal patterns
        ModelPattern(Regex("(?i)rollo"), "ZPL", PrinterType.THERMAL_ZPL),
        ModelPattern(Regex("(?i)zebra.*zd|zebra.*gk|zebra.*gx|zebra.*zp|zebra.*zt"), "ZPL", PrinterType.THERMAL_ZPL),
        ModelPattern(Regex("(?i)zebra"), "ZPL", PrinterType.THERMAL_ZPL),
        ModelPattern(Regex("(?i)dymo"), "DYMO LabelWriter", PrinterType.THERMAL_GENERIC),
        ModelPattern(Regex("(?i)star.*(tsp|sm|mc)"), "Star Line Mode", PrinterType.THERMAL_STAR),
        ModelPattern(Regex("(?i)epson.*tm-|epson.*receipt"), "ESC/POS", PrinterType.THERMAL_ESCPOS),
        ModelPattern(Regex("(?i)brother.*ql"), "Brother QL Raster", PrinterType.THERMAL_GENERIC),
        ModelPattern(Regex("(?i)bixolon"), "ESC/POS", PrinterType.THERMAL_ESCPOS),
        ModelPattern(Regex("(?i)citizen.*(ct|cl|cx)"), "ESC/POS", PrinterType.THERMAL_ESCPOS),

        // Standard patterns (inkjet / laser)
        ModelPattern(Regex("(?i)hp|hewlett"), "Generic PCL 6"),
        ModelPattern(Regex("(?i)canon"), "Generic IPP Everywhere"),
        ModelPattern(Regex("(?i)epson"), "Generic IPP Everywhere"),
        ModelPattern(Regex("(?i)brother"), "Generic IPP Everywhere"),
        ModelPattern(Regex("(?i)samsung"), "Generic PostScript"),
        ModelPattern(Regex("(?i)xerox"), "Generic PostScript"),
        ModelPattern(Regex("(?i)kyocera"), "Generic PostScript"),
        ModelPattern(Regex("(?i)ricoh"), "Generic PostScript"),
        ModelPattern(Regex("(?i)lexmark"), "Generic PCL 6"),
        ModelPattern(Regex("(?i)dell"), "Generic PCL 6"),
        ModelPattern(Regex("(?i)oki"), "Generic PostScript"),
    )

    /**
     * Suggest a driver based on USB Vendor ID and Product ID.
     *
     * Checks specific VID/PID pairs first, then falls back to vendor-only matching.
     *
     * @return Pair of (driverName, knownPrinterName) or defaults
     */
    fun suggestDriver(vendorId: Int, productId: Int): Pair<String, String> {
        // 1. Exact VID+PID match
        knownByVidPid.find { it.vendorId == vendorId && it.productId == productId }?.let {
            return Pair(it.driver, it.name)
        }

        // 2. Vendor-only match
        knownByVendor.find { it.vendorId == vendorId }?.let {
            return Pair(it.driver, it.name)
        }

        // 3. Default
        return Pair("Generic IPP", "USB Printer (${String.format("0x%04X", vendorId)}:${String.format("0x%04X", productId)})")
    }

    /**
     * Suggest a driver based on a model/make string (e.g. from mDNS TXT record `ty` field).
     *
     * @return Driver name suggestion
     */
    fun suggestDriverByModel(modelString: String): String {
        modelPatterns.find { it.pattern.containsMatchIn(modelString) }?.let {
            return it.driver
        }
        return "Generic IPP Everywhere"
    }

    /**
     * Look up detailed info for a specific VID/PID.
     */
    fun lookupPrinter(vendorId: Int, productId: Int): KnownPrinter? {
        return knownByVidPid.find { it.vendorId == vendorId && it.productId == productId }
            ?: knownByVendor.find { it.vendorId == vendorId }
    }

    /**
     * Get the default label size for a known thermal printer.
     */
    fun getDefaultLabelSize(vendorId: Int, productId: Int): String? {
        return lookupPrinter(vendorId, productId)?.defaultLabelSize
    }

    /**
     * Check if a VID/PID is a known thermal printer.
     */
    fun isThermalPrinter(vendorId: Int, productId: Int): Boolean {
        val printer = lookupPrinter(vendorId, productId)
        return printer?.printerType != null && printer.printerType != PrinterType.STANDARD
    }
}
