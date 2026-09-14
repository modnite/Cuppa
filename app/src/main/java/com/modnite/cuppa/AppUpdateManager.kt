package com.modnite.cuppa

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class AppUpdateManager(
    private val context: Context,
    private val currentVersionName: String,
    private val logger: (String) -> Unit,
    private val onUpdateAvailable: (String, String, String) -> Unit // latestVersion, releaseNotes, apkUrl
) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    companion object {
        const val GITHUB_RELEASE_API = "https://api.github.com/repos/modnite/Cuppa/releases/latest"
    }

    fun startPeriodicCheck() {
        executor.scheduleWithFixedDelay({
            checkForUpdates(silent = true)
        }, 30, 15 * 60, TimeUnit.SECONDS) // Check every 15 minutes
    }

    fun checkForUpdates(silent: Boolean = false) {
        Executors.newSingleThreadExecutor().execute {
            try {
                if (!silent) logger("[UPDATE] Checking GitHub for new Cuppa releases...")
                val url = URL(GITHUB_RELEASE_API)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "Cuppa-AndroidApp")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)

                    val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                    val body = json.optString("body", "Bug fixes and performance improvements.")

                    var apkUrl = ""
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    if (isNewerVersion(tagName, currentVersionName) && apkUrl.isNotEmpty()) {
                        logger("[UPDATE] New version available: v$tagName (Current: v$currentVersionName)")
                        onUpdateAvailable(tagName, body, apkUrl)
                    } else {
                        if (!silent) logger("[UPDATE] Cuppa v$currentVersionName is up to date.")
                    }
                } else {
                    if (!silent) logger("[UPDATE] GitHub API returned HTTP ${conn.responseCode}")
                }
            } catch (e: Exception) {
                if (!silent) logger("[UPDATE] Update check notice: ${e.message}")
            }
        }
    }

    fun downloadAndInstallApk(apkUrl: String, onProgress: (String) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            try {
                onProgress("[UPDATE] Downloading Cuppa update...")
                val url = URL(apkUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val apkFile = File(context.cacheDir, "Cuppa_Update.apk")
                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }

                onProgress("[UPDATE] Download complete. Launching installer...")

                val contentUri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

            } catch (e: Exception) {
                onProgress("[UPDATE] Update install notice: ${e.message}")
            }
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

            for (i in 0 until Math.max(latestParts.size, currentParts.size)) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
