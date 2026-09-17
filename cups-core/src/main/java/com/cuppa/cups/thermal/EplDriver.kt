package com.cuppa.cups.thermal

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * EplDriver — Eltron Page Language (EPL2) command generator.
 *
 * Designed for legacy Eltron and Zebra thermal printers (e.g., LP 2844, TLP 2844,
 * GC420d, GK420d) operating at 203 DPI.
 *
 * Important EPL2 Bit Polarity Convention:
 * - In EPL2 Graphic Write (GW command):
 *     0 = Black dot (burn)
 *     1 = White dot (unheated)
 *   This is the inverse of ZPL and ESC/POS. EplDriver handles this by default.
 */
class EplDriver(
    val widthDots: Int = 812, // 4" at 203 DPI
    val lengthDots: Int = 1218, // 6" at 203 DPI
    val gapDots: Int = 24 // Standard gap between die-cut labels
) {
    private val buffer = ByteArrayOutputStream()

    init {
        // N = Clear image buffer
        writeString("N\n")
        // q = Set label width
        writeString("q$widthDots\n")
        // Q = Set label length with gap
        writeString("Q$lengthDots,$gapDots\n")
    }

    private fun writeString(s: String) {
        buffer.write(s.toByteArray(StandardCharsets.US_ASCII))
    }

    /**
     * Set print density (darkness).
     * @param density 0 (lightest) to 15 (darkest).
     */
    fun setDensity(density: Int): EplDriver {
        writeString("D${density.coerceIn(0, 15)}\n")
        return this
    }

    /**
     * Set print speed in inches per second (IPS).
     * @param speedIps 1 to 5.
     */
    fun setSpeed(speedIps: Int): EplDriver {
        writeString("S${speedIps.coerceIn(1, 5)}\n")
        return this
    }

    /**
     * Draw ASCII text (A command).
     *
     * Format: A{x},{y},{rotation},{font},{hMult},{vMult},{reverse},"{text}"
     * @param rotation 0=0°, 1=90°, 2=180°, 3=270°
     * @param font 1 to 5 (standard built-in raster fonts)
     */
    fun drawText(
        x: Int,
        y: Int,
        text: String,
        font: Int = 3,
        hMult: Int = 1,
        vMult: Int = 1,
        reverse: Boolean = false,
        rotation: Int = 0
    ): EplDriver {
        val revChar = if (reverse) "R" else "N"
        val escaped = text.replace("\"", "\\\"")
        writeString("A$x,$y,$rotation,${font.coerceIn(1, 5)},${hMult.coerceIn(1, 8)},${vMult.coerceIn(1, 8)},$revChar,\"$escaped\"\n")
        return this
    }

    /**
     * Draw a line or solid rectangle (LO command).
     *
     * Format: LO{x},{y},{length},{thickness}
     */
    fun drawLine(x: Int, y: Int, length: Int, thickness: Int): EplDriver {
        writeString("LO$x,$y,$length,$thickness\n")
        return this
    }

    /**
     * Draw a box border (X command).
     *
     * Format: X{x},{y},{thickness},{endX},{endY}
     */
    fun drawBox(x: Int, y: Int, width: Int, height: Int, thickness: Int = 2): EplDriver {
        val endX = x + width
        val endY = y + height
        writeString("X$x,$y,$thickness,$endX,$endY\n")
        return this
    }

    /**
     * Draw a Code 128 1D barcode (B command).
     *
     * Format: B{x},{y},{rotation},{type},{narrow},{wide},{height},{humanReadable},"{data}"
     * Type "1" corresponds to Code 128 Auto.
     */
    fun drawBarcode128(
        x: Int,
        y: Int,
        data: String,
        height: Int = 80,
        narrowBar: Int = 2,
        wideBar: Int = 4,
        showText: Boolean = true,
        rotation: Int = 0
    ): EplDriver {
        val hrChar = if (showText) "B" else "N"
        writeString("B$x,$y,$rotation,1,$narrowBar,$wideBar,$height,$hrChar,\"$data\"\n")
        return this
    }

    /**
     * Draw a 2D QR Code (b command).
     *
     * Format: b{x},{y},Q,m{magnification},s{errorCorrection},"{data}"
     */
    fun drawQrCode(
        x: Int,
        y: Int,
        data: String,
        magnification: Int = 5,
        errorCorrection: Char = 'M'
    ): EplDriver {
        val ec = when (errorCorrection.uppercaseChar()) {
            'L' -> 0
            'M' -> 1
            'Q' -> 2
            'H' -> 3
            else -> 1
        }
        writeString("b$x,$y,Q,m${magnification.coerceIn(1, 10)},s$ec,\"$data\"\n")
        return this
    }

    /**
     * Draw a monochrome graphic image (GW command).
     *
     * Format: GW{x},{y},{widthBytes},{heightDots},{binaryData}\n
     * Note: In EPL GW, 0 = black (burn dot) and 1 = white (skip).
     * Therefore, [invertPolarity] defaults to true so that standard dark pixels become 0 bits.
     */
    fun drawBitmap(
        x: Int,
        y: Int,
        bitmap: Bitmap,
        ditherMode: ThermalRasterizer.DitherMode = ThermalRasterizer.DitherMode.FLOYD_STEINBERG,
        invertPolarity: Boolean = true // EPL GW expects 0 for black/burn dot
    ): EplDriver {
        val width = bitmap.width
        val height = bitmap.height
        val rowBytes = (width + 7) / 8
        val monoBytes = ThermalRasterizer.rasterize(bitmap, ditherMode, invertPolarity)

        writeString("GW$x,$y,$rowBytes,$height\n")
        buffer.write(monoBytes)
        buffer.write(0x0A) // LF
        return this
    }

    /**
     * Issue the print command (P1).
     *
     * Format: P{copies}\n
     */
    fun build(copies: Int = 1): ByteArray {
        val result = ByteArrayOutputStream()
        result.write(buffer.toByteArray())
        val pCmd = "P${copies.coerceAtLeast(1)}\n".toByteArray(StandardCharsets.US_ASCII)
        result.write(pCmd)
        return result.toByteArray()
    }

    companion object {
        /**
         * Generate a comprehensive test label in EPL2.
         */
        fun generateTestLabel(
            printerName: String = "EPL2 Thermal Printer",
            transport: String = "USB Direct"
        ): ByteArray {
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())

            val driver = EplDriver(widthDots = 812, lengthDots = 1218)
                .setDensity(10)
                .setSpeed(4)

            // Outer border
            driver.drawBox(20, 20, 772, 1178, thickness = 4)

            // Header
            driver.drawText(60, 50, "CUPPA PRINT SERVER", font = 4, hMult = 2, vMult = 2)
            driver.drawText(60, 120, "Android Local CUPS & Thermal Subsystem", font = 2)
            driver.drawLine(40, 160, 732, 3)

            // Info rows
            driver.drawText(50, 190, "TARGET PRINTER: $printerName", font = 3)
            driver.drawText(50, 240, "PROTOCOL:       EPL2 Direct Thermal (203 DPI)", font = 3)
            driver.drawText(50, 290, "TRANSPORT:      $transport", font = 3)
            driver.drawText(50, 340, "TIMESTAMP:      $dateStr", font = 2)
            driver.drawLine(40, 390, 732, 2)

            // Code 128
            driver.drawText(50, 420, "CODE 128 TEST BARCODE:", font = 2)
            driver.drawBarcode128(90, 460, "CUPPA-EPL-2026", height = 90, narrowBar = 3, wideBar = 6)
            driver.drawLine(40, 610, 732, 2)

            // QR Code
            driver.drawText(50, 640, "2D QR CODE (EPL DIAGNOSTIC):", font = 2)
            driver.drawQrCode(70, 690, "cuppa://test/epl?printer=$printerName&time=$dateStr", magnification = 6)

            // Alignment pattern
            driver.drawBox(400, 690, 350, 200, thickness = 2)
            driver.drawText(420, 710, "EPL2 ALIGNMENT TEST", font = 2)
            driver.drawLine(420, 745, 310, 1)
            driver.drawLine(420, 775, 310, 2)
            driver.drawLine(420, 805, 310, 4)
            driver.drawText(420, 845, "203 DPI - 8 DOTS/MM", font = 2)

            // Footer
            driver.drawLine(40, 960, 732, 3)
            driver.drawText(100, 1000, "PASS - HARDWARE PRINT ENGINE VERIFIED", font = 3, hMult = 1, vMult = 2)
            driver.drawText(180, 1060, "Cuppa Native Thermal Subsystem", font = 2)

            return driver.build(1)
        }
    }
}
