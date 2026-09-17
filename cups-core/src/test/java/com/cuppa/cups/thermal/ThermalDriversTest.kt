package com.cuppa.cups.thermal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class ThermalDriversTest {

    @Test
    fun testZplDriverBasicLabel() {
        val driver = ZplDriver(widthDots = 812, lengthDots = 1218)
            .setDarkness(20)
            .setPrintSpeed(4)
            .drawText(50, 50, "HELLO CUPPA")
            .drawBox(10, 10, 800, 1200, thickness = 2)

        val zpl = driver.buildString()
        assertTrue(zpl.startsWith("^XA\n"))
        assertTrue(zpl.contains("^PW812\n"))
        assertTrue(zpl.contains("^LL1218\n"))
        assertTrue(zpl.contains("~SD20\n"))
        assertTrue(zpl.contains("^PR4,4\n"))
        assertTrue(zpl.contains("^FO50,50^A0N,30,30^FDHELLO CUPPA^FS\n"))
        assertTrue(zpl.contains("^FO10,10^GB800,1200,2^FS\n"))
        assertTrue(zpl.endsWith("^XZ\n"))
    }

    @Test
    fun testZplDriverBarcodeAndQr() {
        val driver = ZplDriver()
            .drawBarcode128(100, 100, "TEST12345", height = 80, moduleWidth = 3)
            .drawQrCode(100, 300, "https://cuppa.local", magnification = 8, errorCorrection = 'H')

        val zpl = driver.buildString()
        assertTrue(zpl.contains("^BY3,3,80"))
        assertTrue(zpl.contains("^BCN,80,Y,N,N^FDTEST12345^FS"))
        assertTrue(zpl.contains("^BQN,2,8,H^FDQA,https://cuppa.local^FS"))
    }

    @Test
    fun testZplGenerateTestLabel() {
        val bytes = ZplDriver.generateTestLabel(
            printerName = "Rollo X1038",
            cupsVersion = "CUPS 2.2.9",
            transport = "USB Direct"
        )
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val content = String(bytes, StandardCharsets.US_ASCII)
        assertTrue(content.startsWith("^XA\n"))
        assertTrue(content.contains("CUPPA PRINT SERVER"))
        assertTrue(content.contains("Rollo X1038"))
        assertTrue(content.contains("CUPPA-TEST-2026"))
        assertTrue(content.contains("PASS - HARDWARE PRINT ENGINE VERIFIED"))
        assertTrue(content.endsWith("^XZ\n"))
    }

    @Test
    fun testEscPosDriverInitializationAndFormatting() {
        val driver = EscPosDriver(characterWidth = 48)
            .setAlignment(EscPosDriver.Alignment.CENTER)
            .setBold(true)
            .printLine("TEST RECEIPT")
            .setBold(false)
            .setAlignment(EscPosDriver.Alignment.LEFT)
            .printTwoColumnLine("Item 1", "$10.00")
            .printBarcode128("12345678")
            .cutPaper(partial = true)

        val bytes = driver.build()
        assertTrue(bytes.size > 20)

        // Initializer ESC @ (0x1B, 0x40)
        assertEquals(0x1B.toByte(), bytes[0])
        assertEquals(0x40.toByte(), bytes[1])

        val str = String(bytes, StandardCharsets.US_ASCII)
        assertTrue(str.contains("TEST RECEIPT"))
        assertTrue(str.contains("Item 1"))
        assertTrue(str.contains("$10.00"))
        assertTrue(str.contains("12345678"))

        // Auto cut at end: GS V 66 0 (0x1D, 0x56, 0x42, 0x00)
        val lastBytes = bytes.takeLast(4).toByteArray()
        assertEquals(0x1D.toByte(), lastBytes[0])
        assertEquals(0x56.toByte(), lastBytes[1])
        assertEquals(66.toByte(), lastBytes[2])
        assertEquals(0.toByte(), lastBytes[3])
    }

    @Test
    fun testEscPosGenerateTestReceipt() {
        val bytes = EscPosDriver.generateTestReceipt("Epson TM-T88VI", 48)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val text = String(bytes, StandardCharsets.US_ASCII)
        assertTrue(text.contains("CUPPA PRINT SERVER"))
        assertTrue(text.contains("Epson TM-T88VI"))
        assertTrue(text.contains("PRINTER DIAGNOSTICS"))
        assertTrue(text.contains("TOTAL:"))
        assertTrue(text.contains("*** TEST PRINT SUCCESSFUL ***"))
    }

    @Test
    fun testEplDriverBasicCommands() {
        val driver = EplDriver(widthDots = 812, lengthDots = 1218)
            .setDensity(12)
            .setSpeed(3)
            .drawText(50, 50, "EPL TEST", font = 3)
            .drawLine(10, 10, 500, 2)
            .drawBox(20, 20, 200, 100, thickness = 2)

        val bytes = driver.build(1)
        val text = String(bytes, StandardCharsets.US_ASCII)

        assertTrue(text.startsWith("N\nq812\nQ1218,24\n"))
        assertTrue(text.contains("D12\n"))
        assertTrue(text.contains("S3\n"))
        assertTrue(text.contains("A50,50,0,3,1,1,N,\"EPL TEST\"\n"))
        assertTrue(text.contains("LO10,10,500,2\n"))
        assertTrue(text.contains("X20,20,2,220,120\n"))
        assertTrue(text.endsWith("P1\n"))
    }

    @Test
    fun testEplDriverBarcodeAndQr() {
        val driver = EplDriver()
            .drawBarcode128(50, 100, "EPL128TEST", height = 60)
            .drawQrCode(50, 250, "cuppa://verify", magnification = 5, errorCorrection = 'M')

        val bytes = driver.build(1)
        val text = String(bytes, StandardCharsets.US_ASCII)

        assertTrue(text.contains("B50,100,0,1,2,4,60,B,\"EPL128TEST\"\n"))
        assertTrue(text.contains("b50,250,Q,m5,s1,\"cuppa://verify\"\n"))
    }

    @Test
    fun testEplGenerateTestLabel() {
        val bytes = EplDriver.generateTestLabel("Zebra LP 2844", "USB Direct")
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val text = String(bytes, StandardCharsets.US_ASCII)
        assertTrue(text.startsWith("N\n"))
        assertTrue(text.contains("CUPPA PRINT SERVER"))
        assertTrue(text.contains("Zebra LP 2844"))
        assertTrue(text.contains("CUPPA-EPL-2026"))
        assertTrue(text.contains("PASS - HARDWARE PRINT ENGINE VERIFIED"))
        assertTrue(text.endsWith("P1\n"))
    }

    @Test
    fun testTsplDriverBasicCommands() {
        val driver = TsplDriver(widthMm = 101.6, heightMm = 152.4)
            .setDensity(8)
            .setSpeed(5)
            .setGap(3.0, 0.0)
            .setDirection(0)
            .setReference(0, 0)
            .clearBuffer()
            .drawText(50, 50, "ROLLO TEST", font = "3")
            .drawBox(20, 20, 792, 1198, thickness = 4)
            .drawBar(24, 24, 768, 110)
            .drawBarcode128(70, 485, "CUPPA-ROLLO-X1038", height = 90)
            .drawQrCode(70, 715, "cuppa://test?rollo", cellWidth = 7)
            .print(1)

        val bytes = driver.build()
        val text = String(bytes, StandardCharsets.US_ASCII)

        assertTrue(text.contains("SIZE 101 mm ,152 mm\n"))
        assertTrue(text.contains("REFERENCE 0,0\n"))
        assertTrue(text.contains("DIRECTION 0,0\n"))
        assertTrue(text.contains("GAP 3 mm,0 mm\n"))
        assertTrue(text.contains("DENSITY 8\n"))
        assertTrue(text.contains("SPEED 5\n"))
        assertTrue(text.contains("SETC AUTODOTTED OFF\n"))
        assertTrue(text.contains("SETC PAUSEKEY ON\n"))
        assertTrue(text.contains("SETC WATERMARK OFF\n"))
        assertTrue(text.contains("CLS\n"))
        assertTrue(text.contains("TEXT 50,50,\"3\",0,1,1,\"ROLLO TEST\"\n"))
        assertTrue(text.contains("BOX 20,20,792,1198,4\n"))
        assertTrue(text.contains("BAR 24,24,768,110\n"))
        assertTrue(text.contains("BARCODE 70,485,\"128\",90,1,0,2,4,\"CUPPA-ROLLO-X1038\"\n"))
        assertTrue(text.contains("QRCODE 70,715,M,7,A,0,\"cuppa://test?rollo\"\n"))
        assertTrue(text.endsWith("PRINT 1,1\n"))
    }

    @Test
    fun testTsplGenerateTestLabel() {
        val bytes = TsplDriver.generateTestLabel("Rollo X1038", "CUPS v2.2.9", "USB Direct")
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val text = String(bytes, StandardCharsets.US_ASCII)
        assertTrue(text.contains("SIZE 101 mm ,152 mm\n"))
        assertTrue(text.contains("CUPPA PRINT SERVER"))
        assertTrue(text.contains("Rollo X1038"))
        assertTrue(text.contains("TSPL / Rollo X1038 Native"))
        assertTrue(text.contains("CUPPA-ROLLO-X1038"))
        assertTrue(text.contains("PASS - ROLLO X1038 PRINT ENGINE VERIFIED"))
        assertTrue(text.endsWith("PRINT 1,1\n"))
    }

    @Test
    fun testPclDriverEscapeSequenceStructure() {
        // Bitmap-consuming methods (printBitmap/fromBitmap) aren't exercised here since
        // android.graphics.Bitmap has no real implementation under plain JVM unit tests
        // (unitTests.isReturnDefaultValues = true, no Robolectric) — same constraint the
        // other thermal driver tests in this file work around.
        val driver = PclDriver(dpi = 300)
            .setOrientation(landscape = false)
            .setCursorPosition(0, 0)
            .formFeed()
            .reset()

        val bytes = driver.build()
        val text = String(bytes, StandardCharsets.US_ASCII)

        // Reset (Esc E) is emitted on construction.
        assertEquals(0x1B.toByte(), bytes[0])
        assertEquals('E'.code.toByte(), bytes[1])

        assertTrue(text.contains("&l0O")) // portrait orientation
        assertTrue(text.contains("*p0x0Y")) // cursor position
        assertTrue(text.contains("")) // form feed
        // Ends with a second reset (Esc E) after the form feed.
        assertTrue(text.endsWith("E"))
    }

    @Test
    fun testPclDriverRasterCommandFraming() {
        // Verifies the raster-graphics command framing independent of actual pixel data by
        // calling the private bitmap path indirectly is not possible without a real Bitmap, so
        // this checks the surrounding non-bitmap command structure stays well-formed across
        // repeated builder calls (guards against accidental state leaking between instances).
        val first = PclDriver(dpi = 300).setCursorPosition(10, 20).build()
        val second = PclDriver(dpi = 300).setCursorPosition(10, 20).build()
        assertEquals(String(first, StandardCharsets.US_ASCII), String(second, StandardCharsets.US_ASCII))
    }
}

