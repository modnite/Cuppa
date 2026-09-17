package com.cuppa.app.discovery

import com.cuppa.app.data.CupsRepository
import com.cuppa.cups.PrinterInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterDiscoveryTest {

    @Test
    fun testRolloVidPidDetection() {
        val (driver, name) = PrinterDriverDatabase.suggestDriver(0x0483, 0x5720)
        assertEquals("ZPL", driver)
        assertEquals("Rollo X1038", name)
        assertTrue(PrinterDriverDatabase.isThermalPrinter(0x0483, 0x5720))
        assertEquals("4x6", PrinterDriverDatabase.getDefaultLabelSize(0x0483, 0x5720))
    }

    @Test
    fun testKnownVendorDrivers() {
        // Zebra
        val (zebraDriver, zebraName) = PrinterDriverDatabase.suggestDriver(0x0A5F, 0x0081)
        assertEquals("ZPL", zebraDriver)
        assertEquals("Zebra ZD420", zebraName)

        // Epson POS
        val (epsonDriver, epsonName) = PrinterDriverDatabase.suggestDriver(0x04B8, 0x0202)
        assertEquals("ESC/POS", epsonDriver)
        assertEquals("Epson POS Printer", epsonName)

        // Star Micronics
        val (starDriver, starName) = PrinterDriverDatabase.suggestDriver(0x0519, 0x0001)
        assertEquals("Star Line Mode", starDriver)
        assertEquals("Star Micronics Printer", starName)

        // Unknown VID/PID
        val (unknownDriver, unknownName) = PrinterDriverDatabase.suggestDriver(0x9999, 0x8888)
        assertEquals("Generic IPP", unknownDriver)
        assertTrue(unknownName.contains("0x9999"))
        assertNull(PrinterDriverDatabase.lookupPrinter(0x9999, 0x8888))
    }

    @Test
    fun testModelStringDriverLookup() {
        val rolloDriver = PrinterDriverDatabase.suggestDriverByModel("Rollo X1038 USB Printer")
        assertEquals("ZPL", rolloDriver)

        val zebraDriver = PrinterDriverDatabase.suggestDriverByModel("Zebra ZD420-203dpi")
        assertEquals("ZPL", zebraDriver)

        val hpDriver = PrinterDriverDatabase.suggestDriverByModel("HP LaserJet Pro M404dn")
        assertEquals("Generic PCL 6", hpDriver)

        val brotherDriver = PrinterDriverDatabase.suggestDriverByModel("Brother HL-L2350DW")
        assertEquals("Generic IPP Everywhere", brotherDriver)

        val starDriver = PrinterDriverDatabase.suggestDriverByModel("Star TSP100 Receipt")
        assertEquals("Star Line Mode", starDriver)

        val epsonDriver = PrinterDriverDatabase.suggestDriverByModel("Epson TM-T88V")
        assertEquals("ESC/POS", epsonDriver)
    }

    @Test
    fun testDiscoveredPrinterModel() {
        val printer = DiscoveredPrinter(
            id = "net:192.168.1.50:631",
            name = "Office Printer",
            transport = PrinterTransport.NETWORK,
            uri = "ipp://192.168.1.50:631/ipp/print",
            makeAndModel = "Brother HL-L2350DW",
            suggestedDriver = "Generic IPP Everywhere",
            isAlreadyAdded = false,
            capabilities = mapOf("ty" to "Brother HL-L2350DW", "pdl" to "application/pdf")
        )

        assertEquals("net:192.168.1.50:631", printer.id)
        assertEquals(PrinterTransport.NETWORK, printer.transport)
        assertEquals("ipp://192.168.1.50:631/ipp/print", printer.uri)
        assertEquals("Brother HL-L2350DW", printer.capabilities["ty"])
    }

    @Test
    fun testCupsRepositoryAddAndRemovePrinter() {
        val repo = CupsRepository(null)
        val testPrinter1 = PrinterInfo(
            name = "Printer 1",
            uri = "ipp://192.168.1.100:631/ipp/print",
            makeAndModel = "Rollo X1038",
            state = 3
        )
        val testPrinter2 = PrinterInfo(
            name = "Printer 2",
            uri = "ipp://192.168.1.101:631/ipp/print",
            makeAndModel = "Epson TM-T88V",
            state = 3
        )

        assertEquals(0, repo.printers.value.size)

        repo.addPrinter(testPrinter1)
        assertEquals(1, repo.printers.value.size)
        assertEquals("Printer 1", repo.printers.value[0].name)

        repo.addPrinter(testPrinter2)
        assertEquals(2, repo.printers.value.size)

        // Adding duplicate URI updates rather than duplicates
        val updatedPrinter1 = PrinterInfo(
            name = "Printer 1 (Renamed)",
            uri = "ipp://192.168.1.100:631/ipp/print",
            makeAndModel = "Rollo X1038",
            state = 4
        )
        repo.addPrinter(updatedPrinter1)
        assertEquals(2, repo.printers.value.size)
        assertEquals("Printer 1 (Renamed)", repo.printers.value.first { it.uri == testPrinter1.uri }.name)

        // Remove printer
        repo.removePrinter(testPrinter1.uri)
        assertEquals(1, repo.printers.value.size)
        assertFalse(repo.printers.value.any { it.uri == testPrinter1.uri })
    }
}
