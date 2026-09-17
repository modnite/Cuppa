package com.cuppa.app.data.ppd

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.cuppa.app.util.CuppaLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Categorized printer driver types.
 */
enum class DriverCategory(val displayName: String) {
    THERMAL("Thermal Label & Receipt"),
    HP_PCL("HP & PCL Laser"),
    GENERIC("Generic Standards"),
    CUSTOM("Custom User Drivers")
}

/**
 * Structured metadata extracted from a PPD (PostScript Printer Description) file.
 */
data class PpdInfo(
    val fileName: String,
    val filePath: String,
    val manufacturer: String,
    val modelName: String,
    val nickName: String,
    val shortNickName: String,
    val isColor: Boolean,
    val resolutions: List<String>,
    val pageSizes: List<String>,
    val isBundled: Boolean,
    val category: DriverCategory
)

/**
 * PpdManager — Manages bundled and user-imported PPD driver files.
 *
 * Extracts bundled PPD assets into internal storage (`filesDir/ppd/`) for CUPS native engine
 * consumption, parses PPD header attributes, and handles custom PPD imports via Android SAF.
 */
class PpdManager(private val context: Context? = null) {

    companion object {
        private const val TAG = "PpdManager"
        private const val ASSETS_PPD_DIR = "ppd"
        private const val INTERNAL_PPD_DIR = "ppd"

        private val BUNDLED_FILE_NAMES = setOf(
            "rollo_x1038.ppd",
            "zebra_zpl.ppd",
            "epson_escpos.ppd",
            "star_linemode.ppd",
            "dymo_lw450.ppd",
            "hp_laserjet_pcl.ppd",
            "generic_postscript.ppd",
            "generic_ipp_everywhere.ppd"
        )
    }

    private val ppdDir: File
        get() = (context?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "cuppa_ppd")).resolve(INTERNAL_PPD_DIR).also {
            if (!it.exists()) it.mkdirs()
        }

    /**
     * Ensure bundled PPD files from assets are extracted to internal storage.
     */
    suspend fun initializeBundledPpds() = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext
        try {
            val assetFiles = ctx.assets.list(ASSETS_PPD_DIR) ?: return@withContext
            for (fileName in assetFiles) {
                if (!fileName.endsWith(".ppd", ignoreCase = true) && !fileName.endsWith(".ppd.gz", ignoreCase = true)) {
                    continue
                }
                val destFile = File(ppdDir, fileName)
                // Copy if missing or out of date
                if (!destFile.exists()) {
                    ctx.assets.open("$ASSETS_PPD_DIR/$fileName").use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Extracted bundled PPD: $fileName -> ${destFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize bundled PPDs", e)
        }
    }

    /**
     * Get all installed PPD drivers (bundled + custom imported).
     */
    suspend fun getInstalledPpds(): List<PpdInfo> = withContext(Dispatchers.IO) {
        val files = ppdDir.listFiles { _, name ->
            name.endsWith(".ppd", ignoreCase = true) || name.endsWith(".ppd.gz", ignoreCase = true)
        } ?: emptyArray()

        files.mapNotNull { file ->
            val isBundled = BUNDLED_FILE_NAMES.contains(file.name)
            parsePpd(file, isBundled)
        }.sortedWith(
            compareBy<PpdInfo> { it.category.ordinal }
                .thenBy { it.manufacturer }
                .thenBy { it.nickName }
        )
    }

    /**
     * Import a custom PPD or PPD.GZ file from a content URI selected via SAF.
     */
    suspend fun importCustomPpd(uri: Uri): Result<PpdInfo> = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext Result.failure(IllegalStateException("Context required for SAF import"))
        try {
            val fileName = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.ppd"
            val destFile = File(ppdDir, fileName)

            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(IllegalArgumentException("Cannot open stream for URI: $uri"))

            val ppdInfo = parsePpd(destFile, isBundled = false)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid PPD file format"))

            Log.i(TAG, "Successfully imported custom PPD: ${ppdInfo.nickName} (${destFile.name})")
            Result.success(ppdInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import PPD from $uri", e)
            Result.failure(e)
        }
    }

    /**
     * Delete a custom installed PPD file.
     */
    suspend fun deletePpd(fileName: String): Boolean = withContext(Dispatchers.IO) {
        if (BUNDLED_FILE_NAMES.contains(fileName)) {
            Log.w(TAG, "Cannot delete bundled PPD: $fileName")
            return@withContext false
        }
        val file = File(ppdDir, fileName)
        if (file.exists()) {
            file.delete().also {
                Log.i(TAG, "Deleted custom PPD: $fileName")
            }
        } else false
    }

    /**
     * Parse a PPD file on disk and extract key metadata.
     */
    fun parsePpd(file: File, isBundled: Boolean): PpdInfo? {
        if (!file.exists() || file.length() == 0L) return null

        var manufacturer = ""
        var modelName = ""
        var nickName = ""
        var shortNickName = ""
        var isColor = false
        val resolutions = mutableListOf<String>()
        val pageSizes = mutableListOf<String>()

        try {
            val inputStream: InputStream = if (file.name.endsWith(".gz", ignoreCase = true)) {
                GZIPInputStream(FileInputStream(file))
            } else {
                FileInputStream(file)
            }

            BufferedReader(InputStreamReader(inputStream, Charsets.ISO_8859_1)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()

                    if (trimmed.startsWith("*Manufacturer:", ignoreCase = true)) {
                        manufacturer = extractQuotedValue(trimmed)
                    } else if (trimmed.startsWith("*ModelName:", ignoreCase = true)) {
                        modelName = extractQuotedValue(trimmed)
                    } else if (trimmed.startsWith("*NickName:", ignoreCase = true)) {
                        nickName = extractQuotedValue(trimmed)
                    } else if (trimmed.startsWith("*ShortNickName:", ignoreCase = true)) {
                        shortNickName = extractQuotedValue(trimmed)
                    } else if (trimmed.startsWith("*ColorDevice:", ignoreCase = true)) {
                        isColor = trimmed.contains("true", ignoreCase = true)
                    } else if (trimmed.startsWith("*Resolution ", ignoreCase = true)) {
                        val res = extractOptionName(trimmed)
                        if (res.isNotBlank() && !resolutions.contains(res)) {
                            resolutions.add(res)
                        }
                    } else if (trimmed.startsWith("*PageSize ", ignoreCase = true)) {
                        val ps = extractOptionName(trimmed)
                        if (ps.isNotBlank() && !pageSizes.contains(ps)) {
                            pageSizes.add(ps)
                        }
                    }

                    line = reader.readLine()
                }
            }

            if (modelName.isBlank() && nickName.isBlank()) {
                modelName = file.nameWithoutExtension
                nickName = modelName
            }
            if (manufacturer.isBlank()) {
                manufacturer = inferManufacturer(file.name, nickName)
            }
            if (shortNickName.isBlank()) {
                shortNickName = modelName
            }

            val category = determineCategory(isBundled, manufacturer, modelName, nickName)

            return PpdInfo(
                fileName = file.name,
                filePath = file.absolutePath,
                manufacturer = manufacturer,
                modelName = modelName,
                nickName = nickName.ifBlank { modelName },
                shortNickName = shortNickName,
                isColor = isColor,
                resolutions = resolutions,
                pageSizes = pageSizes,
                isBundled = isBundled,
                category = category
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing PPD: ${file.name}", e)
            return null
        }
    }

    private fun extractQuotedValue(line: String): String {
        val firstQuote = line.indexOf('"')
        val lastQuote = line.lastIndexOf('"')
        return if (firstQuote != -1 && lastQuote > firstQuote) {
            line.substring(firstQuote + 1, lastQuote).trim()
        } else {
            val colon = line.indexOf(':')
            if (colon != -1) line.substring(colon + 1).trim() else ""
        }
    }

    private fun extractOptionName(line: String): String {
        // e.g. "*Resolution 203dpi/203 DPI: ..." -> "203 DPI" or "203dpi"
        val space = line.indexOf(' ')
        if (space == -1) return ""
        val rest = line.substring(space + 1).trim()
        val colon = rest.indexOf(':')
        val optionPart = if (colon != -1) rest.substring(0, colon).trim() else rest

        val slash = optionPart.indexOf('/')
        return if (slash != -1 && slash + 1 < optionPart.length) {
            optionPart.substring(slash + 1).trim()
        } else {
            optionPart
        }
    }

    private fun inferManufacturer(fileName: String, nickName: String): String {
        val lower = "$fileName $nickName".lowercase()
        return when {
            "rollo" in lower -> "Rollo"
            "zebra" in lower -> "Zebra"
            "epson" in lower -> "Epson"
            "star" in lower -> "Star"
            "dymo" in lower -> "DYMO"
            "hp" in lower || "laserjet" in lower -> "HP"
            "brother" in lower -> "Brother"
            "canon" in lower -> "Canon"
            else -> "Generic"
        }
    }

    private fun determineCategory(
        isBundled: Boolean,
        manufacturer: String,
        modelName: String,
        nickName: String
    ): DriverCategory {
        if (!isBundled) return DriverCategory.CUSTOM

        val combined = "$manufacturer $modelName $nickName".lowercase()
        return when {
            "rollo" in combined || "zebra" in combined || "epson" in combined ||
                    "star" in combined || "dymo" in combined || "thermal" in combined ||
                    "label" in combined || "receipt" in combined || "zpl" in combined ||
                    "escpos" in combined -> DriverCategory.THERMAL
            "hp" in combined || "laserjet" in combined || "pcl" in combined -> DriverCategory.HP_PCL
            else -> DriverCategory.GENERIC
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context?.contentResolver?.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        return name ?: uri.lastPathSegment
    }
}
