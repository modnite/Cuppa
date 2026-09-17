package com.cuppa.cups.thermal

import android.graphics.Bitmap

/**
 * ThermalRasterizer — 1-bit monochrome dithering engine for thermal printheads.
 *
 * Thermal Printhead Polarity Physics:
 * - Standard Thermal Polarity (default):
 *     1 = Heat pin / Burn (Black dot on thermal paper)
 *     0 = Leave pin cool (White paper unheated)
 * - Inverted Polarity:
 *     1 = White dot / skip
 *     0 = Black dot / burn
 *
 * Formats:
 * - Packed 1-bit MSB-first byte array (row-padded to 8-bit boundaries).
 * - Hexadecimal ASCII stream for ZPL ^GFA commands.
 */
object ThermalRasterizer {

    enum class DitherMode {
        /** Hard luminance threshold (fastest, ideal for crisp barcodes, clean text, and solid vector lines). */
        THRESHOLD,

        /** 2D Floyd-Steinberg error diffusion (standard for photos and continuous tone graphics). */
        FLOYD_STEINBERG,

        /** Atkinson error diffusion (higher contrast, preserves highlights, classic Mac/receipt aesthetic). */
        ATKINSON
    }

    /**
     * Rasterize an Android [Bitmap] into a 1-bit packed byte array for thermal printing.
     *
     * @param bitmap The source image to rasterize.
     * @param ditherMode The dithering algorithm to apply.
     * @param invertPolarity If true, inverts bits (swapping black and white).
     * @param threshold Luminance cutoff (0..255) used for THRESHOLD mode (default 128).
     * @return Packed 1-bit byte array, MSB-first, row-padded to 8 bits.
     */
    fun rasterize(
        bitmap: Bitmap,
        ditherMode: DitherMode = DitherMode.FLOYD_STEINBERG,
        invertPolarity: Boolean = false,
        threshold: Int = 128
    ): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val rowBytes = (width + 7) / 8
        val output = ByteArray(rowBytes * height)

        when (ditherMode) {
            DitherMode.THRESHOLD -> {
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                for (y in 0 until height) {
                    val rowOffset = y * rowBytes
                    for (x in 0 until width) {
                        val color = pixels[y * width + x]
                        val alpha = (color shr 24) and 0xFF
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF

                        // Transparent pixels are treated as white (no burn)
                        if (alpha < 128) continue

                        val lum = (299 * r + 587 * g + 114 * b) / 1000

                        // Standard thermal: lum <= threshold means dark pixel -> burn (1)
                        var isBurn = lum <= threshold
                        if (invertPolarity) {
                            isBurn = !isBurn
                        }

                        if (isBurn) {
                            val byteIndex = rowOffset + (x / 8)
                            val bitIndex = 7 - (x % 8) // MSB first
                            output[byteIndex] = (output[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                        }
                    }
                }
            }

            DitherMode.FLOYD_STEINBERG -> {
                // Working buffer with floating point/integer grayscale for error diffusion
                val gray = FloatArray(width * height)
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                for (i in pixels.indices) {
                    val color = pixels[i]
                    val alpha = (color shr 24) and 0xFF
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    // Transparent pixels → white (255) to prevent phantom black regions
                    gray[i] = if (alpha < 128) 255f else (299f * r + 587f * g + 114f * b) / 1000f
                }

                for (y in 0 until height) {
                    val rowOffset = y * rowBytes
                    for (x in 0 until width) {
                        val idx = y * width + x
                        val oldVal = gray[idx].coerceIn(0f, 255f)
                        val newVal = if (oldVal < 128f) 0f else 255f
                        val error = oldVal - newVal

                        var isBurn = newVal == 0f // 0 is black -> burn
                        if (invertPolarity) {
                            isBurn = !isBurn
                        }

                        if (isBurn) {
                            val byteIndex = rowOffset + (x / 8)
                            val bitIndex = 7 - (x % 8)
                            output[byteIndex] = (output[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                        }

                        // Distribute error (Floyd-Steinberg: 7/16 right, 3/16 down-left, 5/16 down, 1/16 down-right)
                        if (x + 1 < width) {
                            gray[idx + 1] += error * (7f / 16f)
                        }
                        if (y + 1 < height) {
                            if (x - 1 >= 0) {
                                gray[(y + 1) * width + (x - 1)] += error * (3f / 16f)
                            }
                            gray[(y + 1) * width + x] += error * (5f / 16f)
                            if (x + 1 < width) {
                                gray[(y + 1) * width + (x + 1)] += error * (1f / 16f)
                            }
                        }
                    }
                }
            }

            DitherMode.ATKINSON -> {
                val gray = FloatArray(width * height)
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                for (i in pixels.indices) {
                    val color = pixels[i]
                    val alpha = (color shr 24) and 0xFF
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    // Transparent pixels → white (255) to prevent phantom black regions
                    gray[i] = if (alpha < 128) 255f else (299f * r + 587f * g + 114f * b) / 1000f
                }

                for (y in 0 until height) {
                    val rowOffset = y * rowBytes
                    for (x in 0 until width) {
                        val idx = y * width + x
                        val oldVal = gray[idx].coerceIn(0f, 255f)
                        val newVal = if (oldVal < 128f) 0f else 255f
                        val error = oldVal - newVal
                        val e8 = error / 8f

                        var isBurn = newVal == 0f
                        if (invertPolarity) {
                            isBurn = !isBurn
                        }

                        if (isBurn) {
                            val byteIndex = rowOffset + (x / 8)
                            val bitIndex = 7 - (x % 8)
                            output[byteIndex] = (output[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                        }

                        // Atkinson distributes 1/8 to 6 neighboring pixels
                        if (x + 1 < width) gray[y * width + (x + 1)] += e8
                        if (x + 2 < width) gray[y * width + (x + 2)] += e8
                        if (y + 1 < height) {
                            if (x - 1 >= 0) gray[(y + 1) * width + (x - 1)] += e8
                            gray[(y + 1) * width + x] += e8
                            if (x + 1 < width) gray[(y + 1) * width + (x + 1)] += e8
                        }
                        if (y + 2 < height) {
                            gray[(y + 2) * width + x] += e8
                        }
                    }
                }
            }
        }

        return output
    }

    /**
     * Convert bitmap into uppercase hexadecimal ASCII string formatted for ZPL ^GFA graphics.
     */
    fun rasterizeToHex(
        bitmap: Bitmap,
        ditherMode: DitherMode = DitherMode.FLOYD_STEINBERG,
        invertPolarity: Boolean = false
    ): String {
        val bytes = rasterize(bitmap, ditherMode, invertPolarity)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
}
