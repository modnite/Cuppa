package com.cuppa.cups.thermal

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * PclDriver — HP PCL 5 raster-graphics generator for generic (non-thermal) laser and inkjet
 * printers that have no native driverless/IPP-Everywhere support.
 *
 * A full Gutenprint- or HPLIP-class driver matrix (per-model color profiles, halftone tables,
 * dozens of PPD-driven filter chains) is not realistically portable to Android — Gutenprint alone
 * depends on hundreds of generated XML/data files and a desktop build toolchain, and HPLIP ships
 * proprietary plugin blobs and relies on udev/Python on the host. PCL 5's raster graphics mode is
 * the practical alternative: it is a small, fully self-contained, publicly documented byte
 * protocol that the overwhelming majority of laser/inkjet printers (not just HP hardware) accept
 * as a baseline compatibility mode, the same way CUPS's own generic PCL filter works.
 *
 * This encoder is monochrome-only (no PCL 6/XL, no color raster, no per-model tuning) — that is
 * the honest scope of what a from-scratch implementation can cover here.
 */
class PclDriver(
    val dpi: Int = 300
) {
    private val buffer = ByteArrayOutputStream()
    private val esc = 0x1B

    init {
        // Printer reset (Esc E) puts the printer into a known default state.
        buffer.write(esc)
        buffer.write('E'.code)
    }

    /**
     * Set the page orientation.
     * @param landscape If true, landscape (value 1); otherwise portrait (value 0).
     */
    fun setOrientation(landscape: Boolean = false): PclDriver {
        writeEscCommand("&l${if (landscape) 1 else 0}O")
        return this
    }

    /**
     * Move the raster cursor to an absolute position, in dots from the top-left of the
     * logical page (PCL's default unit-of-measure is 1/300in unless changed; this driver keeps
     * that default so positions map 1:1 to the bitmap's own pixel grid at [dpi]).
     */
    fun setCursorPosition(xDots: Int, yDots: Int): PclDriver {
        writeEscCommand("*p${xDots}x${yDots}Y")
        return this
    }

    /**
     * Render a bitmap using PCL 5 raster graphics mode (monochrome, uncompressed transfer).
     * Reuses the same 1-bit dithering engine as the thermal drivers — PCL raster and thermal
     * printheads share the same bit convention (1 = print/black dot, MSB-first, row-padded to a
     * byte boundary), so no polarity inversion is needed.
     */
    fun printBitmap(
        bitmap: Bitmap,
        ditherMode: ThermalRasterizer.DitherMode = ThermalRasterizer.DitherMode.FLOYD_STEINBERG
    ): PclDriver {
        val rowBytes = (bitmap.width + 7) / 8
        val monoBytes = ThermalRasterizer.rasterize(bitmap, ditherMode, invertPolarity = false)

        // Set raster resolution (Esc*t###R) and source raster width (Esc*r###S).
        writeEscCommand("*t${dpi}R")
        writeEscCommand("*r${bitmap.width}S")
        // Compression method 0 = uncompressed.
        writeEscCommand("*b0M")
        // Start raster graphics at the current cursor position (Esc*r1A).
        writeEscCommand("*r1A")

        for (row in 0 until bitmap.height) {
            writeEscCommand("*b${rowBytes}W")
            buffer.write(monoBytes, row * rowBytes, rowBytes)
        }

        // End raster graphics (Esc*rB).
        writeEscCommand("*rB")
        return this
    }

    /**
     * Eject the current page.
     */
    fun formFeed(): PclDriver {
        buffer.write(0x0C)
        return this
    }

    /**
     * Reset the printer back to its power-on default state.
     */
    fun reset(): PclDriver {
        writeEscCommand("E")
        return this
    }

    /**
     * Build the raw PCL 5 byte stream.
     */
    fun build(): ByteArray = buffer.toByteArray()

    private fun writeEscCommand(command: String) {
        buffer.write(esc)
        buffer.write(command.toByteArray(Charsets.US_ASCII))
    }

    companion object {
        /**
         * Render [bitmap] as a single-page PCL 5 raster document, ready to send directly to a
         * USB or network printer's raw port.
         */
        fun fromBitmap(
            bitmap: Bitmap,
            dpi: Int = 300,
            ditherMode: ThermalRasterizer.DitherMode = ThermalRasterizer.DitherMode.FLOYD_STEINBERG
        ): ByteArray {
            return PclDriver(dpi = dpi)
                .setOrientation(landscape = false)
                .setCursorPosition(0, 0)
                .printBitmap(bitmap, ditherMode = ditherMode)
                .formFeed()
                .reset()
                .build()
        }
    }
}
