package com.cuppa.cups.thermal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * TsplDriver — TSC Programming Language (TSPL / TSPL2) generator.
 *
 * Specifically reverse-engineered from and calibrated against the official
 * Rollo X1038 Linux CUPS driver (`rastertorollo.c` / `rollo-x1038.ppd`) by Nelu LLC
 * and Michael R Sweet.
 *
 * Hardware Specifications:
 * - Rollo X1038 4" x 6" Direct Thermal Printer (USB VID 0x09C5 / PID 0x0588, IEEE 1284 CMD:XPP,XL)
 * - Resolution: 203 DPI (8 dots per mm)
 * - Standard 4" x 6" label:
 *     - Width: 812 dots (~101.6 mm / 102 mm) -> 102 bytes per raster row
 *     - Height: 1218 dots (~152.4 mm / 152 mm)
 * - TSPL BITMAP Mode:
 *     - Mode 1: Overwrite (0 = Black / Burn dot, 1 = White / Clear unheated)
 */
class TsplDriver(
    val widthMm: Double = 101.6,
    val heightMm: Double = 152.4,
    val dpi: Int = 203
) {
    private val commands = StringBuilder()
    private val binaryStream = ByteArrayOutputStream()

    init {
        // Initialize label dimensions in mm matching rastertorollo
        val w = widthMm.toInt()
        val h = heightMm.toInt()
        commands.append("SIZE $w mm ,$h mm\n")
        commands.append("REFERENCE 0,0\n")
        commands.append("DIRECTION 0,0\n")
        commands.append("GAP 3 mm,0 mm\n")
        commands.append("OFFSET 0 mm\n")
    }

    fun appendRawCommand(cmd: String): TsplDriver {
        commands.append(cmd)
        return this
    }

    /**
     * Set print darkness / density.
     * @param density 0 (lightest) to 15 (darkest). Official default is 8.
     */
    fun setDensity(density: Int): TsplDriver {
        val clamped = density.coerceIn(0, 15)
        commands.append("DENSITY $clamped\n")
        return this
    }

    /**
     * Set print speed in inches per second (IPS).
     * @param speed 2 to 6 IPS. Official default is 5 or 6.
     */
    fun setSpeed(speed: Int): TsplDriver {
        val clamped = speed.coerceIn(2, 6)
        commands.append("SPEED $clamped\n")
        return this
    }

    /**
     * Set media gap size.
     */
    fun setGap(gapMm: Double = 3.0, offsetMm: Double = 0.0): TsplDriver {
        commands.append("GAP ${gapMm.toInt()} mm,${offsetMm.toInt()} mm\n")
        return this
    }

    /**
     * Set print direction.
     * @param direction 0 = normal, 1 = 180 degree rotation.
     */
    fun setDirection(direction: Int): TsplDriver {
        commands.append("DIRECTION $direction,0\n")
        return this
    }

    /**
     * Set coordinate reference point.
     */
    fun setReference(x: Int, y: Int): TsplDriver {
        commands.append("REFERENCE $x,$y\n")
        return this
    }

    /**
     * Clear the image buffer.
     */
    fun clearBuffer(): TsplDriver {
        // Apply Rollo firmware specific control codes before CLS
        commands.append("SETC AUTODOTTED OFF\n")
        commands.append("SETC PAUSEKEY ON\n")
        commands.append("SETC WATERMARK OFF\n")
        commands.append("CLS\n")
        return this
    }

    /**
     * Draw text using built-in alphanumeric bitmap or scalable font.
     *
     * @param x X-coordinate in dots.
     * @param y Y-coordinate in dots.
     * @param font Built-in font name: "1" (8x12), "2" (12x20), "3" (16x24), "4" (24x32), "5" (32x48), "TSS24.BF2"
     * @param rotation 0, 90, 180, 270.
     * @param xMulti Horizontal multiplier (1..10).
     * @param yMulti Vertical multiplier (1..10).
     * @param text String content.
     */
    fun drawText(
        x: Int,
        y: Int,
        text: String,
        font: String = "3",
        rotation: Int = 0,
        xMulti: Int = 1,
        yMulti: Int = 1
    ): TsplDriver {
        val escaped = text.replace("\"", "")
        commands.append("TEXT $x,$y,\"$font\",$rotation,$xMulti,$yMulti,\"$escaped\"\n")
        return this
    }

    /**
     * Draw a solid rectangle / bar.
     */
    fun drawBar(x: Int, y: Int, width: Int, height: Int): TsplDriver {
        commands.append("BAR $x,$y,$width,$height\n")
        return this
    }

    /**
     * Draw a rectangle outline (box).
     */
    fun drawBox(x: Int, y: Int, endX: Int, endY: Int, thickness: Int = 2): TsplDriver {
        commands.append("BOX $x,$y,$endX,$endY,$thickness\n")
        return this
    }

    /**
     * Draw horizontal rule.
     */
    fun drawHorizontalLine(x: Int, y: Int, length: Int, thickness: Int = 2): TsplDriver {
        return drawBar(x, y, length, thickness)
    }

    /**
     * Draw 1D Code 128 barcode.
     */
    fun drawBarcode128(
        x: Int,
        y: Int,
        text: String,
        height: Int = 80,
        humanReadable: Int = 1,
        rotation: Int = 0,
        narrow: Int = 2,
        wide: Int = 4
    ): TsplDriver {
        val escaped = text.replace("\"", "")
        commands.append("BARCODE $x,$y,\"128\",$height,$humanReadable,$rotation,$narrow,$wide,\"$escaped\"\n")
        return this
    }

    /**
     * Draw 2D QR Code.
     */
    fun drawQrCode(
        x: Int,
        y: Int,
        text: String,
        eccLevel: String = "M",
        cellWidth: Int = 6,
        mode: String = "A",
        rotation: Int = 0
    ): TsplDriver {
        val escaped = text.replace("\"", "")
        commands.append("QRCODE $x,$y,$eccLevel,$cellWidth,$mode,$rotation,\"$escaped\"\n")
        return this
    }

    /**
     * Append raw 1-bit bitmap graphics data in TSPL BITMAP format.
     */
    fun drawBitmap(
        x: Int,
        y: Int,
        widthBytes: Int,
        heightDots: Int,
        mode: Int = 1,
        bitmapData: ByteArray
    ): TsplDriver {
        // Flush pending text commands to binaryStream
        val textBytes = commands.toString().toByteArray(StandardCharsets.US_ASCII)
        binaryStream.write(textBytes)
        commands.setLength(0)

        val header = "BITMAP $x,$y,$widthBytes,$heightDots,$mode,".toByteArray(StandardCharsets.US_ASCII)
        binaryStream.write(header)
        binaryStream.write(bitmapData)
        binaryStream.write("\n".toByteArray(StandardCharsets.US_ASCII))
        return this
    }

    /**
     * Append the PRINT command to execute printing and feed label.
     */
    fun print(copies: Int = 1): TsplDriver {
        commands.append("PRINT $copies,1\n")
        return this
    }

    /**
     * Build the raw byte stream ready to send to the printer over USB bulk out or network socket.
     */
    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(RESET_PREAMBLE)
        if (binaryStream.size() > 0) {
            out.write(binaryStream.toByteArray())
        }
        if (commands.isNotEmpty()) {
            out.write(commands.toString().toByteArray(StandardCharsets.US_ASCII))
        }
        return out.toByteArray()
    }

    /**
     * Build string representation (useful for unit tests of non-binary payloads).
     */
    fun buildString(): String {
        return commands.toString()
    }

    companion object {
        const val DEFAULT_DPI = 203
        const val ROLLO_WIDTH_MM = 101.6
        const val ROLLO_HEIGHT_MM = 152.4
        const val ROLLO_ROW_BYTES = 102 // 816 dots / 8 = 102 bytes

        // `~@` (reset) followed by CRLF. Confirmed against a real Rollo X1038 (VID 0x09C5 / PID
        // 0x0588): without this preamble the printer accepts the whole TSPL command stream over
        // USB bulk OUT with no transfer error, but never actually fires the print head — it's
        // sitting in a state that only this reset clears. Not documented in the public TSPL2
        // spec; recovered from an earlier from-scratch reverse-engineering pass against this
        // exact hardware.
        private val RESET_PREAMBLE = byteArrayOf(0x7E, 0x40, 0x0D, 0x0A)

        /** The X1038's print head is 832 dots wide, but a 4" label at 203 DPI is only 812. */
        const val ROLLO_HEAD_DOTS = 832

        /**
         * Places an already label-width (812 dot) bitmap in the middle of the 832-dot print head.
         * Rendering a full 832 wide prints ~2.5% oversized and runs off the right edge of the
         * label (confirmed on real hardware); rendering 812 and sending it as-is leaves the right
         * edge exactly on the label edge, where any drift clips it. Centering gives both sides
         * the same small margin.
         */
        fun centerOnPrintHead(bitmap: Bitmap): Bitmap {
            if (bitmap.width >= ROLLO_HEAD_DOTS) return bitmap
            val out = Bitmap.createBitmap(ROLLO_HEAD_DOTS, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(bitmap, ((ROLLO_HEAD_DOTS - bitmap.width) / 2).toFloat(), 0f, null)
            return out
        }

        /**
         * Convert an Android [Bitmap] into a complete TSPL job matching Nelu LLC / Rollo CUPS driver.
         */
        fun fromBitmap(
            bitmap: Bitmap,
            density: Int = 8,
            speed: Int = 5,
            gapMm: Double = 3.0,
            ditherMode: ThermalRasterizer.DitherMode = ThermalRasterizer.DitherMode.FLOYD_STEINBERG,
            invertPolarity: Boolean = true
        ): ByteArray {
            val width = bitmap.width
            val height = bitmap.height
            val rowBytes = (width + 7) / 8
            val widthMm = rowBytes // 8 dots = 1 mm at 203 DPI
            val heightMm = (height + 7) / 8

            // Rollo BITMAP mode 1 expects: 0 = Black (burn), 1 = White (skip)
            // ThermalRasterizer with invertPolarity = true outputs 0 for dark pixels, 1 for light pixels
            val rasterData = ThermalRasterizer.rasterize(
                bitmap = bitmap,
                ditherMode = ditherMode,
                invertPolarity = invertPolarity
            )

            val out = ByteArrayOutputStream()
            out.write(RESET_PREAMBLE)
            val header = buildString {
                append("SIZE $widthMm mm ,$heightMm mm\n")
                append("REFERENCE 0,0\n")
                append("DIRECTION 0,0\n")
                append("GAP ${gapMm.toInt()} mm,0 mm\n")
                append("OFFSET 0 mm\n")
                append("DENSITY ${density.coerceIn(0, 15)}\n")
                append("SPEED ${speed.coerceIn(2, 6)}\n")
                append("SETC AUTODOTTED OFF\n")
                append("SETC PAUSEKEY ON\n")
                append("SETC WATERMARK OFF\n")
                append("CLS\n")
                append("BITMAP 0,0,$rowBytes,$height,1,")
            }

            out.write(header.toByteArray(StandardCharsets.US_ASCII))
            out.write(rasterData)
            out.write("\nPRINT 1,1\n".toByteArray(StandardCharsets.US_ASCII))

            return out.toByteArray()
        }

        /**
         * Generate a comprehensive test label for Rollo X1038 and compatible TSPL printers.
         * Default size is 4" x 6" (102mm x 152mm at 203 DPI).
         */
        fun generateTestLabel(
            printerName: String = "Rollo X1038",
            cupsVersion: String = "CUPS v2.2.9",
            transport: String = "USB Direct"
        ): ByteArray {
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())

            val driver = TsplDriver(widthMm = ROLLO_WIDTH_MM, heightMm = ROLLO_HEIGHT_MM)
                .setDensity(8)
                .setSpeed(5)
                .setGap(3.0, 0.0)
                .setDirection(0)
                .setReference(0, 0)
                .clearBuffer()

            // Outer border box (4x6 boundary)
            driver.drawBox(20, 20, 792, 1198, thickness = 4)

            // Header Banner
            driver.drawBar(24, 24, 768, 110)
            // Header text
            driver.drawText(50, 45, "CUPPA PRINT SERVER", font = "4", xMulti = 1, yMulti = 1)
            driver.drawText(50, 95, "Android Local CUPS & Thermal Subsystem", font = "2", xMulti = 1, yMulti = 1)

            // Divider
            driver.drawHorizontalLine(40, 160, 732, thickness = 3)

            // Printer & Diagnostics Section
            driver.drawText(50, 185, "TARGET PRINTER:  $printerName", font = "3")
            driver.drawText(50, 230, "DRIVER ENGINE:   TSPL / Rollo X1038 Native", font = "3")
            driver.drawText(50, 275, "TRANSPORT:       $transport", font = "3")
            driver.drawText(50, 320, "CUPS CORE:       $cupsVersion", font = "3")
            driver.drawText(50, 365, "TIMESTAMP:       $dateStr", font = "3")

            // Divider
            driver.drawHorizontalLine(40, 420, 732, thickness = 2)

            // 1D Barcode section
            driver.drawText(50, 445, "CODE 128 TEST BARCODE:", font = "2")
            driver.drawBarcode128(70, 485, "CUPPA-ROLLO-X1038", height = 90, humanReadable = 1, narrow = 3, wide = 6)

            // Divider
            driver.drawHorizontalLine(40, 640, 732, thickness = 2)

            // 2D QR Code & Label Calibration Box
            driver.drawText(50, 665, "2D QR CODE (DEVICE VERIFICATION):", font = "2")
            driver.drawQrCode(70, 715, "cuppa://test?printer=$printerName&proto=tspl&time=$dateStr", cellWidth = 7)

            // Calibration & Density Patterns on the right
            driver.drawBox(400, 715, 750, 920, thickness = 2)
            driver.drawText(420, 735, "DENSITY & ALIGNMENT", font = "2")
            driver.drawHorizontalLine(420, 770, 310, thickness = 1)
            driver.drawHorizontalLine(420, 795, 310, thickness = 2)
            driver.drawHorizontalLine(420, 820, 310, thickness = 4)
            driver.drawHorizontalLine(420, 850, 310, thickness = 8)
            driver.drawText(420, 885, "203 DPI - 8 DOTS/MM", font = "2")

            // Footer
            driver.drawHorizontalLine(40, 970, 732, thickness = 3)
            driver.drawText(100, 1010, "PASS - ROLLO X1038 PRINT ENGINE VERIFIED", font = "3")
            driver.drawText(180, 1060, "Cuppa Native Thermal Subsystem (TSPL)", font = "2")

            // Print 1 label
            driver.print(1)

            return driver.build()
        }
    }
}
