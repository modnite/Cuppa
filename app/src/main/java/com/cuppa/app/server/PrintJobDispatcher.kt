package com.cuppa.app.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.cuppa.app.util.CuppaLog as Log
import com.cuppa.app.backend.usb.UsbPrinterBackend
import com.cuppa.app.data.CupsRepository
import com.cuppa.app.data.thermal.ThermalPreferences
import com.cuppa.cups.CupsEngine
import com.cuppa.cups.PrintJob
import com.cuppa.cups.PrintJobStatus
import com.cuppa.cups.PrinterInfo
import com.cuppa.cups.thermal.EplDriver
import com.cuppa.cups.thermal.EscPosDriver
import com.cuppa.cups.thermal.PclDriver
import com.cuppa.cups.thermal.TsplDriver
import com.cuppa.cups.thermal.ZplDriver
import kotlinx.coroutines.*
import java.io.File

/**
 * PrintJobDispatcher — Polls the local CupsEngine for pending spool jobs and dispatches
 * them to their physical destinations (direct USB thermal, ESC/POS, or network IPP).
 */
class PrintJobDispatcher(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "PrintJobDispatcher"
        private const val POLL_INTERVAL_MS = 1500L
    }

    private var pollJob: Job? = null
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val usbBackend = UsbPrinterBackend(context)
    private val cupsRepo = CupsRepository.getInstance(context)
    private val thermalPrefs = ThermalPreferences(context)

    fun start() {
        if (pollJob != null) return
        Log.i(TAG, "Starting PrintJobDispatcher")
        pollJob = scope.launch {
            while (isActive) {
                try {
                    pollAndDispatch()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in dispatch loop", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping PrintJobDispatcher")
        pollJob?.cancel()
        pollJob = null
        scope.coroutineContext.cancelChildren()
    }

    private suspend fun pollAndDispatch() = withContext(dispatcher) {
        val jobs = CupsEngine.getJobs()
        val pendingJobs = jobs.filter { it.status == PrintJobStatus.PENDING }

        for (job in pendingJobs) {
            Log.i(TAG, "Found pending job #${job.jobId} for ${job.printerUri} (${job.sizeBytes} bytes)")
            CupsEngine.updateJobState(job.jobId, PrintJobStatus.PROCESSING.code)
            // Push the PROCESSING transition to the Jobs tab immediately rather than waiting for
            // its own independent poll cycle to happen to land during the dispatch window —
            // dispatch (especially a network relay) can take several seconds, but without this
            // the state change is invisible until this job is already done.
            cupsRepo.refreshJobs()

            val spoolFile = File(job.spoolFilePath)
            if (!spoolFile.exists() || spoolFile.length() == 0L) {
                Log.e(TAG, "Spool file missing or empty for job #${job.jobId}: ${job.spoolFilePath}")
                CupsEngine.updateJobState(job.jobId, PrintJobStatus.ABORTED.code)
                continue
            }

            // Find matching printer
            val targetPrinter = findTargetPrinter(job.printerUri)
            val success = if (targetPrinter != null) {
                dispatchJob(job, targetPrinter, spoolFile)
            } else {
                Log.w(TAG, "No matching printer found for ${job.printerUri}, attempting generic fallback")
                dispatchFallback(spoolFile)
            }

            val finalState = if (success) PrintJobStatus.COMPLETED else PrintJobStatus.ABORTED
            CupsEngine.updateJobState(job.jobId, finalState.code)
            Log.i(TAG, "Job #${job.jobId} finished with state: $finalState")
            cupsRepo.refreshJobs()
        }
    }

    private fun findTargetPrinter(printerUriOrName: String): PrinterInfo? {
        val printers = cupsRepo.printers.value
        return printers.find { it.name.equals(printerUriOrName, ignoreCase = true) }
            ?: printers.find { it.uri.equals(printerUriOrName, ignoreCase = true) }
            ?: printers.find { it.isDefault }
            ?: printers.firstOrNull()
    }

    private suspend fun dispatchJob(job: PrintJob, printer: PrinterInfo, spoolFile: File): Boolean {
        return if (printer.uri.startsWith("usb", ignoreCase = true)) {
            dispatchUsbJob(job, printer, spoolFile)
        } else if (printer.uri.startsWith("ipp://", ignoreCase = true) || printer.uri.startsWith("http://", ignoreCase = true)) {
            dispatchNetworkIppJob(job, printer, spoolFile)
        } else {
            Log.w(TAG, "Unsupported transport scheme in printer URI: ${printer.uri}")
            false
        }
    }

    private suspend fun dispatchUsbJob(job: PrintJob, printer: PrinterInfo, spoolFile: File): Boolean {
        val device = usbBackend.findDeviceByUri(printer.uri)
        if (device == null) {
            Log.e(TAG, "USB printer device not found for URI: ${printer.uri}")
            return false
        }

        val rawBytes = spoolFile.readBytes()
        if (rawBytes.isEmpty()) return false

        // Check if payload is a PDF document
        val isPdf = rawBytes.size > 4 &&
                rawBytes[0] == '%'.code.toByte() &&
                rawBytes[1] == 'P'.code.toByte() &&
                rawBytes[2] == 'D'.code.toByte() &&
                rawBytes[3] == 'F'.code.toByte()

        return if (isPdf) {
            renderAndPrintPdfToUsb(device, printer, spoolFile, job.copies.coerceAtLeast(1))
        } else {
            // Already raw printer language (ZPL, ESC/POS, EPL, TSPL, PCL)
            Log.i(TAG, "Streaming raw print stream (${rawBytes.size} bytes) directly to USB printer")
            var ok = true
            repeat(job.copies.coerceAtLeast(1)) { if (ok) ok = usbBackend.sendRawBytes(device, rawBytes).isSuccess }
            ok
        }
    }

    private suspend fun renderAndPrintPdfToUsb(
        device: android.hardware.usb.UsbDevice,
        printer: PrinterInfo,
        spoolFile: File,
        copies: Int = 1
    ): Boolean {
        Log.i(TAG, "Rendering PDF pages for thermal printing to ${printer.name} ($copies cop${if (copies == 1) "y" else "ies"})")
        val tp = thermalPrefs.settings.value
        return try {
            val pfd = ParcelFileDescriptor.open(spoolFile, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(pfd).use { renderer ->
                val pageCount = renderer.pageCount
                val ident = "${printer.name} ${printer.makeAndModel} ${printer.uri}".lowercase()

                // Classify by explicit make/model keywords. Anything that doesn't match a known
                // thermal/label language falls through to PCL raster (see PclDriver) rather than
                // silently defaulting to a label-printer language it can't understand — a plain
                // USB laser/inkjet printer sent ZPL or TSPL would accept the bytes and print
                // nothing, with no error surfaced anywhere.
                val isEscPos = "esc" in ident || "receipt" in ident || "pos" in ident
                // 0x09c5 is the confirmed USB vendor ID of the Rollo X1038 (reports itself only as a generic
                // "Printer", so its name never contains "rollo"); it lives in the usb:// URI.
                val isRollo = "rollo" in ident || "1038" in ident || "tspl" in ident || "0x09c5" in ident
                val isZebra = !isRollo && ("zebra" in ident || "zpl" in ident || "zd4" in ident || "gk4" in ident)
                val isEltron = "eltron" in ident || "epl" in ident || "lp2844" in ident || "tlp2844" in ident

                for (pageIndex in 0 until pageCount) {
                    val page = renderer.openPage(pageIndex)
                    // Thermal/label formats render at a small fixed width matching their DPI;
                    // a generic PCL laser/inkjet page is rendered near a real 300dpi Letter page.
                    val width = when {
                        isEscPos -> 576 // 80mm @ 203dpi
                        isRollo || isZebra || isEltron -> 812 // 4" @ 203dpi
                        else -> 2550 // 8.5" @ 300dpi
                    }
                    val aspectRatio = page.height.toFloat() / page.width.toFloat()
                    val height = (width * aspectRatio).toInt().coerceAtLeast(100)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()

                    // Apply the user's Thermal Settings (darkness/speed/dither/invert) to whichever
                    // driver handles this printer. Darkness scales are 0-30 (ZPL/EPL "D") vs 0-15
                    // (TSPL "DENSITY") on the same physical dot range, so TSPL gets it halved.
                    // Invert-polarity is XORed against each format's own hardware-required base
                    // polarity (TSPL/EPL need inverted bits to represent black at the protocol
                    // level regardless of user preference) so the toggle means "flip the visual
                    // result" rather than accidentally cancelling out a required format constant.
                    val pagePayload = when {
                        isEscPos -> EscPosDriver(characterWidth = 48)
                            .printImage(bitmap, ditherMode = tp.ditherMode, invertPolarity = tp.invertPolarity)
                            .feed(2)
                            .cutPaper(partial = true)
                            .build()
                        isRollo -> TsplDriver.fromBitmap(
                            TsplDriver.centerOnPrintHead(bitmap),
                            density = (tp.darkness / 2).coerceIn(0, 15),
                            speed = tp.speedIps,
                            ditherMode = tp.ditherMode,
                            invertPolarity = true xor tp.invertPolarity
                        )
                        isZebra -> ZplDriver(widthDots = width, lengthDots = height)
                            .setDarkness(tp.darkness)
                            .setPrintSpeed(tp.speedIps)
                            .drawBitmap(0, 0, bitmap, ditherMode = tp.ditherMode, invertPolarity = tp.invertPolarity)
                            .build()
                        isEltron -> EplDriver(widthDots = width, lengthDots = height)
                            .setDensity((tp.darkness / 2).coerceIn(0, 15))
                            .setSpeed(tp.speedIps.coerceIn(1, 5))
                            .drawBitmap(0, 0, bitmap, ditherMode = tp.ditherMode, invertPolarity = true xor tp.invertPolarity)
                            .build()
                        else -> PclDriver.fromBitmap(bitmap, dpi = 300, ditherMode = tp.ditherMode)
                    }

                    repeat(copies) {
                        val sendRes = usbBackend.sendRawBytes(device, pagePayload)
                        if (sendRes.isFailure) {
                            Log.e(TAG, "Failed sending page $pageIndex: ${sendRes.exceptionOrNull()?.message}")
                            return false
                        }
                    }
                }
            }
            pfd.close()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to render and print PDF over USB", e)
            false
        }
    }

    private suspend fun dispatchNetworkIppJob(job: PrintJob, printer: PrinterInfo, spoolFile: File): Boolean {
        Log.i(TAG, "Forwarding spooled IPP job #${job.jobId} to network printer ${printer.uri}")

        // If the target only understands PWG-Raster (no PDF interpreter on board — the common
        // case for consumer inkjets/lasers), sending it raw PDF gets accepted at the IPP layer
        // but silently produces nothing. Convert first, same as a real AirPrint client would.
        val rawHeader = spoolFile.inputStream().use { it.readNBytes(4) }
        val isPdf = rawHeader.size == 4 && rawHeader[0] == '%'.code.toByte() && rawHeader[1] == 'P'.code.toByte() &&
                rawHeader[2] == 'D'.code.toByte() && rawHeader[3] == 'F'.code.toByte()
        val supportsPwgRaster = printer.supportedFormats.any { it.equals("image/pwg-raster", ignoreCase = true) }
        val supportsPdf = printer.supportedFormats.any { it.equals("application/pdf", ignoreCase = true) }

        var fileToSend = spoolFile
        var convertedFile: File? = null
        if (isPdf && supportsPwgRaster && !supportsPdf) {
            val rasterFile = File(spoolFile.parentFile, "${spoolFile.nameWithoutExtension}.ras")
            if (PwgRasterConverter.convertAllPagesToPwgRaster(spoolFile, rasterFile, printer.colorSupported)) {
                fileToSend = rasterFile
                convertedFile = rasterFile
                Log.i(TAG, "Converted job #${job.jobId} PDF to PWG-Raster for ${printer.name}")
            } else {
                Log.w(TAG, "PDF->PWG-Raster conversion failed for job #${job.jobId}, sending raw PDF (may not print)")
            }
        }

        // The client's copies count travels with the job; the target printer does the duplication.
        val copies = job.copies.coerceAtLeast(1)
        val resultJobId = CupsEngine.printFile(
            uri = printer.uri,
            filePath = fileToSend.absolutePath,
            jobTitle = job.jobName.ifBlank { "Cuppa Network Print" },
            options = if (copies > 1) mapOf("copies" to copies.toString()) else emptyMap()
        )
        convertedFile?.delete()
        return resultJobId > 0
    }

    private suspend fun dispatchFallback(spoolFile: File): Boolean {
        // If a USB printer is connected, send directly
        val rawBytes = spoolFile.readBytes()
        if (rawBytes.isEmpty()) return false
        val manager = context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager ?: return false
        val device = manager.deviceList.values.firstOrNull() ?: return false
        return usbBackend.sendRawBytes(device, rawBytes).isSuccess
    }
}
