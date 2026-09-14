package com.modnite.cuppa

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class RawSocketServer(
    private val context: Context,
    private val printManager: UsbPrintManager,
    private val jobQueueManager: JobQueueManager,
    private val logger: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val serverExecutor = Executors.newCachedThreadPool()

    companion object {
        const val RAW_PORT = 9100
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        logger("[RAW_9100] Server starting on Port $RAW_PORT...")

        serverExecutor.execute {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(RAW_PORT))
                }
                logger("[RAW_9100] Listening for AppSocket/JetDirect print streams on port $RAW_PORT...")

                while (isRunning && serverSocket?.isClosed == false) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        handleRawClientSocket(clientSocket)
                    } catch (e: Exception) {
                        if (isRunning && serverSocket?.isClosed == false) {
                            logger("[RAW_9100] Accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                logger("[RAW_9100] Failed to bind port $RAW_PORT: ${e.message}")
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        logger("[RAW_9100] Server stopped.")
    }

    private fun handleRawClientSocket(socket: Socket) {
        val clientIp = socket.inetAddress?.hostAddress ?: "Unknown"
        Executors.newSingleThreadExecutor().execute {
            try {
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                val baos = ByteArrayOutputStream()
                val buffer = ByteArray(8192)

                while (isRunning) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    baos.write(buffer, 0, read)
                }

                val data = baos.toByteArray()
                if (data.isNotEmpty()) {
                    val jobId = JobQueueManager.getNextJobId()
                    val dataStr = String(data, 0, Math.min(data.size, 512), Charsets.US_ASCII).lowercase()
                    val osFamily = when {
                        dataStr.contains("mac") || dataStr.contains("apple") || dataStr.contains("darwin") -> "macos"
                        dataStr.contains("win") || dataStr.contains("microsoft") -> "windows"
                        dataStr.contains("linux") || dataStr.contains("cups") -> "linux"
                        else -> "raw_client"
                    }
                    logger("[RAW_9100] Received ${data.size} bytes from $clientIp [$osFamily] for Job #$jobId")
                    processRawPayload(data, jobId, clientIp, osFamily)
                }

                try { socket.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                logger("[RAW_9100] Error handling client $clientIp: ${e.message}")
            }
        }
    }

    private fun processRawPayload(data: ByteArray, jobId: Int, clientIp: String, osFamily: String) {
        val tempPdfFile = File(context.cacheDir, "temp_raw_$jobId.pdf")
        try {
            val pdfHeader = "%PDF-".toByteArray()
            val pdfStart = findByteSequence(data, pdfHeader)
            if (pdfStart != -1) {
                val pdfBytes = data.copyOfRange(pdfStart, data.size)
                FileOutputStream(tempPdfFile).use { it.write(pdfBytes) }
                ingestPdfToLocalPrint(tempPdfFile, jobId, clientIp, osFamily)
                return
            }

            // Fallback: Save raw data directly
            FileOutputStream(tempPdfFile).use { it.write(data) }
            ingestPdfToLocalPrint(tempPdfFile, jobId, clientIp, osFamily)
        } catch (e: Exception) {
            logger("[RAW_9100] ERROR processing raw stream #$jobId: ${e.message}")
        } finally {
            try { tempPdfFile.delete() } catch (_: Exception) {}
        }
    }

    private fun ingestPdfToLocalPrint(pdfFile: File, jobId: Int, clientIp: String, osFamily: String) {
        val uri = Uri.fromFile(pdfFile)
        val bitmap = printManager.renderPdfToBitmap(uri)
        if (bitmap != null) {
            PrintHistoryCacheManager.saveJobScreenshot(context, bitmap, jobId, "raw_network", osFamily)
            logger("[RAW_9100] Stream converted to 816x1218 bitmap. Adding RAW Job #$jobId [$osFamily] to Print Queue...")
            jobQueueManager.addJob(bitmap, "RAW Job #$jobId ($osFamily)", false, jobId)
        } else {
            logger("[RAW_9100] ERROR: Failed to render bitmap for RAW Job #$jobId")
        }
    }

    private fun findByteSequence(data: ByteArray, seq: ByteArray): Int {
        for (i in 0..data.size - seq.size) {
            var match = true
            for (j in seq.indices) {
                if (data[i + j] != seq[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
