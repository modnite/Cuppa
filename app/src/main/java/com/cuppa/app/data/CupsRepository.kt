package com.cuppa.app.data

import android.content.Context
import com.cuppa.app.util.CuppaLog
import com.cuppa.app.util.CuppaLog as Log
import com.cuppa.app.util.safeProductName
import com.cuppa.cups.CupsEngine
import com.cuppa.cups.PrintJob
import com.cuppa.cups.PrintJobStatus
import com.cuppa.cups.PrinterInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Represents the status of the CUPS print subsystem.
 */
sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data class Running(val port: Int = 631, val requestedPort: Int = port, val version: String) : ServerState
    data class Error(val message: String) : ServerState
}

/**
 * A completed/failed/canceled job, persisted so the Jobs tab has something to show beyond
 * whatever's currently in the native server's in-memory queue (which is wiped every time the
 * process restarts — force-stop, low-memory kill, reboot).
 */
data class JobHistoryEntry(
    val jobId: Int,
    val jobName: String,
    val printerName: String,
    val printerUri: String,
    val user: String,
    val state: Int,
    val sizeBytes: Long,
    val finishedAt: Long
)

/**
 * Repository providing a reactive interface to CUPS native engine and IPP operations.
 *
 * Manages:
 * - Server lifecycle (start/stop)
 * - Printer list (add/remove/persist)
 * - Print job submission and monitoring
 */
class CupsRepository(
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "CupsRepository"
        private const val PREFS_NAME = "cuppa_printers"
        private const val KEY_PRINTERS = "saved_printers"
        private const val HISTORY_PREFS_NAME = "cuppa_job_history"
        private const val KEY_JOB_HISTORY = "job_history"
        private const val MAX_HISTORY_ENTRIES = 200
        private val TERMINAL_STATES = setOf(7, 8, 9) // CANCELED, ABORTED, COMPLETED

        @Volatile
        private var instance: CupsRepository? = null

        fun getInstance(context: Context? = null): CupsRepository {
            return instance ?: synchronized(this) {
                instance ?: CupsRepository(context?.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _serverState = MutableStateFlow<ServerState>(
        if (CupsEngine.isServerRunning()) {
            ServerState.Running(version = CupsEngine.getVersion())
        } else {
            ServerState.Stopped
        }
    )
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _printers = MutableStateFlow<List<PrinterInfo>>(emptyList())
    val printers: StateFlow<List<PrinterInfo>> = _printers.asStateFlow()

    private val _jobs = MutableStateFlow<List<PrintJob>>(emptyList())
    val jobs: StateFlow<List<PrintJob>> = _jobs.asStateFlow()

    private val _jobHistory = MutableStateFlow<List<JobHistoryEntry>>(emptyList())
    val jobHistory: StateFlow<List<JobHistoryEntry>> = _jobHistory.asStateFlow()

    // Job IDs already written to history, so a job isn't re-recorded every time refreshJobs()
    // happens to see it again before the native server eventually drops it from its own queue.
    // Seeded from persisted history on load so this holds across process restarts too.
    private val recordedJobIds = mutableSetOf<Int>()

    fun getNativeVersion(): String = CupsEngine.getVersion()

    fun refreshServerState(port: Int = 631, requestedPort: Int = port) {
        if (CupsEngine.isServerRunning()) {
            _serverState.value = ServerState.Running(port = port, requestedPort = requestedPort, version = CupsEngine.getVersion())
        } else {
            _serverState.value = ServerState.Stopped
        }
    }

    suspend fun startServer(port: Int = 631, configDir: String? = null): Boolean = withContext(Dispatchers.IO) {
        _serverState.value = ServerState.Starting
        val path = configDir ?: (context?.filesDir?.resolve("cups")?.absolutePath ?: "/data/local/tmp/cups")
        val ok = CupsEngine.startServer(port, path)
        if (ok) {
            _serverState.value = ServerState.Running(port = port, version = CupsEngine.getVersion())
            refreshJobs()
        } else {
            _serverState.value = ServerState.Error("Failed to start CUPS subsystem")
        }
        ok
    }

    suspend fun stopServer() = withContext(Dispatchers.IO) {
        CupsEngine.stopServer()
        _serverState.value = ServerState.Stopped
    }

    suspend fun testPrinterConnection(uri: String): Result<PrinterInfo> = withContext(Dispatchers.IO) {
        val trimmed = uri.trim()
        CuppaLog.i(TAG, "testPrinterConnection initiated for: $trimmed")

        // 1. USB printer handling
        if (trimmed.startsWith("usb:", ignoreCase = true) || trimmed.startsWith("usb://", ignoreCase = true)) {
            val ctx = context
            if (ctx != null) {
                val usbBackend = com.cuppa.app.backend.usb.UsbPrinterBackend(ctx)
                val device = usbBackend.findDeviceByUri(trimmed)
                if (device != null) {
                    val deviceName = device.safeProductName()
                    val devId = usbBackend.queryDeviceId(device)
                    CuppaLog.i(TAG, "Direct USB device verified: $deviceName (VID: 0x${Integer.toHexString(device.vendorId)}, PID: 0x${Integer.toHexString(device.productId)})")
                    val info = PrinterInfo(
                        name = deviceName.replace(" ", "_").replace(Regex("[^a-zA-Z0-9_]"), ""),
                        uri = trimmed,
                        makeAndModel = deviceName,
                        info = devId ?: "Direct USB Thermal",
                        state = 3,
                        isAcceptingJobs = true,
                        supportedFormats = listOf("application/octet-stream", "application/pdf")
                    )
                    return@withContext Result.success(info)
                } else {
                    CuppaLog.w(TAG, "USB printer $trimmed is not attached")
                    return@withContext Result.failure(Exception("USB device ($trimmed) is not currently connected to this device"))
                }
            }
        }

        // 2. Check if already known in local repository
        val localMatch = _printers.value.find { it.uri.equals(trimmed, ignoreCase = true) || it.name.equals(trimmed, ignoreCase = true) }
        if (localMatch != null && !trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("ipp://", ignoreCase = true)) {
            CuppaLog.i(TAG, "Found in local printer repository: ${localMatch.name}")
            return@withContext Result.success(localMatch)
        }

        // 3. Network IPP URI normalization
        var normalizedUri = trimmed
        if (!normalizedUri.contains("://")) {
            normalizedUri = "ipp://$normalizedUri"
        }
        val uriObj = try { java.net.URI(normalizedUri) } catch (_: Exception) { null }
        if (uriObj != null && (uriObj.path.isNullOrEmpty() || uriObj.path == "/")) {
            normalizedUri = normalizedUri.trimEnd('/') + "/ipp/print"
        }

        CuppaLog.i(TAG, "Querying network IPP printer attributes: $normalizedUri")

        try {
            val info = withTimeoutOrNull(7000L) {
                CupsEngine.getPrinterAttributes(normalizedUri)
            }
            if (info != null) {
                CuppaLog.i(TAG, "Successfully queried attributes for: ${info.name} (${info.makeAndModel})")
                Result.success(info)
            } else {
                CuppaLog.w(TAG, "Printer at $normalizedUri returned null attributes or timed out")
                Result.failure(Exception("Unable to connect to printer at $normalizedUri (check IP/port and Wi-Fi connection)"))
            }
        } catch (e: Exception) {
            CuppaLog.e(TAG, "Exception querying printer at $normalizedUri", e)
            Result.failure(e)
        }
    }

    suspend fun refreshJobs(uri: String = ""): List<PrintJob> = withContext(Dispatchers.IO) {
        val jobList = CupsEngine.getJobs(uri)
        // Preserve any in-flight client-dispatch placeholder (negative jobId — see printFile())
        // across this refresh: the native call only knows about jobs that reached the local
        // server's own queue, so an outgoing client-role print (Test Print, etc.) would otherwise
        // vanish from "Active" the instant this poll's result overwrites it.
        val placeholders = _jobs.value.filter { it.jobId < 0 }
        _jobs.value = jobList + placeholders

        // Record any job that has reached a terminal state for the first time. The native
        // server's job list is purely in-memory (see CupsServer::mJobs in cups_server.cpp) and
        // is gone the moment the process restarts, so without this the Jobs tab would always be
        // empty except for whatever's actively printing at that exact moment.
        val newlyTerminal = jobList.filter { it.state in TERMINAL_STATES && it.jobId !in recordedJobIds }
        if (newlyTerminal.isNotEmpty()) {
            for (job in newlyTerminal) {
                recordedJobIds.add(job.jobId)
            }
            val printerByUri = _printers.value.associateBy { it.uri }
            val entries = newlyTerminal.map { job ->
                JobHistoryEntry(
                    jobId = job.jobId,
                    jobName = job.jobName,
                    printerName = printerByUri[job.printerUri]?.name ?: job.printerUri,
                    printerUri = job.printerUri,
                    user = job.user,
                    state = job.state,
                    sizeBytes = job.sizeBytes,
                    finishedAt = System.currentTimeMillis()
                )
            }
            _jobHistory.value = (entries + _jobHistory.value).take(MAX_HISTORY_ENTRIES)
            context?.let { saveJobHistory(it) }
        }

        jobList
    }

    // ---- Job History Persistence ----

    /**
     * Load persisted job history from SharedPreferences. Call once at startup (mirrors
     * loadPrinters()).
     */
    fun loadJobHistory(context: Context) {
        try {
            val prefs = context.getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_JOB_HISTORY, null) ?: return
            val jsonArray = JSONArray(json)
            val loaded = mutableListOf<JobHistoryEntry>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                loaded.add(
                    JobHistoryEntry(
                        jobId = obj.getInt("jobId"),
                        jobName = obj.optString("jobName", "Untitled"),
                        printerName = obj.optString("printerName", ""),
                        printerUri = obj.optString("printerUri", ""),
                        user = obj.optString("user", ""),
                        state = obj.optInt("state", 9),
                        sizeBytes = obj.optLong("sizeBytes", 0L),
                        finishedAt = obj.optLong("finishedAt", 0L)
                    )
                )
            }
            _jobHistory.value = loaded
            // recordedJobIds is deliberately not seeded from history. The native server numbers
            // jobs from 1 again every time the process starts, so a saved "#1" from yesterday
            // would make today's job #1 look already recorded and it would never show up.
            Log.i(TAG, "Loaded ${loaded.size} job history entr${if (loaded.size == 1) "y" else "ies"} from storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load job history: ${e.message}")
        }
    }

    private fun saveJobHistory(context: Context) {
        try {
            val prefs = context.getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray()
            for (entry in _jobHistory.value) {
                jsonArray.put(JSONObject().apply {
                    put("jobId", entry.jobId)
                    put("jobName", entry.jobName)
                    put("printerName", entry.printerName)
                    put("printerUri", entry.printerUri)
                    put("user", entry.user)
                    put("state", entry.state)
                    put("sizeBytes", entry.sizeBytes)
                    put("finishedAt", entry.finishedAt)
                })
            }
            prefs.edit().putString(KEY_JOB_HISTORY, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save job history: ${e.message}")
        }
    }

    /**
     * Clear all persisted job history (does not affect the live/active job queue).
     */
    fun clearJobHistory() {
        _jobHistory.value = emptyList()
        context?.let { ctx ->
            ctx.getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        }
        Log.i(TAG, "Job history cleared")
    }

    // NOTE ON CANCELLATION: printFile() deliberately runs its body on `scope` (this repository's
    // own process-lifetime CoroutineScope) via async{}.await(), rather than directly in the
    // caller's coroutine context. TestPrintSheet launches this via rememberCoroutineScope(),
    // which is cancelled the moment the composable leaves composition (e.g. the user dismisses
    // the sheet before the ~10s printer round-trip finishes) — that used to silently cancel the
    // whole suspend function, including the history-recording code below, even though the native
    // print had already fully succeeded. Since `scope`'s job is NOT a structural child of the
    // caller's job, cancelling the caller only cancels its own await(); the print + history write
    // keep running to completion regardless of whether anything is still listening.
    suspend fun printFile(
        uri: String,
        filePath: String,
        jobTitle: String = "Cuppa Print Job",
        options: Map<String, String> = emptyMap()
    ): Int = scope.async {
        val printerName = _printers.value.firstOrNull { it.uri == uri }?.name ?: uri
        val sizeBytes = runCatching { File(filePath).length() }.getOrDefault(0L)

        // The native call below is one blocking round-trip with no incremental progress — a
        // negative-jobId placeholder is the only way to make this dispatch visible in the Jobs
        // tab's "Active" section while it's in flight, since it never touches the local server's
        // own job queue (that's only for jobs OTHER devices send TO Cuppa). Negative ids can't
        // collide with real (always-positive) job ids from either the local queue or a printer.
        val placeholderId = -(System.nanoTime() and 0x7FFFFFFFL).toInt().coerceAtLeast(1)
        val placeholder = PrintJob(
            jobId = placeholderId,
            jobName = jobTitle,
            printerUri = uri,
            user = "Cuppa",
            documentFormat = "application/octet-stream",
            state = PrintJobStatus.PROCESSING.code,
            sizeBytes = sizeBytes,
            createdAt = System.currentTimeMillis(),
            spoolFilePath = filePath
        )
        _jobs.value = _jobs.value + placeholder

        val jobId = try {
            CupsEngine.printFile(uri, filePath, jobTitle, options)
        } finally {
            _jobs.value = _jobs.value.filterNot { it.jobId == placeholderId }
        }

        // Record this outgoing (client-role) job directly rather than relying on re-querying the
        // target printer's own Get-Jobs afterward: most consumer printers (the Epson included)
        // don't reliably support Get-Jobs, so that round-trip silently returns nothing and the
        // job never makes it into history. The JNI call's own success/failure is the only signal
        // this client actually has, so use it directly.
        if (jobId > 0 && jobId !in recordedJobIds) {
            recordedJobIds.add(jobId)
            appendHistoryEntry(
                JobHistoryEntry(
                    jobId = jobId,
                    jobName = jobTitle,
                    printerName = printerName,
                    printerUri = uri,
                    user = "Cuppa",
                    state = 9, // COMPLETED — accepted by the printer's IPP server
                    sizeBytes = sizeBytes,
                    finishedAt = System.currentTimeMillis()
                )
            )
        } else if (jobId <= 0) {
            appendHistoryEntry(
                JobHistoryEntry(
                    jobId = -System.currentTimeMillis().toInt(),
                    jobName = jobTitle,
                    printerName = printerName,
                    printerUri = uri,
                    user = "Cuppa",
                    state = 8, // ABORTED
                    sizeBytes = sizeBytes,
                    finishedAt = System.currentTimeMillis()
                )
            )
        }

        if (jobId > 0) {
            refreshJobs(uri)
        }
        jobId
    }.await()

    private fun appendHistoryEntry(entry: JobHistoryEntry) {
        _jobHistory.value = (listOf(entry) + _jobHistory.value).take(MAX_HISTORY_ENTRIES)
        context?.let { saveJobHistory(it) }
    }

    // ---- Printer Management ----

    /**
     * Add a printer to the managed list.
     * Deduplicates by URI — if a printer with the same URI exists, it is replaced.
     */
    fun addPrinter(printer: PrinterInfo) {
        val current = _printers.value.toMutableList()
        current.removeAll { it.uri == printer.uri }
        current.add(printer)
        _printers.value = current
        CupsEngine.addPrinterToNative(printer)
        Log.i(TAG, "Added printer: ${printer.name} (${printer.uri}) and synced to native engine")
    }

    /**
     * Remove a printer from the managed list by URI.
     */
    fun removePrinter(uri: String) {
        val current = _printers.value.toMutableList()
        val printerToRemove = current.find { it.uri == uri }
        val removed = current.removeAll { it.uri == uri }
        _printers.value = current
        if (removed) {
            if (printerToRemove != null) {
                CupsEngine.removePrinterFromNative(printerToRemove.name)
            }
            Log.i(TAG, "Removed printer: $uri from managed list and native engine")
        }
    }

    /**
     * Persist the current printer list to SharedPreferences as JSON.
     */
    fun savePrinters(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray()
            for (printer in _printers.value) {
                val obj = JSONObject().apply {
                    put("name", printer.name)
                    put("uri", printer.uri)
                    put("makeAndModel", printer.makeAndModel)
                    put("location", printer.location)
                    put("info", printer.info)
                    put("isDefault", printer.isDefault)
                    put("isAcceptingJobs", printer.isAcceptingJobs)
                    put("state", printer.state)
                    put("colorSupported", printer.colorSupported)
                    val formats = JSONArray()
                    printer.supportedFormats.forEach { formats.put(it) }
                    put("supportedFormats", formats)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_PRINTERS, jsonArray.toString()).apply()
            Log.i(TAG, "Saved ${_printers.value.size} printer(s) to storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save printers: ${e.message}")
        }
    }

    /**
     * Load persisted printers from SharedPreferences.
     */
    fun loadPrinters(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_PRINTERS, null) ?: return
            val jsonArray = JSONArray(json)
            val loaded = mutableListOf<PrinterInfo>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val formats = mutableListOf<String>()
                val formatsArray = obj.optJSONArray("supportedFormats")
                if (formatsArray != null) {
                    for (j in 0 until formatsArray.length()) {
                        formats.add(formatsArray.getString(j))
                    }
                }
                loaded.add(
                    PrinterInfo(
                        name = obj.getString("name"),
                        uri = obj.getString("uri"),
                        makeAndModel = obj.optString("makeAndModel", ""),
                        location = obj.optString("location", ""),
                        info = obj.optString("info", ""),
                        isDefault = obj.optBoolean("isDefault", false),
                        isAcceptingJobs = obj.optBoolean("isAcceptingJobs", true),
                        state = obj.optInt("state", 3),
                        colorSupported = obj.optBoolean("colorSupported", false),
                        supportedFormats = formats
                    )
                )
            }
            // Merge with existing: keep any that were added at runtime
            val currentUris = _printers.value.map { it.uri }.toSet()
            val merged = _printers.value.toMutableList()
            for (printer in loaded) {
                if (printer.uri !in currentUris) {
                    merged.add(printer)
                }
            }
            _printers.value = merged
            // Sync all to native engine
            for (printer in merged) {
                CupsEngine.addPrinterToNative(printer)
            }
            Log.i(TAG, "Loaded ${loaded.size} printer(s) from storage, total ${merged.size} synced to native engine")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load printers: ${e.message}")
        }
    }

    /**
     * Push all currently managed printers to the native C++ CUPS engine.
     */
    fun syncAllPrintersToNative() {
        for (printer in _printers.value) {
            CupsEngine.addPrinterToNative(printer)
        }
        Log.i(TAG, "Synchronized ${_printers.value.size} printer(s) to native CUPS engine")
    }
}
