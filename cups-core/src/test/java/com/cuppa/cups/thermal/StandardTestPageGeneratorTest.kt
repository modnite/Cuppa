package com.cuppa.cups.thermal

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class StandardTestPageGeneratorTest {

    @Test
    fun testGeneratePostScriptTestPageLetter() {
        val bytes = StandardTestPageGenerator.generatePostScriptTestPage(
            paperSize = StandardTestPageGenerator.PaperSize.LETTER,
            printerName = "HP LaserJet Enterprise",
            transport = "IPP Everywhere"
        )
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val ps = String(bytes, StandardCharsets.US_ASCII)
        assertTrue(ps.startsWith("%!PS-Adobe-3.0"))
        assertTrue(ps.contains("Cuppa CUPS Test Page"))
        assertTrue(ps.contains("HP LaserJet Enterprise"))
        assertTrue(ps.contains("BoundingBox: 0 0 612 792"))
        assertTrue(ps.contains("showpage"))
        assertTrue(ps.endsWith("%%EOF\n"))
    }

    @Test
    fun testGeneratePostScriptTestPageA4() {
        val bytes = StandardTestPageGenerator.generatePostScriptTestPage(
            paperSize = StandardTestPageGenerator.PaperSize.A4,
            printerName = "Canon imageRUNNER",
            transport = "IPP Everywhere"
        )
        assertNotNull(bytes)
        val ps = String(bytes, StandardCharsets.US_ASCII)
        assertTrue(ps.contains("BoundingBox: 0 0 595 842"))
        assertTrue(ps.contains("Canon imageRUNNER"))
    }

    @Test
    fun testGeneratePclTestPage() {
        val bytes = StandardTestPageGenerator.generatePclTestPage(
            printerName = "Brother MFC-L8900CDW",
            transport = "Direct Socket 9100"
        )
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val pcl = String(bytes, StandardCharsets.US_ASCII)
        assertTrue(pcl.contains("@PJL ENTER LANGUAGE=PCL"))
        assertTrue(pcl.contains("CUPPA CUPS PRINT SERVER"))
        assertTrue(pcl.contains("HP PCL 5/6 HARDWARE TEST"))
        assertTrue(pcl.contains("Brother MFC-L8900CDW"))
        assertTrue(pcl.contains("\u000C")) // Form feed
    }

    @Test
    fun testGenerateRawTextTestPage() {
        val bytes = StandardTestPageGenerator.generateRawTextTestPage(
            printerName = "Generic Dot Matrix",
            transport = "USB Direct",
            paperSize = StandardTestPageGenerator.PaperSize.LEGAL
        )
        assertNotNull(bytes)
        val text = String(bytes, StandardCharsets.UTF_8)
        assertTrue(text.contains("CUPPA CUPS PRINT SERVER"))
        assertTrue(text.contains("Legal (8.5\" × 14\")"))
        assertTrue(text.contains("Generic Dot Matrix"))
        assertTrue(text.contains("\u000C")) // Form feed
    }
}
