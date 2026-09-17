package com.cuppa.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.cuppa.app.BuildConfig
import com.cuppa.app.util.CuppaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val releaseNotes: String,
    val apkSizeBytes: Long,
    val htmlUrl: String
)

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : DownloadState
    data class ReadyToInstall(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * UpdateManager — checks GitHub Releases for a newer version, downloads the APK to the app's
 * own private cache (no storage permission needed), and hands it to the system installer via
 * the FileProvider already declared for log exports.
 *
 * versionCode derivation from a release tag (vMAJOR.MINOR.PATCH -> MAJOR*10000 + MINOR*100 +
 * PATCH) must stay in sync with .github/workflows/release.yml's "Compute version from tag" step,
 * since that's what actually assigns the versionCode baked into each published APK.
 */
object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val REPO = "modnite/Cuppa"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_URL = "https://github.com/$REPO/releases"
    private const val PREFS_NAME = "cuppa_update_prefs"
    private const val KEY_LAST_CHECK = "last_check_time"
    private const val KEY_AUTO_CHECK = "auto_check_enabled"
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours

    private val _availableUpdate = MutableStateFlow<UpdateInfo?>(null)
    val availableUpdate: StateFlow<UpdateInfo?> = _availableUpdate.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    fun isAutoCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    /**
     * Checks GitHub Releases for a version newer than this build. [force] bypasses both the
     * auto-check throttle and the auto-check-enabled setting — used by the manual "Check Now"
     * button; the throttled/gated path is used for the on-launch background check.
     */
    suspend fun checkForUpdate(context: Context, force: Boolean = false): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!force) {
            if (!isAutoCheckEnabled(context)) return@withContext Result.success(null)
            val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
            if (System.currentTimeMillis() - lastCheck < AUTO_CHECK_INTERVAL_MS) {
                return@withContext Result.success(_availableUpdate.value)
            }
        }

        try {
            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 8000
                readTimeout = 8000
            }
            val code = connection.responseCode
            if (code != 200) {
                connection.disconnect()
                return@withContext Result.failure(Exception("GitHub API returned HTTP $code"))
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").removePrefix("v")
            val remoteVersionCode = versionNameToCode(tagName)
            if (tagName.isBlank() || remoteVersionCode == null) {
                return@withContext Result.success(null)
            }

            if (remoteVersionCode <= BuildConfig.VERSION_CODE) {
                _availableUpdate.value = null
                return@withContext Result.success(null)
            }

            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var apkSize = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        apkSize = asset.optLong("size")
                        break
                    }
                }
            }
            if (apkUrl.isNullOrBlank()) {
                CuppaLog.w(TAG, "Release $tagName has no .apk asset")
                return@withContext Result.success(null)
            }

            val info = UpdateInfo(
                versionName = tagName,
                versionCode = remoteVersionCode,
                downloadUrl = apkUrl,
                releaseNotes = json.optString("body", ""),
                apkSizeBytes = apkSize,
                htmlUrl = json.optString("html_url", RELEASES_URL)
            )
            _availableUpdate.value = info
            Result.success(info)
        } catch (e: Exception) {
            CuppaLog.w(TAG, "Update check failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** vMAJOR.MINOR.PATCH -> MAJOR*10000 + MINOR*100 + PATCH, matching the release workflow. */
    private fun versionNameToCode(versionName: String): Int? {
        val parts = versionName.split(".")
        if (parts.size != 3) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = parts[2].toIntOrNull() ?: return null
        return major * 10000 + minor * 100 + patch
    }

    suspend fun downloadUpdate(context: Context, info: UpdateInfo) = withContext(Dispatchers.IO) {
        _downloadState.value = DownloadState.Downloading(0, info.apkSizeBytes)
        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Clear any previously downloaded APKs before starting a new one.
            updatesDir.listFiles()?.forEach { it.delete() }
            val outFile = File(updatesDir, "cuppa-${info.versionName}.apk")

            val connection = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 15000
                instanceFollowRedirects = true
            }
            val total = if (info.apkSizeBytes > 0) info.apkSizeBytes else connection.contentLengthLong

            connection.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        _downloadState.value = DownloadState.Downloading(downloaded, total)
                    }
                }
            }
            connection.disconnect()
            _downloadState.value = DownloadState.ReadyToInstall(outFile)
        } catch (e: Exception) {
            CuppaLog.e(TAG, "Update download failed", e)
            _downloadState.value = DownloadState.Failed(e.message ?: "Download failed")
        }
    }

    fun resetDownloadState() {
        _downloadState.value = DownloadState.Idle
    }

    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun requestInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
