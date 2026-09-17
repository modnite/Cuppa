package com.cuppa.cups.thermal

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * ZplDriver — Zebra Programming Language (ZPL II) generator.
 *
 * Tailored for direct-thermal label printers including the Rollo X1038,
 * Zebra ZD420/GK420d, and compatible 203 DPI thermal label printers.
 *
 * Default 4" × 6" shipping label resolution at 203 DPI:
 * - Width: 812 dots (4.00 inches × 203 dots/inch)
 * - Length: 1218 dots (6.00 inches × 203 dots/inch)
 */
class ZplDriver(
    val widthDots: Int = 812,
    val lengthDots: Int = 1218,
    val dpi: Int = 203
) {
    private val sb = StringBuilder()

    init {
        // Start Format
        sb.append("^XA\n")
        // Print Width & Label Length
        sb.append("^PW").append(widthDots).append("\n")
        sb.append("^LL").append(lengthDots).append("\n")
        // Label Home (0, 0)
        sb.append("^LH0,0\n")
    }

    /**
     * Set print darkness / density.
     * @param darkness Value between 0 (lightest) and 30 (darkest). Standard is 15-20.
     */
    fun setDarkness(darkness: Int): ZplDriver {
        val clamped = darkness.coerceIn(0, 30)
        sb.append("~SD").append(String.format("%02d", clamped)).append("\n")
        return this
    }

    /**
     * Set print speed in inches per second (IPS).
     * @param ips Speed from 2 to 6 IPS.
     */
    fun setPrintSpeed(ips: Int): ZplDriver {
        val clamped = ips.coerceIn(2, 6)
        sb.append("^PR").append(clamped).append(",").append(clamped).append("\n")
        return this
    }

    /**
     * Draw text using scalable smooth font.
     *
     * @param x X-coordinate in dots.
     * @param y Y-coordinate in dots.
     * @param text Text content.
     * @param fontHeight Height of the font in dots.
     * @param fontWidth Width of the font in dots.
     * @param font ZPL font identifier (default '0' = standard scalable font).
     */
    fun drawText(
        x: Int,
        y: Int,
        text: String,
        fontHeight: Int = 30,
        fontWidth: Int = fontHeight,
        font: Char = '0'
    ): ZplDriver {
        sb.append("^FO").append(x).append(",").append(y)
            .append("^A").append(font).append("N,").append(fontHeight).append(",").append(fontWidth)
            .append("^FD").append(escapeText(text)).append("^FS\n")
        return this
    }

    /**
     * Draw a graphic box / rectangle.
     */
    fun drawBox(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        thickness: Int = 2,
        cornerRadius: Int = 0
    ): ZplDriver {
        sb.append("^FO").append(x).append(",").append(y)
            .append("^GB").append(width).append(",").append(height).append(",").append(thickness)
        if (cornerRadius > 0) {
            sb.append(",B,").append(cornerRadius.coerceIn(0, 8))
        }
        sb.append("^FS\n")
        return this
    }

    /**
     * Draw a horizontal line.
     */
    fun drawHorizontalLine(x: Int, y: Int, length: Int, thickness: Int = 2): ZplDriver {
        return drawBox(x, y, length, thickness, thickness)
    }

    /**
     * Draw a vertical line.
     */
    fun drawVerticalLine(x: Int, y: Int, length: Int, thickness: Int = 2): ZplDriver {
        return drawBox(x, y, thickness, length, thickness)
    }

    /**
     * Draw a Code 128 1D barcode.
     *
     * @param x X-coordinate in dots.
     * @param y Y-coordinate in dots.
     * @param data Barcode payload string.
     * @param height Height of the barcode bars in dots.
     * @param moduleWidth Module width (narrow bar width) in dots (1-10, default 2).
     * @param showText Whether to print human-readable interpretation line below the barcode.
     */
    fun drawBarcode128(
        x: Int,
        y: Int,
        data: String,
        height: Int = 80,
        moduleWidth: Int = 2,
        showText: Boolean = true
    ): ZplDriver {
        sb.append("^FO").append(x).append(",").append(y)
            .append("^BY").append(moduleWidth).append(",3,").append(height)
            .append("^BCN,").append(height).append(",").append(if (showText) "Y" else "N")
            .append(",N,N^FD").append(data).append("^FS\n")
        return this
    }

    /**
     * Draw a 2D QR Code.
     *
     * @param x X-coordinate in dots.
     * @param y Y-coordinate in dots.
     * @param data Content encoded in the QR code.
     * @param magnification Magnification factor (1-10, default 6).
     * @param errorCorrection Error correction level: 'M' (15%), 'Q' (25%), 'H' (30%).
     */
    fun drawQrCode(
        x: Int,
        y: Int,
        data: String,
        magnification: Int = 6,
        errorCorrection: Char = 'M'
    ): ZplDriver {
        sb.append("^FO").append(x).append(",").append(y)
            .append("^BQN,2,").append(magnification.coerceIn(1, 10)).append(",").append(errorCorrection)
            .append("^FDQA,").append(data).append("^FS\n")
        return this
    }

    /**
     * Draw a monochrome bitmap using ZPL ^GFA (Graphic Field ASCII) compression.
     */
    fun drawBitmap(
        x: Int,
        y: Int,
        bitmap: Bitmap,
        ditherMode: ThermalRasterizer.DitherMode = ThermalRasterizer.DitherMode.FLOYD_STEINBERG,
        invertPolarity: Boolean = false
    ): ZplDriver {
        val width = bitmap.width
        val height = bitmap.height
        val rowBytes = (width + 7) / 8
        val totalBytes = rowBytes * height

        val hexString = ThermalRasterizer.rasterizeToHex(bitmap, ditherMode, invertPolarity)

        sb.append("^FO").append(x).append(",").append(y)
            .append("^GFA,").append(totalBytes).append(",").append(totalBytes).append(",")
            .append(rowBytes).append(",").append(hexString).append("^FS\n")
        return this
    }

    /**
     * Build the raw ZPL command stream.
     */
    fun build(): ByteArray {
        val fullCommand = sb.toString() + "^XZ\n"
        return fullCommand.toByteArray(StandardCharsets.US_ASCII)
    }

    /**
     * Build as plain ZPL string.
     */
    fun buildString(): String {
        return sb.toString() + "^XZ\n"
    }

    companion object {
        /**
         * Escape special ZPL control characters.
         */
        private fun escapeText(text: String): String {
            return text.replace("^", "").replace("~", "")
        }

        /**
         * Generate a comprehensive test label for a thermal printer.
         * Default size is 4" × 6" (812 × 1218 dots at 203 DPI).
         */
        fun generateTestLabel(
            printerName: String = "Rollo X1038",
            cupsVersion: String = "CUPS v2.2.9",
            transport: String = "USB Direct"
        ): ByteArray {
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())

            val driver = ZplDriver(widthDots = 812, lengthDots = 1218)
                .setDarkness(18)
                .setPrintSpeed(4)

            // Outer border box (4x6 boundary)
            driver.drawBox(20, 20, 772, 1178, thickness = 4)

            // Header Banner
            driver.drawBox(24, 24, 764, 110, thickness = 110)
            // Title in inverted text or clear header
            driver.drawText(60, 50, "CUPPA PRINT SERVER", fontHeight = 50, fontWidth = 45)
            driver.drawText(60, 105, "Android Local CUPS & Thermal Subsystem", fontHeight = 24, fontWidth = 22)

            // Divider
            driver.drawHorizontalLine(40, 160, 732, thickness = 3)

            // Printer & Diagnostics Section
            driver.drawText(50, 185, "TARGET PRINTER:", fontHeight = 26, fontWidth = 24)
            driver.drawText(270, 185, printerName, fontHeight = 28, fontWidth = 26)

            driver.drawText(50, 230, "DRIVER ENGINE:", fontHeight = 26, fontWidth = 24)
            driver.drawText(270, 230, "ZPL II Direct Thermal (203 DPI)", fontHeight = 26, fontWidth = 24)

            driver.drawText(50, 275, "TRANSPORT:", fontHeight = 26, fontWidth = 24)
            driver.drawText(270, 275, transport, fontHeight = 26, fontWidth = 24)

            driver.drawText(50, 320, "CUPS CORE:", fontHeight = 26, fontWidth = 24)
            driver.drawText(270, 320, cupsVersion, fontHeight = 26, fontWidth = 24)

            driver.drawText(50, 365, "TIMESTAMP:", fontHeight = 26, fontWidth = 24)
            driver.drawText(270, 365, dateStr, fontHeight = 24, fontWidth = 22)

            // Divider
            driver.drawHorizontalLine(40, 420, 732, thickness = 2)

            // 1D Barcode section
            driver.drawText(50, 445, "CODE 128 TEST BARCODE:", fontHeight = 24, fontWidth = 22)
            driver.drawBarcode128(90, 485, "CUPPA-TEST-2026", height = 90, moduleWidth = 3)

            // Divider
            driver.drawHorizontalLine(40, 630, 732, thickness = 2)

            // 2D QR Code & Label Calibration Box
            driver.drawText(50, 660, "2D QR CODE (DEVICE VERIFICATION):", fontHeight = 24, fontWidth = 22)
            driver.drawQrCode(70, 710, "cuppa://test?printer=$printerName&time=$dateStr", magnification = 7)

            // Calibration & Density Patterns on the right
            driver.drawBox(400, 710, 350, 200, thickness = 2)
            driver.drawText(420, 730, "DENSITY & ALIGNMENT", fontHeight = 22, fontWidth = 20)
            driver.drawHorizontalLine(420, 765, 310, thickness = 1)
            driver.drawHorizontalLine(420, 790, 310, thickness = 2)
            driver.drawHorizontalLine(420, 815, 310, thickness = 4)
            driver.drawHorizontalLine(420, 845, 310, thickness = 8)
            driver.drawText(420, 875, "203 DPI - 8 DOTS/MM", fontHeight = 20, fontWidth = 18)

            // Footer
            driver.drawHorizontalLine(40, 960, 732, thickness = 3)
            driver.drawText(120, 1000, "PASS - HARDWARE PRINT ENGINE VERIFIED", fontHeight = 32, fontWidth = 30)
            driver.drawText(200, 1050, "Cuppa Native Thermal Subsystem", fontHeight = 22, fontWidth = 20)

            return driver.build()
        }
    }
}
