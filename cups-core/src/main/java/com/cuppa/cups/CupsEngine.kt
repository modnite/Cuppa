package com.cuppa.cups

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Information about an IPP printer.
 */
data class PrinterInfo(
    val name: String,
    val uri: String,
    val makeAndModel: String = "",
    val location: String = "",
    val info: String = "",
    val isDefault: Boolean = false,
    val isAcceptingJobs: Boolean = true,
    val state: Int = 3, // 3 = idle, 4 = processing, 5 = stopped
    val supportedFormats: List<String> = emptyList(),
    val colorSupported: Boolean = false
) {
    val stateName: String
        get() = when (state) {
            3 -> "Idle"
            4 -> "Processing"
            5 -> "Stopped"
            else -> "Unknown ($state)"
        }
}

/**
 * Information about a print job.
 */
data class PrintJob(
    val jobId: Int,
    val jobName: String,
    val printerUri: String,
    val user: String,
    val documentFormat: String,
    val state: Int = 9, // 3 = pending, 5 = processing, 7 = canceled, 9 = completed
    val sizeBytes: Long = 0,
    val createdAt: Long = 0,
    val spoolFilePath: String = "",
    val copies: Int = 1,
    /** Forwardable job-template attributes from the client, one "name=value" per line. */
    val options: String = ""
) {
    val status: PrintJobStatus
        get() = PrintJobStatus.fromCode(state)
}

/**
 * Standard IPP job states (RFC 8011).
 */
enum class PrintJobStatus(val code: Int, val displayName: String) {
    PENDING(3, "Pending"),
    HELD(4, "Held"),
    PROCESSING(5, "Processing"),
    STOPPED(6, "Stopped"),
    CANCELED(7, "Canceled"),
    ABORTED(8, "Aborted"),
    COMPLETED(9, "Completed");

    companion object {
        fun fromCode(code: Int): PrintJobStatus =
            entries.find { it.code == code } ?: PENDING
    }
}

/**
 * CupsEngine — Kotlin interface to the native CUPS client library and IPP subsystem.
 *
 * This object loads `libcuppa_native.so` and exposes coroutine-friendly APIs for:
 * - Querying CUPS version and server lifecycle
 * - Querying IPP printer attributes directly
 * - Submitting print jobs via IPP (using cupsDoFileRequest)
 * - Monitoring active and completed print jobs
 */
object CupsEngine {

    private const val TAG = "CupsEngine"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("cuppa_native")
            isLoaded = true
            try {
                Log.i(TAG, "Native library loaded successfully: ${getVersion()}")
            } catch (_: Throwable) {}
        } catch (e: Throwable) {
            isLoaded = false
            try {
                Log.e(TAG, "Failed to load native library: ${e.message}")
            } catch (_: Throwable) {}
        }
    }

    /**
     * Returns the native library version string.
     */
    fun getVersion(): String {
        return if (isLoaded) {
            nativeGetVersion()
        } else {
            "Native library not loaded"
        }
    }

    /**
     * Start the local CUPS server / IPP subsystem.
     *
     * @param port The port to listen on (default: 631 for IPP).
     * @param configPath Path to the configuration directory.
     * @return true if startup succeeded.
     */
    fun startServer(port: Int = 631, configPath: String): Boolean {
        if (!isLoaded) {
            Log.e(TAG, "Cannot start server: native library not loaded")
            return false
        }
        return nativeStartServer(port, configPath)
    }

    /**
     * Stop the local CUPS server / IPP subsystem.
     */
    fun stopServer() {
        if (!isLoaded) {
            Log.e(TAG, "Cannot stop server: native library not loaded")
            return
        }
        nativeStopServer()
    }

    /**
     * Check if the server is currently running.
     */
    fun isServerRunning(): Boolean {
        if (!isLoaded) return false
        return nativeIsServerRunning()
    }

    /**
     * Query printer attributes via direct IPP connection.
     *
     * @param uri The IPP URI (e.g. "ipp://192.168.1.100:631/ipp/print").
     * @return [PrinterInfo] if reachable and queried successfully, null otherwise.
     */
    suspend fun getPrinterAttributes(uri: String): PrinterInfo? = withContext(Dispatchers.IO) {
        if (!isLoaded) {
            Log.e(TAG, "Cannot query printer: native library not loaded")
            return@withContext null
        }
        try {
            nativeGetPrinterAttributes(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Exception querying printer attributes for $uri", e)
            null
        }
    }

    /**
     * Submit a file as a print job to an IPP printer.
     *
     * @param uri The printer IPP URI.
     * @param filePath Absolute path to the file on local disk (PDF, image, etc.).
     * @param jobTitle Display title of the job.
     * @param options IPP job options (e.g. copies, sides, media).
     * @return The job ID assigned by the printer, or -1 on error.
     */
    suspend fun printFile(
        uri: String,
        filePath: String,
        jobTitle: String = "Cuppa Print Job",
        options: Map<String, String> = emptyMap()
    ): Int {
        if (!isLoaded) {
            Log.e(TAG, "Cannot print file: native library not loaded")
            return -1
        }
        // nativePrintFile is a blocking, non-suspending JNI call into libcups. Wrapping it in
        // withTimeoutOrNull directly would NOT work: coroutine cancellation is cooperative and is
        // only checked at suspension points, but a blocking native call never suspends or yields
        // back to the dispatcher, so the calling thread stays stuck inside it regardless of what
        // the timeout job does. To get a real, enforceable timeout, the blocking call has to run
        // on its own dedicated thread that can simply be abandoned — the coroutine then only
        // awaits a CompletableDeferred (a genuine suspension point), which withTimeoutOrNull *can*
        // actually cancel. The abandoned thread keeps running until the native call eventually
        // returns on its own; it isn't forcibly killed, but the caller/UI is no longer blocked
        // waiting on it.
        val deferred = CompletableDeferred<Int>()
        val thread = Thread({
            val result = try {
                nativePrintFile(uri, filePath, jobTitle, options)
            } catch (e: Exception) {
                Log.e(TAG, "Exception submitting print job to $uri", e)
                -1
            }
            deferred.complete(result)
        }, "CupsEngine-printFile").apply {
            isDaemon = true
            start()
        }

        // Must stay comfortably above nativePrintFile's own 90s socket timeout (cups_jni.cpp) —
        // a large uncompressed color raster page can legitimately take most of that to transfer
        // and be processed by the printer; a shorter guard here would report a real, eventually-
        // successful print as a failure before the native timeout even had a chance to apply.
        val result = withTimeoutOrNull(120_000L) { deferred.await() }
        if (result == null) {
            Log.e(TAG, "Timed out waiting for printFile response from $uri after 120s " +
                "(native call is still running on thread '${thread.name}' in the background)")
        }
        return result ?: -1
    }

    /**
     * Query jobs from a printer or the local spool.
     *
     * @param uri Optional printer URI to query. Pass empty string to query local spool.
     * @return List of [PrintJob]s.
     */
    suspend fun getJobs(uri: String = ""): List<PrintJob> = withContext(Dispatchers.IO) {
        if (!isLoaded) {
            Log.e(TAG, "Cannot get jobs: native library not loaded")
            return@withContext emptyList()
        }
        try {
            val jobs = nativeGetJobs(uri)
            jobs?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting jobs for $uri", e)
            emptyList()
        }
    }

    /**
     * Process an incoming IPP request payload through the native CupsServer engine.
     * @param request IPP byte array payload from the client.
     * @return IPP byte array payload to send back to the client.
     */
    suspend fun processIppRequest(request: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        if (!isLoaded) return@withContext null
        try {
            nativeProcessIppRequest(request)
        } catch (e: Exception) {
            Log.e(TAG, "Exception processing IPP request", e)
            null
        }
    }

    /**
     * Update the state of a print job in the native engine.
     * @param jobId The job ID.
     * @param state The new state code.
     */
    fun updateJobState(jobId: Int, state: Int) {
        if (!isLoaded) return
        try {
            nativeUpdateJobState(jobId, state)
        } catch (e: Exception) {
            Log.e(TAG, "Exception updating job state", e)
        }
    }

    /**
     * Register a printer in the native CUPS server.
     */
    fun addPrinterToNative(printer: PrinterInfo): Boolean {
        if (!isLoaded) return false
        return try {
            nativeAddPrinter(printer)
        } catch (e: Exception) {
            Log.e(TAG, "Exception adding printer to native engine: ${e.message}", e)
            false
        }
    }

    /**
     * Remove a printer from the native CUPS server.
     */
    fun removePrinterFromNative(name: String): Boolean {
        if (!isLoaded) return false
        return try {
            nativeRemovePrinter(name)
        } catch (e: Exception) {
            Log.e(TAG, "Exception removing printer from native engine: ${e.message}", e)
            false
        }
    }

    /**
     * Clear all printers from the native CUPS server.
     */
    fun clearNativePrinters() {
        if (!isLoaded) return
        try {
            nativeClearPrinters()
        } catch (e: Exception) {
            Log.e(TAG, "Exception clearing native printers: ${e.message}", e)
        }
    }

    /**
     * Encode a single rendered page as a PWG-Raster document, using the real CUPS raster writer
     * linked into this library — the format IPP Everywhere / AirPrint mandates, and what most
     * real network printers understand even when they have no PDF interpreter on board.
     *
     * @param rgbPixels Packed top-to-bottom rows, no padding: 3 bytes/pixel (R,G,B) if
     *                  [colorMode], else 1 byte/pixel (gray). Must be exactly width*height*bpp.
     * @param outputPath Destination file for the encoded page.
     * @return true on success. Single-page only — see the native implementation for why.
     */
    suspend fun encodePwgRasterPage(
        rgbPixels: ByteArray,
        width: Int,
        height: Int,
        dpi: Int,
        colorMode: Boolean,
        outputPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isLoaded) return@withContext false
        try {
            nativeEncodePwgRasterPage(rgbPixels, width, height, dpi, colorMode, outputPath)
        } catch (e: Exception) {
            Log.e(TAG, "Exception encoding PWG raster page", e)
            false
        }
    }

    /**
     * Writes a multi-page PWG-Raster document. [writePages] receives an appender it calls once per
     * page, in order, with packed rows (3 bytes/pixel if [colorMode], else 1). Returns false if the
     * writer could not be opened or any page failed to encode.
     */
    suspend fun encodePwgRasterDocument(
        outputPath: String,
        colorMode: Boolean,
        writePages: suspend (addPage: (pixels: ByteArray, width: Int, height: Int, dpi: Int) -> Boolean) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isLoaded) return@withContext false
        val handle = try { nativeBeginPwgRaster(outputPath) } catch (e: Exception) { 0L }
        if (handle == 0L) return@withContext false
        try {
            writePages { pixels, w, h, dpi -> nativeAddPwgRasterPage(handle, pixels, w, h, dpi, colorMode) }
        } finally {
            nativeEndPwgRaster(handle)
        }
    }

    /** Tell the IPP replies whether plain IPP is refused, so they advertise ipps:// and tls. */
    fun setTlsRequired(required: Boolean) {
        if (!isLoaded) return
        try {
            nativeSetTlsRequired(required)
        } catch (e: Exception) {
            Log.e(TAG, "Exception setting TLS mode: ${e.message}", e)
        }
    }

    /**
     * Set the local IP / hostname of the server for dynamic IPP URI attribute generation.
     */
    fun setServerHost(host: String) {
        if (!isLoaded) return
        try {
            nativeSetServerHost(host)
        } catch (e: Exception) {
            Log.e(TAG, "Exception setting server host: ${e.message}", e)
        }
    }

    /**
     * Whether the native library was loaded successfully.
     */
    fun isNativeLoaded(): Boolean = isLoaded

    // ---- JNI declarations ----

    private external fun nativeGetVersion(): String
    private external fun nativeStartServer(port: Int, configPath: String): Boolean
    private external fun nativeStopServer()
    private external fun nativeIsServerRunning(): Boolean
    private external fun nativeGetPrinterAttributes(uri: String): PrinterInfo?
    private external fun nativePrintFile(uri: String, filePath: String, jobTitle: String, options: Map<String, String>): Int
    private external fun nativeGetJobs(uri: String): Array<PrintJob>?
    private external fun nativeProcessIppRequest(request: ByteArray): ByteArray?
    private external fun nativeUpdateJobState(jobId: Int, state: Int)
    private external fun nativeAddPrinter(printer: PrinterInfo): Boolean
    private external fun nativeRemovePrinter(name: String): Boolean
    private external fun nativeClearPrinters()
    private external fun nativeSetServerHost(host: String)
    private external fun nativeSetTlsRequired(required: Boolean)
    private external fun nativeBeginPwgRaster(outputPath: String): Long
    private external fun nativeAddPwgRasterPage(handle: Long, pixels: ByteArray, width: Int, height: Int, dpi: Int, colorMode: Boolean): Boolean
    private external fun nativeEndPwgRaster(handle: Long)
    private external fun nativeEncodePwgRasterPage(
        rgbPixels: ByteArray,
        width: Int,
        height: Int,
        dpi: Int,
        colorMode: Boolean,
        outputPath: String
    ): Boolean
}
