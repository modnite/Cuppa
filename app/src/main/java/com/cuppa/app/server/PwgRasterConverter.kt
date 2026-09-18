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
 * PwgRasterConverter — renders a PDF and encodes it as PWG-Raster, the format IPP
 * Everywhere / AirPrint mandates.
 *
 * Real network printers that advertise "image/pwg-raster" but not "application/pdf" (most
 * consumer inkjets — confirmed live against an Epson L3250) have no PDF interpreter on board:
 * sending them a raw PDF gets accepted at the IPP protocol layer (200 OK, job queued) but is
 * silently dropped during rendering, since the printer's RIP has nothing that understands it.
 * Real AirPrint clients always convert locally before sending; this does the same conversion
 * Cuppa was missing.
 *
 * [convertAllPagesToPwgRaster] writes every page into one stream (used for forwarded jobs);
 * [convertFirstPageToPwgRaster] is the one-page variant Cuppa's own test pages use.
 */
object PwgRasterConverter {
    private const val TAG = "PwgRasterConverter"
    private const val DPI = 300

    /**
     * Renders every page of [pdfFile] into one multi-page PWG-Raster document. With [copies] above
     * one the whole document is written that many times in a row (collated), so the copy count
     * does not depend on the target printer honoring a "copies" attribute.
     */
    suspend fun convertAllPagesToPwgRaster(pdfFile: File, outputFile: File, colorSupported: Boolean, copies: Int = 1, marginsMm100: IntArray? = null): Boolean {
        return try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pageCount = renderer.pageCount
                    if (pageCount == 0) return false
                    CupsEngine.encodePwgRasterDocument(outputFile.absolutePath, colorSupported) { addPage ->
                        for (i in 0 until pageCount * copies.coerceAtLeast(1)) {
                            val page = renderer.openPage(i % pageCount)
                            val widthPx = (page.width / 72.0 * DPI).toInt().coerceAtLeast(1)
                            val heightPx = (page.height / 72.0 * DPI).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                            Canvas(bitmap).drawColor(Color.WHITE)
                            if (marginsMm100 != null) {
                                // Shrink the page into the printer's printable area (left, top, right,
                                // bottom in hundredths of a mm). Otherwise the printer clips whatever
                                // sits inside its unprintable border, which is most of an inch at the
                                // bottom of an inkjet page.
                                val l = (marginsMm100[0] / 2540.0 * DPI).toInt()
                                val t = (marginsMm100[1] / 2540.0 * DPI).toInt()
                                val r = (marginsMm100[2] / 2540.0 * DPI).toInt()
                                val b = (marginsMm100[3] / 2540.0 * DPI).toInt()
                                val availW = (widthPx - l - r).coerceAtLeast(1)
                                val availH = (heightPx - t - b).coerceAtLeast(1)
                                val s = minOf(availW.toDouble() / widthPx, availH.toDouble() / heightPx)
                                val tw = (widthPx * s).toInt().coerceAtLeast(1)
                                val th = (heightPx * s).toInt().coerceAtLeast(1)
                                val small = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                                Canvas(small).drawColor(Color.WHITE)
                                page.render(small, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                Canvas(bitmap).drawBitmap(small, (l + (availW - tw) / 2).toFloat(), (t + (availH - th) / 2).toFloat(), null)
                                small.recycle()
                            } else {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            }
                            page.close()

                            val bpp = if (colorSupported) 3 else 1
                            val packed = ByteArray(widthPx * heightPx * bpp)
                            val pixels = IntArray(widthPx * heightPx)
                            bitmap.getPixels(pixels, 0, widthPx, 0, 0, widthPx, heightPx)
                            bitmap.recycle()
                            var idx = 0
                            for (p in pixels) {
                                val r = (p shr 16) and 0xFF
                                val g = (p shr 8) and 0xFF
                                val b = p and 0xFF
                                if (colorSupported) {
                                    packed[idx++] = r.toByte(); packed[idx++] = g.toByte(); packed[idx++] = b.toByte()
                                } else {
                                    packed[idx++] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
                                }
                            }
                            if (!addPage(packed, widthPx, heightPx, DPI)) return@encodePwgRasterDocument false
                        }
                        true
                    }.also { CuppaLog.i(TAG, "PDF ($pageCount page(s)) -> PWG-Raster @ ${DPI}dpi, color=$colorSupported: $it") }
                }
            }
        } catch (e: Exception) {
            CuppaLog.e(TAG, "Failed to convert PDF to PWG-Raster", e)
            false
        }
    }

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
