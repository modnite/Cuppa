package com.cuppa.app.util

import android.content.pm.PackageManager
import com.cuppa.app.util.CuppaLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ShizukuHelper — Integration with Shizuku / Sui for privileged Android operations.
 *
 * Provides:
 * - Reactive Binder connection status (StateFlow)
 * - Permission checking and requesting with live callbacks
 * - Executing elevated shell commands via Shizuku process
 * - Detection if Shizuku is running as root (UID 0)
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 6310

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _hasPermission = MutableStateFlow(false)
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _versionInfo = MutableStateFlow("Not running")
    val versionInfo: StateFlow<String> = _versionInfo.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received!")
        updateState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead!")
        updateState()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            Log.i(TAG, "Shizuku permission result: $grantResult")
            updateState()
        }
    }

    private var initialized = false

    /**
     * Register sticky lifecycle listeners with Shizuku.
     * Safe to call multiple times.
     */
    fun initialize() {
        if (initialized) return
        initialized = true
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            updateState()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register Shizuku listeners: ${e.message}")
            updateState()
        }
    }

    /**
     * Query live Shizuku status and update all reactive state flows.
     */
    fun updateState() {
        val available = isShizukuAvailable()
        _isAvailable.value = available

        if (available) {
            val perm = hasPermissionInternal()
            _hasPermission.value = perm
            _versionInfo.value = try {
                val v = Shizuku.getVersion()
                val uid = Shizuku.getUid()
                "v$v (UID $uid)"
            } catch (_: Throwable) {
                "Running"
            }
        } else {
            _hasPermission.value = false
            _versionInfo.value = "Not running"
        }
    }

    /**
     * Check if the Shizuku server is alive and responding.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasPermissionInternal(): Boolean {
        return try {
            if (Shizuku.isPreV11()) {
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check Shizuku permission: ${e.message}")
            false
        }
    }

    /**
     * Check if Shizuku permission has been granted to Cuppa.
     */
    fun hasPermission(): Boolean {
        if (!isShizukuAvailable()) return false
        return hasPermissionInternal()
    }

    /**
     * Request Shizuku permission from the user.
     */
    fun requestPermission(requestCode: Int = SHIZUKU_PERMISSION_REQUEST_CODE) {
        if (!isShizukuAvailable()) return
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Log.i(TAG, "Should show Shizuku permission rationale")
            }
            Shizuku.requestPermission(requestCode)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    /**
     * Check if Shizuku is running with root privileges (UID 0).
     */
    fun isRunningAsRoot(): Boolean {
        if (!hasPermission()) return false
        return try {
            Shizuku.getUid() == 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Get Shizuku version string.
     */
    fun getVersion(): String {
        return _versionInfo.value
    }

    /**
     * Execute a shell command via Shizuku's elevated process.
     */
    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext Result.failure(IllegalStateException("Shizuku permission not granted"))
        }

        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val output = StringBuilder()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val errorOutput = StringBuilder()
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Result.success(output.toString().trim())
            } else {
                val err = errorOutput.toString().trim().ifEmpty { "Exit code $exitCode" }
                Result.failure(Exception("Shizuku process failed: $err"))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Exception executing Shizuku command: $command", e)
            Result.failure(e)
        }
    }
}
