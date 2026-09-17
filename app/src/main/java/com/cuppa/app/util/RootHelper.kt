package com.cuppa.app.util

import com.cuppa.app.util.CuppaLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * RootHelper — Utilities for rooted Android devices.
 *
 * Provides capabilities to:
 * - Detect root access (Magisk, KernelSU, APatch, SuperSU)
 * - Run commands via `su`
 * - Enable unprivileged port binding (<1024) via kernel sysctl so port 631 binds directly
 * - Configure iptables NAT redirection as a fallback
 */
object RootHelper {

    private const val TAG = "RootHelper"

    /**
     * Common su binary paths.
     */
    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su",
        "/magisk/.core/bin/su"
    )

    /**
     * Check if a su binary exists on the filesystem.
     */
    fun isRootAvailable(): Boolean {
        for (path in SU_PATHS) {
            if (File(path).exists()) return true
        }
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(":")) {
            if (File(dir, "su").exists()) return true
        }
        return false
    }

    /**
     * Test if root access is granted by running a quick id/whoami command.
     */
    suspend fun isRootGranted(): Boolean = withContext(Dispatchers.IO) {
        val result = executeSu("id")
        result.isSuccess && (result.getOrNull()?.contains("uid=0") == true)
    }

    /**
     * Execute a command as root using `su -c`.
     */
    suspend fun executeSu(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
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
                Result.failure(Exception("su command failed: $err"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing root command: $command", e)
            Result.failure(e)
        }
    }

    /**
     * Enable binding to ports < 1024 (like port 631) for unprivileged applications.
     * Sets Linux sysctl `net.ipv4.ip_unprivileged_port_start=0`.
     */
    suspend fun enablePort631Sysctl(): Result<Boolean> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Attempting to enable unprivileged port start via sysctl...")
        val cmd = "sysctl -w net.ipv4.ip_unprivileged_port_start=0"
        val result = executeSu(cmd)
        if (result.isSuccess) {
            Log.i(TAG, "Successfully enabled unprivileged port binding for port 631")
            Result.success(true)
        } else {
            // Alternative: write directly to procfs
            val procResult = executeSu("echo 0 > /proc/sys/net/ipv4/ip_unprivileged_port_start")
            if (procResult.isSuccess) {
                Log.i(TAG, "Successfully set ip_unprivileged_port_start via procfs")
                Result.success(true)
            } else {
                Log.w(TAG, "Failed to set sysctl for port 631: ${result.exceptionOrNull()?.message}")
                Result.failure(result.exceptionOrNull() ?: Exception("Failed to configure sysctl"))
            }
        }
    }

    /**
     * Setup iptables redirection to forward incoming TCP 631 traffic to an unprivileged port (e.g. 8631).
     */
    suspend fun setupIptablesRedirect(fromPort: Int = 631, toPort: Int = 8631): Result<Boolean> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Setting up iptables redirect: $fromPort -> $toPort")
        val cmd = "iptables -t nat -I PREROUTING -p tcp --dport $fromPort -j REDIRECT --to-ports $toPort"
        val res = executeSu(cmd)
        if (res.isSuccess) {
            Log.i(TAG, "iptables redirect established for port $fromPort -> $toPort")
            Result.success(true)
        } else {
            Log.e(TAG, "Failed to configure iptables redirect: ${res.exceptionOrNull()?.message}")
            Result.failure(res.exceptionOrNull() ?: Exception("iptables redirect failed"))
        }
    }

    /**
     * Remove iptables redirection rule.
     */
    suspend fun removeIptablesRedirect(fromPort: Int = 631, toPort: Int = 8631): Result<Boolean> = withContext(Dispatchers.IO) {
        val cmd = "iptables -t nat -D PREROUTING -p tcp --dport $fromPort -j REDIRECT --to-ports $toPort"
        executeSu(cmd)
        Result.success(true)
    }

    /**
     * Grant USB device permissions via root by chmodding /dev/bus/usb nodes
     * and granting Android permissions directly via pm.
     */
    suspend fun grantUsbPermissions(packageName: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            executeSu("chmod -R 666 /dev/bus/usb/")
            executeSu("chmod -R 666 /dev/usb/")
            executeSu("pm grant $packageName android.permission.POST_NOTIFICATIONS")
            Log.i(TAG, "Root permissions granted for USB and notifications")
            Result.success(true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed root grantUsbPermissions: ${e.message}")
            Result.failure(e)
        }
    }
}
