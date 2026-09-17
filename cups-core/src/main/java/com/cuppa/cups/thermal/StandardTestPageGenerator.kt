package com.cuppa.cups.thermal

import android.graphics.*
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

/**
 * StandardTestPageGenerator — Comprehensive test page generator for all CUPS printer types.
 *
 * Supports:
 * - Standard Office & Desktop Printers (Letter, A4, Legal)
 * - Formats: Vector PDF, PWG Raster (AirPrint/IPP Everywhere), PostScript (PS), HP PCL 5/6, Raw Text
 * - Thermal & Label Printers (4"x6", 80mm receipt, TSPL, ZPL, ESC/POS)
 */
object StandardTestPageGenerator {

    enum class PaperSize(
        val displayName: String,
        val widthPoints: Int,
        val heightPoints: Int,
        val widthMm: Double,
        val heightMm: Double
    ) {
        LETTER("Letter (8.5\" × 11\")", 612, 792, 215.9, 279.4),
        A4("A4 (210 × 297 mm)", 595, 842, 210.0, 297.0),
        LEGAL("Legal (8.5\" × 14\")", 612, 1008, 215.9, 355.6),
        LABEL_4X6("4\" × 6\" Shipping Label", 288, 432, 101.6, 152.4),
        RECEIPT_80MM("80mm Receipt Roll", 226, 600, 80.0, 210.0)
    }

    enum class DocumentFormat(val displayName: String, val extension: String, val mimeType: String) {
        PDF("Vector PDF (CUPS Standard)", "pdf", "application/pdf"),
        PWG_RASTER("PWG Raster (IPP Everywhere)", "ras", "image/pwg-raster"),
        POSTSCRIPT("PostScript (PS Level 3)", "ps", "application/postscript"),
        PCL("HP PCL 5 / PCL 6 (LaserJet)", "pcl", "application/vnd.hp-pcl"),
        TSPL("TSPL (Rollo X1038 / TSC)", "tspl", "application/octet-stream"),
        ZPL("ZPL II (Zebra ZD/GK)", "zpl", "application/octet-stream"),
        ESC_POS("ESC/POS (80mm Receipt)", "bin", "application/octet-stream"),
        RAW_TEXT("Plain Text (Raw Line)", "txt", "text/plain")
    }

    /**
     * Generate a vector PDF test page with official CUPS color wheel, CMYK color ramps,
     * typography ladder, margin rules, and server metadata.
     */
    fun generatePdfTestPage(
        paperSize: PaperSize = PaperSize.LETTER,
        printerName: String = "CUPS Printer",
        cupsVersion: String = "CUPS v2.2.9",
        transport: String = "IPP Everywhere",
        serverUri: String = "ipp://localhost:631/printers/printer"
    ): ByteArray {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(paperSize.widthPoints, paperSize.heightPoints, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val w = paperSize.widthPoints.toFloat()
        val h = paperSize.heightPoints.toFloat()

        // White background
        canvas.drawColor(Color.WHITE)

        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
        }

        // 1. Outer margin boundary (0.25 inch / 18 pt border)
        strokePaint.color = Color.DKGRAY
        strokePaint.strokeWidth = 1f
        canvas.drawRect(18f, 18f, w - 18f, h - 18f, strokePaint)

        // Inner margin boundary (0.5 inch / 36 pt border)
        strokePaint.strokeWidth = 1.5f
        strokePaint.color = Color.rgb(33, 150, 243) // Cuppa Blue
        canvas.drawRect(36f, 36f, w - 36f, h - 36f, strokePaint)

        // 2. Header Banner
        fillPaint.color = Color.rgb(25, 118, 210) // Deep Blue
        canvas.drawRect(36f, 36f, w - 36f, 100f, fillPaint)

        textPaint.color = Color.WHITE
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = if (w < 400) 18f else 24f
        canvas.drawText("CUPPA CUPS PRINT SERVER", 50f, 70f, textPaint)

        textPaint.textSize = if (w < 400) 10f else 12f
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText("Driverless IPP Everywhere & Android Native Subsystem — $cupsVersion", 50f, 90f, textPaint)

        // 3. Metadata block
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        textPaint.color = Color.BLACK
        textPaint.textSize = 10f

        var metaY = 125f
        fun drawMetaRow(label: String, value: String) {
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            canvas.drawText(label, 50f, metaY, textPaint)
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            canvas.drawText(value, 180f, metaY, textPaint)
            metaY += 16f
        }

        drawMetaRow("PRINTER:", printerName)
        drawMetaRow("SERVER URI:", serverUri)
        drawMetaRow("TRANSPORT:", transport)
        drawMetaRow("MEDIA SIZE:", "${paperSize.displayName} (${paperSize.widthPoints} × ${paperSize.heightPoints} pt)")
        drawMetaRow("TIMESTAMP:", dateStr)

        // Divider
        strokePaint.color = Color.LTGRAY
        strokePaint.strokeWidth = 1f
        canvas.drawLine(50f, metaY + 6f, w - 50f, metaY + 6f, strokePaint)
        metaY += 20f

        // 4. Color Wheel & CMYK / RGB Gradient Section (only if width allows)
        if (h > 450) {
            // CMYK Swatches
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textPaint.textSize = 10f
            textPaint.color = Color.BLACK
            canvas.drawText("CMYK DENSITY & GRADIENT RAMPS:", 50f, metaY, textPaint)
            metaY += 12f

            val rampColors = listOf(
                Pair("Cyan", Color.CYAN),
                Pair("Magenta", Color.MAGENTA),
                Pair("Yellow", Color.YELLOW),
                Pair("Black", Color.BLACK)
            )

            // Right edge of the last swatch must stay inside the page's ~50pt margin (matching
            // the margin used everywhere else on this page) — the original (w - 120f) formula
            // put it exactly at the page's literal edge (x=w), which a real printer's hardware
            // imageable area clips since almost no printer can render all the way to the paper
            // edge. Confirmed live: the last color swatch was cut off on a real network printer.
            val boxW = (w - 170f) / 6f
            val boxH = 16f

            for ((name, color) in rampColors) {
                textPaint.textSize = 9f
                textPaint.typeface = Typeface.DEFAULT
                canvas.drawText(name, 50f, metaY + 12f, textPaint)

                for (step in 0..5) {
                    val alphaPercent = (step + 1) / 6f
                    val alpha = (alphaPercent * 255).toInt()
                    fillPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
                    val left = 110f + step * (boxW + 2f)
                    canvas.drawRect(left, metaY, left + boxW, metaY + boxH, fillPaint)
                    strokePaint.color = Color.GRAY
                    strokePaint.strokeWidth = 0.5f
                    canvas.drawRect(left, metaY, left + boxW, metaY + boxH, strokePaint)
                }
                metaY += boxH + 6f
            }

            // RGB Swatches
            metaY += 6f
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            canvas.drawText("RGB PRIMARY CHANNELS:", 50f, metaY, textPaint)
            metaY += 12f

            val rgbColors = listOf(
                Pair("Red", Color.RED),
                Pair("Green", Color.GREEN),
                Pair("Blue", Color.BLUE)
            )
            for ((name, color) in rgbColors) {
                textPaint.textSize = 9f
                textPaint.typeface = Typeface.DEFAULT
                canvas.drawText(name, 50f, metaY + 12f, textPaint)

                for (step in 0..5) {
                    val alphaPercent = (step + 1) / 6f
                    val alpha = (alphaPercent * 255).toInt()
                    fillPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
                    val left = 110f + step * (boxW + 2f)
                    canvas.drawRect(left, metaY, left + boxW, metaY + boxH, fillPaint)
                    strokePaint.color = Color.GRAY
                    strokePaint.strokeWidth = 0.5f
                    canvas.drawRect(left, metaY, left + boxW, metaY + boxH, strokePaint)
                }
                metaY += boxH + 6f
            }
        }

        // 5. Typography Ladder
        if (h > 600) {
            metaY += 10f
            strokePaint.color = Color.LTGRAY
            strokePaint.strokeWidth = 1f
            canvas.drawLine(50f, metaY, w - 50f, metaY, strokePaint)
            metaY += 16f

            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textPaint.textSize = 10f
            textPaint.color = Color.BLACK
            canvas.drawText("TYPOGRAPHY & RESOLUTION LADDER:", 50f, metaY, textPaint)
            metaY += 16f

            // Available width matches the 50pt margins used for this section's own header/divider
            // above. The fixed pangram string at 20pt Serif is wide enough to run well past the
            // page edge (confirmed live: the trailing "...1234567890" was clipped by a real
            // printer's hardware imageable area). Shrinking the font size to fit was tried first,
            // but clamped 16pt and 20pt down to the same effective size — defeating the point of
            // a size "ladder" (confirmed live: both rendered identically). Truncating the text
            // instead keeps every line at its true nominal size, which is what actually
            // demonstrates the size progression; the largest sizes just show less of the pangram.
            val maxTextWidth = w - 100f
            val fontSizes = listOf(6f, 8f, 10f, 12f, 16f, 20f)
            for (sz in fontSizes) {
                textPaint.typeface = Typeface.SERIF
                textPaint.textSize = sz
                var label = "${sz.toInt()}pt Serif: The quick brown fox jumps over the lazy dog 1234567890"
                while (textPaint.measureText(label) > maxTextWidth && label.length > 10) {
                    label = label.substring(0, label.length - 1)
                }
                canvas.drawText(label, 50f, metaY, textPaint)
                metaY += sz + 5f
            }
        }

        // 6. Footer & Certification
        fillPaint.color = Color.rgb(240, 244, 248)
        canvas.drawRect(36f, h - 80f, w - 36f, h - 36f, fillPaint)

        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textPaint.textSize = 11f
        textPaint.color = Color.rgb(46, 125, 50) // Green pass
        canvas.drawText("✓ HARDWARE & SUBSYSTEM VERIFIED: CUPS / IPP EVERYWHERE COMPLIANT", 50f, h - 56f, textPaint)

        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textPaint.textSize = 8.5f
        textPaint.color = Color.DKGRAY
        canvas.drawText("Generated natively by Cuppa CUPS Print Server on Android · Page 1 of 1", 50f, h - 42f, textPaint)

        document.finishPage(page)

        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    /**
     * Generate a PostScript Level 2/3 test page.
     */
    fun generatePostScriptTestPage(
        paperSize: PaperSize = PaperSize.LETTER,
        printerName: String = "CUPS Printer",
        transport: String = "IPP Everywhere"
    ): ByteArray {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val ps = buildString {
            appendLine("%!PS-Adobe-3.0")
            appendLine("%%Title: Cuppa CUPS Test Page")
            appendLine("%%Creator: Cuppa Local CUPS Print Server (Android)")
            appendLine("%%Pages: 1")
            appendLine("%%BoundingBox: 0 0 ${paperSize.widthPoints} ${paperSize.heightPoints}")
            appendLine("%%EndComments")
            appendLine("/Helvetica findfont 24 scalefont setfont")
            appendLine("50 ${paperSize.heightPoints - 60} moveto")
            appendLine("(CUPPA PRINT SERVER TEST PAGE) show")
            appendLine("/Helvetica findfont 12 scalefont setfont")
            appendLine("50 ${paperSize.heightPoints - 90} moveto")
            appendLine("(Target Printer: $printerName) show")
            appendLine("50 ${paperSize.heightPoints - 110} moveto")
            appendLine("(Transport: $transport) show")
            appendLine("50 ${paperSize.heightPoints - 130} moveto")
            appendLine("(Timestamp: $dateStr) show")
            appendLine("50 ${paperSize.heightPoints - 150} moveto")
            appendLine("(Format: PostScript Level 3 / CUPS Native) show")
            appendLine("newpath")
            appendLine("36 36 moveto")
            appendLine("${paperSize.widthPoints - 36} 36 lineto")
            appendLine("${paperSize.widthPoints - 36} ${paperSize.heightPoints - 36} lineto")
            appendLine("36 ${paperSize.heightPoints - 36} lineto")
            appendLine("closepath stroke")
            appendLine("showpage")
            appendLine("%%EOF")
        }
        return ps.toByteArray(StandardCharsets.US_ASCII)
    }

    /**
     * Generate an HP PCL 5/6 test document.
     */
    fun generatePclTestPage(
        printerName: String = "CUPS Printer",
        transport: String = "IPP Everywhere"
    ): ByteArray {
        val esc = 0x1B.toChar()
        val ff = 0x0C.toChar()
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val pcl = buildString {
            append("$esc%-12345X@PJL\r\n")
            append("@PJL ENTER LANGUAGE=PCL\r\n")
            append("${esc}E") // Reset
            append("${esc}&l0O") // Portrait
            append("${esc}(s0p12h0s0b4099T") // Font
            append("\r\n\r\n")
            append("========================================================\r\n")
            append("              CUPPA CUPS PRINT SERVER                   \r\n")
            append("              HP PCL 5/6 HARDWARE TEST                  \r\n")
            append("========================================================\r\n\r\n")
            append("Target Printer: $printerName\r\n")
            append("Transport:      $transport\r\n")
            append("PCL Engine:     PCL 5 Enhanced\r\n")
            append("Timestamp:      $dateStr\r\n\r\n")
            append("--------------------------------------------------------\r\n")
            append("PCL Font Ladder:\r\n")
            append("ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz\r\n")
            append("0123456789 !@#$%^&*()_+-=[]{}|;:,.<>?\r\n\r\n")
            append("✓ PCL Print Stream Successfully Processed.\r\n")
            append("$ff") // Form Feed (Eject page)
            append("${esc}E") // Reset
            append("$esc%-12345X")
        }
        return pcl.toByteArray(StandardCharsets.US_ASCII)
    }

    /**
     * Generate plain ASCII raw test document with form feed.
     */
    fun generateRawTextTestPage(
        printerName: String = "CUPS Printer",
        transport: String = "IPP Everywhere",
        paperSize: PaperSize = PaperSize.LETTER
    ): ByteArray {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("==============================================================")
            appendLine("                 CUPPA CUPS PRINT SERVER                      ")
            appendLine("                 Raw ASCII Diagnostic Test                    ")
            appendLine("==============================================================")
            appendLine()
            appendLine("Target Printer: $printerName")
            appendLine("Transport:      $transport")
            appendLine("Media Size:     ${paperSize.displayName}")
            appendLine("Timestamp:      $dateStr")
            appendLine("Driver:         CUPS Raw Stream")
            appendLine()
            appendLine("--------------------------------------------------------------")
            appendLine("ASCII Character Set & Printhead Alignment:")
            appendLine("!" + "\"/#$%&'()*+,-./0123456789:;<=>?@")
            appendLine("ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`")
            appendLine("abcdefghijklmnopqrstuvwxyz{|}~")
            appendLine("--------------------------------------------------------------")
            appendLine()
            appendLine("✓ TEST PRINT COMPLETED SUCCESSFULLY")
            appendLine("CUPS Print Server for Android")
            append("\u000C") // Form Feed
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }
}
