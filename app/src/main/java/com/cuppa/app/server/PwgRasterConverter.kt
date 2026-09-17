package com.cuppa.app.server

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.cuppa.app.util.CuppaLog
import com.cuppa.cups.CupsEngine
import java.io.File

/**
 * PwgRasterConverter — renders a PDF's first page and encodes it as PWG-Raster, the format IPP
 * Everywhere / AirPrint mandates.
 *
 * Real network printers that advertise "image/pwg-raster" but not "application/pdf" (most
 * consumer inkjets — confirmed live against an Epson L3250) have no PDF interpreter on board:
 * sending them a raw PDF gets accepted at the IPP protocol layer (200 OK, job queued) but is
 * silently dropped during rendering, since the printer's RIP has nothing that understands it.
 * Real AirPrint clients always convert locally before sending; this does the same conversion
 * Cuppa was missing.
 *
 * Single-page only, mirroring CupsEngine.encodePwgRasterPage's native-side limitation — a real
 * multi-page PWG-Raster stream needs one sync header for the whole stream followed by consecutive
 * page blocks on a still-open raster writer, which isn't wired up yet. Sufficient today because
 * every document this is used for (Cuppa's own test pages) is one page.
 */
object PwgRasterConverter {
    private const val TAG = "PwgRasterConverter"
    private const val DPI = 300

    suspend fun convertFirstPageToPwgRaster(pdfFile: File, outputFile: File, colorSupported: Boolean): Boolean {
        return try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val page = renderer.openPage(0)
                    val widthPx = (page.width / 72.0 * DPI).toInt().coerceAtLeast(1)
                    val heightPx = (page.height / 72.0 * DPI).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()

                    val bytesPerPixel = if (colorSupported) 3 else 1
                    val packed = ByteArray(widthPx * heightPx * bytesPerPixel)
                    val pixels = IntArray(widthPx * heightPx)
                    bitmap.getPixels(pixels, 0, widthPx, 0, 0, widthPx, heightPx)

                    var idx = 0
                    for (p in pixels) {
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        if (colorSupported) {
                            packed[idx++] = r.toByte()
                            packed[idx++] = g.toByte()
                            packed[idx++] = b.toByte()
                        } else {
                            packed[idx++] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
                        }
                    }
                    bitmap.recycle()

                    val ok = CupsEngine.encodePwgRasterPage(
                        packed, widthPx, heightPx, DPI, colorSupported, outputFile.absolutePath
                    )
                    CuppaLog.i(TAG, "PDF -> PWG-Raster (${widthPx}x${heightPx} @ ${DPI}dpi, color=$colorSupported): $ok")
                    ok
                }
            }
        } catch (e: Exception) {
            CuppaLog.e(TAG, "Failed to convert PDF to PWG-Raster", e)
            false
        }
    }
}
