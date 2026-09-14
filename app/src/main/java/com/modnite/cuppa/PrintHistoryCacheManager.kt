package com.modnite.cuppa

import android.content.Context
import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PrintHistoryCacheManager(private val context: Context) {

    companion object {
        const val CACHE_DIR_NAME = "print_history_cache"

        @JvmStatic
        fun saveJobScreenshot(
            context: Context,
            bitmap: Bitmap,
            jobId: Int,
            origin: String, // "local" or "network"
            osFamily: String // "macos", "windows", "linux", "android"
        ): File? {
            return try {
                val cacheDir = context.getExternalFilesDir(CACHE_DIR_NAME)
                    ?: File(context.filesDir, CACHE_DIR_NAME)
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val cleanOrigin = if (origin.isEmpty()) "local" else origin.lowercase()
                val cleanOs = if (osFamily.isEmpty()) "unknown" else osFamily.lowercase()

                val fileName = "job_${jobId}_${cleanOrigin}_${cleanOs}_${timestamp}.jpg"
                val file = File(cacheDir, fileName)

                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    out.flush()
                }
                file
            } catch (_: Exception) {
                null
            }
        }

        @JvmStatic
        fun exportCacheZip(context: Context): File? {
            return try {
                val cacheDir = context.getExternalFilesDir(CACHE_DIR_NAME)
                    ?: File(context.filesDir, CACHE_DIR_NAME)
                if (!cacheDir.exists()) return null

                val files = cacheDir.listFiles { file -> file.extension.lowercase() == "jpg" }
                if (files.isNullOrEmpty()) return null

                val zipFile = File(context.cacheDir, "print_cache_export.zip")
                if (zipFile.exists()) zipFile.delete()

                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    for (file in files) {
                        val entry = ZipEntry(file.name)
                        zos.putNextEntry(entry)
                        file.inputStream().use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }
                zipFile
            } catch (_: Exception) {
                null
            }
        }

        @JvmStatic
        fun getCachedFileCount(context: Context): Int {
            val cacheDir = context.getExternalFilesDir(CACHE_DIR_NAME)
                ?: File(context.filesDir, CACHE_DIR_NAME)
            if (!cacheDir.exists()) return 0
            return cacheDir.listFiles { file -> file.extension.lowercase() == "jpg" }?.size ?: 0
        }

        @JvmStatic
        fun clearCache(context: Context): Boolean {
            return try {
                val cacheDir = context.getExternalFilesDir(CACHE_DIR_NAME)
                    ?: File(context.filesDir, CACHE_DIR_NAME)
                if (cacheDir.exists()) {
                    cacheDir.listFiles()?.forEach { it.delete() }
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
