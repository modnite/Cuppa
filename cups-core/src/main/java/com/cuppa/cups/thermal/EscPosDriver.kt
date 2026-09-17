package com.cuppa.cups.thermal

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * EscPosDriver — Epson ESC/POS receipt protocol generator.
 *
 * Implements standard ESC/POS command sequences for 80mm and 58mm thermal receipt printers.
 * Supports text styling, character alignment, Code 128 barcodes, QR codes,
 * bit-image raster graphics (GS v 0), cash drawer pulse, and auto-cut.
 */
class EscPosDriver(
    val characterWidth: Int = 48 // 48 chars for 80mm, 32 for 58mm
) {
    private val buffer = ByteArrayOutputStream()

    init {
        // Initialize printer (ESC @)
        buffer.write(byteArrayOf(0x1B, 0x40))
    }

    enum class Alignment(val value: Byte) {
        LEFT(0),
        CENTER(1),
        RIGHT(2)
    }

    /**
     * Set text horizontal alignment.
     */
    fun setAlignment(align: Alignment): EscPosDriver {
        buffer.write(byteArrayOf(0x1B, 0x61, align.value))
        return this
    }

    /**
     * Enable or disable bold emphasis.
     */
    fun setBold(enabled: Boolean): EscPosDriver {
        buffer.write(byteArrayOf(0x1B, 0x45, if (enabled) 1 else 0))
        return this
    }

    /**
     * Enable or disable underline.
     */
    fun setUnderline(enabled: Boolean): EscPosDriver {
        buffer.write(byteArrayOf(0x1B, 0x2D, if (enabled) 1 else 0))
        return this
    }

    /**
     * Set character size scaling.
     * @param widthScale 1 to 8 (1 = normal)
     * @param heightScale 1 to 8 (1 = normal)
     */
    fun setTextSize(widthScale: Int = 1, heightScale: Int = 1): EscPosDriver {
        val w = (widthScale - 1).coerceIn(0, 7)
        val h = (heightScale - 1).coerceIn(0, 7)
        val n = ((w shl 4) or h).toByte()
        buffer.write(byteArrayOf(0x1D, 0x21, n))
        return this
    }

    /**
     * Write raw text.
     */
    fun print(text: String): EscPosDriver {
        buffer.write(text.toByteArray(StandardCharsets.US_ASCII))
        return this
    }

    /**
     * Write line of text followed by line feed.
     */
    fun printLine(text: String = ""): EscPosDriver {
        print(text)
        buffer.write(0x0A) // LF
        return this
    }

    /**
     * Print a two-column line (e.g. item name on left, price on right).
     */
    fun printTwoColumnLine(left: String, right: String): EscPosDriver {
        val spaces = characterWidth - left.length - right.length
        val line = if (spaces > 0) {
            left + " ".repeat(spaces) + right
        } else {
            "$left $right"
        }
        return printLine(line)
    }

    /**
     * Print a divider line of repeating characters.
     */
    fun printDivider(char: Char = '-'): EscPosDriver {
        return printLine(char.toString().repeat(characterWidth))
    }

    /**
     * Feed [lines] empty lines.
     */
    fun feed(lines: Int = 1): EscPosDriver {
        buffer.write(byteArrayOf(0x1B, 0x64, lines.coerceIn(1, 255).toByte()))
        return this
    }

    /**
     * Print a Code 128 barcode.
     * Command: GS k 73 len {data}
     */
    fun printBarcode128(data: String, height: Int = 64, widthMultiplier: Int = 2): EscPosDriver {
        // Set barcode height (GS h n)
        buffer.write(byteArrayOf(0x1D, 0x68, height.coerceIn(1, 255).toByte()))
        // Set barcode width (GS w n)
        buffer.write(byteArrayOf(0x1D, 0x77, widthMultiplier.coerceIn(2, 6).toByte()))
        // Set HRI characters position below barcode (GS H 2)
        buffer.write(byteArrayOf(0x1D, 0x48, 2))

        val dataBytes = data.toByteArray(StandardCharsets.US_ASCII)
        // GS k 73 (Code 128)
        buffer.write(byteArrayOf(0x1D, 0x6B, 73, dataBytes.size.toByte()))
        buffer.write(dataBytes)
        return this
    }

    /**
     * Print a 2D QR Code.
     * Uses standard ESC/POS GS ( k command sequence.
     */
    fun printQrCode(data: String, moduleSize: Int = 6): EscPosDriver {
        val dataBytes = data.toByteArray(StandardCharsets.US_ASCII)
        val pL = ((dataBytes.size + 3) and 0xFF).toByte()
        val pH = (((dataBytes.size + 3) shr 8) and 0xFF).toByte()

        // 1. Model: GS ( k 4 0 49 65 50 0 (Model 2)
        buffer.write(byteArrayOf(0x1D, 0x28, 0x6B, 4, 0, 49, 65, 50, 0))

        // 2. Module size: GS ( k 3 0 49 67 n
        buffer.write(byteArrayOf(0x1D, 0x28, 0x6B, 3, 0, 49, 67, moduleSize.coerceIn(1, 16).toByte()))

        // 3. Error correction level M (15%): GS ( k 3 0 49 69 49
        buffer.write(byteArrayOf(0x1D, 0x28, 0x6B, 3, 0, 49, 69, 49))

        // 4. Store data: GS ( k pL pH 49 80 48 {data}
        buffer.write(byteArrayOf(0x1D, 0x28, 0x6B, pL, pH, 49, 80, 48))
        buffer.write(dataBytes)

        // 5. Print QR Code: GS ( k 3 0 49 81 48
        buffer.write(byteArrayOf(0x1D, 0x28, 0x6B, 3, 0, 49, 81, 48))
        return this
    }

    /**
     * Print a 1-bit raster bit image using GS v 0.
     *
     * @param bitmap Source bitmap to rasterize.
     * @param ditherMode Dithering algorithm.
     * @param invertPolarity Inverts bit polarity (1=black, 0=white by default).
     */
    fun printImage(
        bitmap: Bitmap,
        ditherMode: ThermalRasterizer.DitherMode = ThermalRasterizer.DitherMode.FLOYD_STEINBERG,
        invertPolarity: Boolean = false
    ): EscPosDriver {
        val width = bitmap.width
        val height = bitmap.height
        val rowBytes = (width + 7) / 8
        val monoBytes = ThermalRasterizer.rasterize(bitmap, ditherMode, invertPolarity)

        val xL = (rowBytes and 0xFF).toByte()
        val xH = ((rowBytes shr 8) and 0xFF).toByte()
        val yL = (height and 0xFF).toByte()
        val yH = ((height shr 8) and 0xFF).toByte()

        // GS v 0 0 xL xH yL yH {data}
        buffer.write(byteArrayOf(0x1D, 0x76, 0x30, 0, xL, xH, yL, yH))
        buffer.write(monoBytes)
        return this
    }

    /**
     * Pulse cash drawer pin 2 (ESC p 0 25 250).
     */
    fun openCashDrawer(): EscPosDriver {
        buffer.write(byteArrayOf(0x1B, 0x70, 0, 25, 250.toByte()))
        return this
    }

    /**
     * Feed paper and perform full or partial cut.
     * @param partial If true, partial cut (leaves a hinge); if false, full cut.
     */
    fun cutPaper(partial: Boolean = true): EscPosDriver {
        feed(3)
        // GS V m: 65 = full cut, 66 = partial cut
        val m = if (partial) 66.toByte() else 65.toByte()
        buffer.write(byteArrayOf(0x1D, 0x56, m, 0))
        return this
    }

    /**
     * Build the raw ESC/POS byte sequence.
     */
    fun build(): ByteArray {
        return buffer.toByteArray()
    }

    companion object {
        /**
         * Generate a standard diagnostic test receipt.
         */
        fun generateTestReceipt(
            printerName: String = "ESC/POS Thermal Receipt",
            characterWidth: Int = 48
        ): ByteArray {
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())

            val driver = EscPosDriver(characterWidth = characterWidth)
                .setAlignment(Alignment.CENTER)
                .setTextSize(2, 2)
                .setBold(true)
                .printLine("CUPPA PRINT SERVER")
                .setTextSize(1, 1)
                .setBold(false)
                .printLine("Android Local CUPS Subsystem")
                .printDivider('=')
                .setAlignment(Alignment.LEFT)
                .setBold(true)
                .printLine("PRINTER DIAGNOSTICS")
                .setBold(false)
                .printTwoColumnLine("Printer:", printerName)
                .printTwoColumnLine("Driver:", "ESC/POS Direct Receipt")
                .printTwoColumnLine("Timestamp:", dateStr)
                .printTwoColumnLine("Width:", "${characterWidth} chars (80mm)")
                .printDivider('-')
                .setBold(true)
                .printLine("DIAGNOSTIC TEST ITEMS")
                .setBold(false)
                .printTwoColumnLine("1x Thermal Test Print", "$0.00")
                .printTwoColumnLine("1x ZPL/ESC Emulation", "$0.00")
                .printTwoColumnLine("1x CUPS Core Spooler", "$0.00")
                .printDivider('-')
                .setBold(true)
                .printTwoColumnLine("TOTAL:", "$0.00")
                .setBold(false)
                .printDivider('=')
                .setAlignment(Alignment.CENTER)
                .printLine("CODE 128 TEST BARCODE:")
                .printBarcode128("CUPPA-POS-TEST")
                .feed(1)
                .printLine("QR VERIFICATION CODE:")
                .printQrCode("cuppa://test/receipt?printer=$printerName&time=$dateStr")
                .feed(1)
                .printLine("*** TEST PRINT SUCCESSFUL ***")
                .printLine("Hardware Printhead Operational")
                .cutPaper(partial = true)

            return driver.build()
        }
    }
}
