package com.modnite.cuppa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.IntOrIntRange
import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.MutableAttributeGroup
import com.hp.jipp.encoding.Resolution
import com.hp.jipp.encoding.ResolutionUnit
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Finishing
import com.hp.jipp.model.JobState
import com.hp.jipp.model.MediaCol
import com.hp.jipp.model.MediaColDatabase
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Orientation
import com.hp.jipp.model.PrintQuality
import com.hp.jipp.model.PrinterState
import com.hp.jipp.model.PrinterStateReason
import com.hp.jipp.model.Status
import com.hp.jipp.model.Types
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class IppServer(
    private val context: Context,
    private val printManager: UsbPrintManager,
    private val jobQueueManager: JobQueueManager,
    private val logger: (String) -> Unit,
    private val onStatusChanged: (Boolean, String?) -> Unit,
    private val onNetworkBitmapRendered: ((Bitmap) -> Unit)? = null
) {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val serverExecutor = Executors.newCachedThreadPool()
    @Volatile
    private var lastCreatedJobId: Int = 0

    companion object {
        const val PORT = 8631
    }

    fun start() {
        val ipAddress = getLocalIpAddress()
        if (isRunning) {
            onStatusChanged(true, ipAddress)
            return
        }
        isRunning = true
        logger("[IPP] Server starting on $ipAddress:$PORT...")
        onStatusChanged(true, ipAddress)

        serverExecutor.execute {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                logger("[IPP] Listening for IPP Everywhere print jobs on port $PORT...")

                while (isRunning && serverSocket?.isClosed == false) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        handleClientSocket(clientSocket)
                    } catch (e: Exception) {
                        if (isRunning && serverSocket?.isClosed == false) {
                            logger("[IPP] Server accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                logger("[IPP] Failed to bind IPP port $PORT: ${e.message}")
                isRunning = false
                onStatusChanged(false, null)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        logger("[IPP] Server stopped.")
        onStatusChanged(false, null)
    }

    private fun handleClientSocket(socket: Socket) {
        val clientIp = socket.inetAddress?.hostAddress ?: "Unknown"
        Executors.newSingleThreadExecutor().execute {
            try {
                socket.soTimeout = 5000
                val input = socket.getInputStream()

                // 1. Read HTTP Request Headers
                val headerBytes = readHttpHeaderBytes(input)
                val headerText = String(headerBytes, Charsets.US_ASCII)

                if (headerText.isEmpty()) {
                    socket.close()
                    return@execute
                }

                // Handle Expect: 100-continue header for CUPS / Windows IPP
                if (headerText.contains("Expect: 100-continue", ignoreCase = true)) {
                    val continueResp = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    socket.getOutputStream().write(continueResp)
                    socket.getOutputStream().flush()
                }

                // 2. Read HTTP Body Payload
                val contentLength = parseContentLength(headerText)
                val isChunked = headerText.contains("Transfer-Encoding: chunked", ignoreCase = true)

                val bodyData = if (isChunked) {
                    decodeChunkedBody(input)
                } else if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = input.read(buf, read, contentLength - read)
                        if (r == -1) break
                        read += r
                    }
                    buf
                } else {
                    ByteArray(0)
                }

                if (bodyData.isEmpty()) {
                    sendHttpResponse(socket, 400, "Bad Request")
                    socket.close()
                    return@execute
                }

                val osFamily = detectOsFamily(headerText, bodyData)

                // 3. Parse IPP Request Packet using HP jipp-core
                val ippInputStream = IppInputStream(ByteArrayInputStream(bodyData))
                val ippRequestPacket = ippInputStream.readPacket()

                val version = ippRequestPacket.versionNumber
                val operation = ippRequestPacket.operation
                val requestId = ippRequestPacket.requestId

                when (operation) {
                    Operation.getPrinterAttributes -> {
                        logger("[IPP] Get-Printer-Attributes request (req-id=$requestId, v=$version) from $clientIp [$osFamily]")
                        sendGetPrinterAttributesResponse(socket, version, requestId)
                    }
                    Operation.validateJob -> {
                        logger("[IPP] Validate-Job request (req-id=$requestId, v=$version) from $clientIp [$osFamily]")
                        sendSimpleIppResponse(socket, version, requestId, Status.successfulOk)
                    }
                    Operation.createJob -> {
                        val jobId = JobQueueManager.getNextJobId()
                        lastCreatedJobId = jobId
                        logger("[IPP] Create-Job request #$jobId (req-id=$requestId, v=$version) from $clientIp [$osFamily]")
                        sendCreateJobResponse(socket, version, requestId, jobId)
                    }
                    Operation.sendDocument -> {
                        val jobId = if (lastCreatedJobId > 0) lastCreatedJobId else JobQueueManager.getNextJobId()
                        logger("[IPP] Send-Document #$jobId received (${bodyData.size} bytes, req-id=$requestId, v=$version) from $clientIp [$osFamily]")

                        sendPrintJobResponse(socket, version, requestId, jobId)

                        val docData = extractDocumentBytes(bodyData)
                        if (docData.isNotEmpty()) {
                            processIncomingDocumentPayload(docData, jobId, osFamily)
                        }
                    }
                    Operation.printJob -> {
                        val jobId = JobQueueManager.getNextJobId()
                        logger("[IPP] Print-Job #$jobId received (${bodyData.size} bytes, req-id=$requestId, v=$version) from $clientIp [$osFamily]")

                        sendPrintJobResponse(socket, version, requestId, jobId)

                        val docData = extractDocumentBytes(bodyData)
                        if (docData.isNotEmpty()) {
                            processIncomingDocumentPayload(docData, jobId, osFamily)
                        }
                    }
                    Operation.getJobAttributes -> {
                        logger("[IPP] Get-Job-Attributes request (req-id=$requestId, v=$version) from $clientIp [$osFamily]")
                        sendGetJobAttributesResponse(socket, version, requestId)
                    }
                    else -> {
                        logger("[IPP] Operation $operation requested (req-id=$requestId, v=$version) from $clientIp [$osFamily]")
                        sendSimpleIppResponse(socket, version, requestId, Status.successfulOk)
                    }
                }

                try {
                    socket.close()
                } catch (_: Exception) {}
            } catch (e: Exception) {
                logger("[IPP] Error handling client $clientIp: ${e.message}")
            }
        }
    }

    private fun detectOsFamily(headerText: String, data: ByteArray): String {
        val lowerHeader = headerText.lowercase()
        val dataStr = String(data, 0, Math.min(data.size, 1024), Charsets.US_ASCII).lowercase()

        return when {
            lowerHeader.contains("iphone") || lowerHeader.contains("ipad") || lowerHeader.contains("ipod") || lowerHeader.contains("ios") -> "ios"
            lowerHeader.contains("macintosh") || lowerHeader.contains("darwin") || lowerHeader.contains("mac os x") || lowerHeader.contains("cfnetwork") || dataStr.contains("cgpdftops") || dataStr.contains("safari") || dataStr.contains("mac") || dataStr.contains("apple") -> "macos"
            lowerHeader.contains("windows") || lowerHeader.contains("microsoft") || lowerHeader.contains("nt 10.0") || lowerHeader.contains("nt 11.0") -> "windows"
            lowerHeader.contains("linux") || lowerHeader.contains("ubuntu") || lowerHeader.contains("debian") -> "linux"
            lowerHeader.contains("android") -> "android"
            lowerHeader.contains("cups") -> if (dataStr.contains("mac") || dataStr.contains("apple") || dataStr.contains("cgpdftops") || dataStr.contains("safari")) "macos" else "linux"
            else -> "network"
        }
    }

    private fun readHttpHeaderBytes(input: InputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        var prev3 = -1
        var prev2 = -1
        var prev1 = -1

        while (isRunning) {
            val b = input.read()
            if (b == -1) break
            baos.write(b)
            if (prev3 == 0x0D && prev2 == 0x0A && prev1 == 0x0D && b == 0x0A) { // \r\n\r\n
                break
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        return baos.toByteArray()
    }

    private fun decodeChunkedBody(input: InputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        try {
            while (isRunning) {
                val line = readAsciiLine(input)
                val chunkSizeHex = line.substringBefore(";").trim()
                val chunkSize = chunkSizeHex.toIntOrNull(16) ?: 0
                if (chunkSize <= 0) {
                    readAsciiLine(input) // trailing \r\n
                    break
                }
                val chunkBuf = ByteArray(chunkSize)
                var totalRead = 0
                while (totalRead < chunkSize && isRunning) {
                    val r = input.read(chunkBuf, totalRead, chunkSize - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
                baos.write(chunkBuf, 0, totalRead)
                readAsciiLine(input)
            }
        } catch (_: Exception) {}
        return baos.toByteArray()
    }

    private fun readAsciiLine(input: InputStream): String {
        val baos = ByteArrayOutputStream()
        var prev = -1
        while (isRunning) {
            val b = input.read()
            if (b == -1) break
            if (prev == 0x0D && b == 0x0A) {
                val bytes = baos.toByteArray()
                return String(bytes, 0, Math.max(0, bytes.size - 1), Charsets.US_ASCII)
            }
            baos.write(b)
            prev = b
        }
        return String(baos.toByteArray(), Charsets.US_ASCII).trim()
    }

    private fun parseContentLength(headers: String): Int {
        val lines = headers.lines()
        for (line in lines) {
            if (line.lowercase().startsWith("content-length:")) {
                return line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        return 0
    }

    private fun extractDocumentBytes(data: ByteArray): ByteArray {
        val pdfHeader = "%PDF-".toByteArray()
        val pdfStart = findByteSequence(data, pdfHeader)
        if (pdfStart != -1) {
            return data.copyOfRange(pdfStart, data.size)
        }

        val psHeader = "%!PS-Adobe".toByteArray()
        val psStart = findByteSequence(data, psHeader)
        if (psStart != -1) {
            return data.copyOfRange(psStart, data.size)
        }

        val pngHeader = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val pngStart = findByteSequence(data, pngHeader)
        if (pngStart != -1) return data.copyOfRange(pngStart, data.size)

        val jpgHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val jpgStart = findByteSequence(data, jpgHeader)
        if (jpgStart != -1) return data.copyOfRange(jpgStart, data.size)

        for (i in 8 until data.size) {
            if (data[i] == 0x03.toByte() && i + 1 < data.size) {
                return data.copyOfRange(i + 1, data.size)
            }
        }

        return data
    }

    private fun sendGetPrinterAttributesResponse(socket: Socket, version: Int, requestId: Int) {
        val printerUri = URI("ipp://${getLocalIpAddress()}:$PORT/ipp/print")
        val printerMoreInfo = URI("http://${getLocalIpAddress()}:$PORT/")
        val printerUuid = URI("urn:uuid:e5b02130-1c4b-483b-9a99-000000000001")

        val opGroup = MutableAttributeGroup(
            Tag.operationAttributes,
            listOf(
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            )
        )

        val mediaDbSize = MediaColDatabase.MediaSize(
            xDimension = IntOrIntRange(10160),
            yDimension = IntOrIntRange(15240)
        )
        val mediaColDatabase = MediaColDatabase(
            mediaSize = mediaDbSize,
            mediaTopMargin = 0,
            mediaBottomMargin = 0,
            mediaLeftMargin = 0,
            mediaRightMargin = 0
        )

        val mediaDefSize = MediaCol.MediaSize(
            xDimension = 10160,
            yDimension = 15240
        )
        val mediaColDefault = MediaCol(
            mediaSize = mediaDefSize,
            mediaTopMargin = 0,
            mediaBottomMargin = 0,
            mediaLeftMargin = 0,
            mediaRightMargin = 0
        )

        // DUMMY SINK ARCHITECTURE: Always report printerState = idle and printerStateReasons = none to network clients.
        // The server takes full ownership of jobs and decides when/how to print them on hardware.
        val printerGroup = MutableAttributeGroup(
            Tag.printerAttributes,
            listOf(
                Types.charsetConfigured.of("utf-8"),
                Types.charsetSupported.of("utf-8"),
                Types.naturalLanguageConfigured.of("en"),
                Types.generatedNaturalLanguageSupported.of("en"),
                Types.printerState.of(PrinterState.idle),
                Types.printerStateReasons.of("none"),
                Types.printerIsAcceptingJobs.of(true),
                Types.queuedJobCount.of(jobQueueManager.getQueueSize()),
                Types.ippVersionsSupported.of("1.1", "2.0"),
                Types.operationsSupported.of(
                    Operation.printJob,
                    Operation.validateJob,
                    Operation.createJob,
                    Operation.sendDocument,
                    Operation.getJobAttributes,
                    Operation.getPrinterAttributes
                ),
                Types.compressionSupported.of("none"),
                Types.documentFormatSupported.of(
                    "application/pdf",
                    "image/pwg-raster",
                    "image/png",
                    "image/jpeg",
                    "application/octet-stream"
                ),
                Types.documentFormatDefault.of("application/pdf"),
                Types.pwgRasterDocumentResolutionSupported.of(Resolution(203, 203, ResolutionUnit.dotsPerInch)),
                Types.pwgRasterDocumentSheetBack.of("normal"),
                Types.pwgRasterDocumentTypeSupported.of("black_1"),
                Types.copiesDefault.of(1),
                Types.copiesSupported.of(IntRange(1, 99)),
                Types.orientationRequestedDefault.of(Orientation.portrait),
                Types.orientationRequestedSupported.of(Orientation.portrait),
                Types.printQualityDefault.of(PrintQuality.normal),
                Types.printQualitySupported.of(PrintQuality.normal),
                Types.sidesDefault.of("one-sided"),
                Types.sidesSupported.of("one-sided"),
                Types.finishingsDefault.of(Finishing.none),
                Types.finishingsSupported.of(Finishing.none),
                Types.outputBinDefault.of("face-down"),
                Types.outputBinSupported.of("face-down"),
                Types.mediaSupported.of("na_index-4x6_4x6in", "oe_4x6-label_4x6in", "custom_min_4x6in", "na_letter_8.5x11in", "iso_a4_210x297mm"),
                Types.mediaDefault.of("na_index-4x6_4x6in"),
                Types.mediaReady.of("na_index-4x6_4x6in"),
                Types.pdfVersionsSupported.of("none", "1.4", "1.5", "1.6", "1.7"),
                Types.mediaColDatabase.of(mediaColDatabase),
                Types.mediaColDefault.of(mediaColDefault),
                Types.printerResolutionSupported.of(Resolution(203, 203, ResolutionUnit.dotsPerInch)),
                Types.printerResolutionDefault.of(Resolution(203, 203, ResolutionUnit.dotsPerInch)),
                Types.printerName.of("Rollo Thermal Printer 4x6"),
                Types.printerInfo.of("Rollo Thermal Printer 4x6"),
                Types.printerLocation.of("Local Network"),
                Types.printerMoreInfo.of(printerMoreInfo),
                Types.printerUuid.of(printerUuid),
                Types.printerUriSupported.of(printerUri),
                Types.uriAuthenticationSupported.of("none"),
                Types.uriSecuritySupported.of("none"),
                Types.pdlOverrideSupported.of("attempted")
            )
        )

        val responsePacket = IppPacket(
            versionNumber = version,
            code = Status.successfulOk.code,
            requestId = requestId,
            attributeGroups = listOf(opGroup, printerGroup)
        )
        sendIppPacketResponse(socket, responsePacket)
    }

    private fun sendGetJobAttributesResponse(socket: Socket, version: Int, requestId: Int) {
        val opGroup = MutableAttributeGroup(
            Tag.operationAttributes,
            listOf(
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            )
        )

        val jobGroup = MutableAttributeGroup(
            Tag.jobAttributes,
            listOf(
                Types.jobId.of(1),
                Types.jobState.of(JobState.completed),
                Types.jobStateReasons.of("job-completed-successfully")
            )
        )

        val responsePacket = IppPacket(
            versionNumber = version,
            code = Status.successfulOk.code,
            requestId = requestId,
            attributeGroups = listOf(opGroup, jobGroup)
        )
        sendIppPacketResponse(socket, responsePacket)
    }

    private fun sendCreateJobResponse(socket: Socket, version: Int, requestId: Int, jobId: Int) {
        val printerUri = URI("ipp://${getLocalIpAddress()}:$PORT/ipp/print")
        val jobUri = URI("ipp://${getLocalIpAddress()}:$PORT/ipp/print/job-$jobId")

        val opGroup = MutableAttributeGroup(
            Tag.operationAttributes,
            listOf(
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            )
        )

        val jobGroup = MutableAttributeGroup(
            Tag.jobAttributes,
            listOf(
                Types.jobId.of(jobId),
                Types.jobUri.of(jobUri),
                Types.jobPrinterUri.of(printerUri),
                Types.jobState.of(JobState.completed),
                Types.jobStateReasons.of("job-completed-successfully")
            )
        )

        val responsePacket = IppPacket(
            versionNumber = version,
            code = Status.successfulOk.code,
            requestId = requestId,
            attributeGroups = listOf(opGroup, jobGroup)
        )
        sendIppPacketResponse(socket, responsePacket)
    }

    private fun sendPrintJobResponse(socket: Socket, version: Int, requestId: Int, jobId: Int) {
        val opGroup = MutableAttributeGroup(
            Tag.operationAttributes,
            listOf(
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            )
        )

        val jobGroup = MutableAttributeGroup(
            Tag.jobAttributes,
            listOf(
                Types.jobId.of(jobId),
                Types.jobState.of(JobState.completed),
                Types.jobStateReasons.of("job-completed-successfully")
            )
        )

        val responsePacket = IppPacket(
            versionNumber = version,
            code = Status.successfulOk.code,
            requestId = requestId,
            attributeGroups = listOf(opGroup, jobGroup)
        )
        sendIppPacketResponse(socket, responsePacket)
    }

    private fun sendSimpleIppResponse(socket: Socket, version: Int, requestId: Int, status: Status) {
        val opGroup = MutableAttributeGroup(
            Tag.operationAttributes,
            listOf(
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            )
        )

        val responsePacket = IppPacket(
            versionNumber = version,
            code = status.code,
            requestId = requestId,
            attributeGroups = listOf(opGroup)
        )
        sendIppPacketResponse(socket, responsePacket)
    }

    private fun sendIppPacketResponse(socket: Socket, packet: IppPacket) {
        try {
            val baos = ByteArrayOutputStream()
            val ippOutputStream = IppOutputStream(baos)
            ippOutputStream.write(packet)
            val responseBytes = baos.toByteArray()

            val output = socket.getOutputStream()
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/ipp\r\n" +
                    "Content-Length: ${responseBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(responseBytes)
            output.flush()
        } catch (_: Exception) {}
    }

    private fun sendHttpResponse(socket: Socket, statusCode: Int, statusText: String) {
        try {
            val output = socket.getOutputStream()
            val header = "HTTP/1.1 $statusCode $statusText\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (_: Exception) {}
    }

    private fun processIncomingDocumentPayload(data: ByteArray, jobId: Int, osFamily: String) {
        val tempPdfFile = File(context.cacheDir, "temp_incoming_$jobId.pdf")

        try {
            // 1. Check if incoming payload contains PDF (%PDF-) anywhere in stream
            val pdfHeader = "%PDF-".toByteArray()
            val pdfStart = findByteSequence(data, pdfHeader)
            if (pdfStart != -1) {
                val pdfBytes = data.copyOfRange(pdfStart, data.size)
                FileOutputStream(tempPdfFile).use { it.write(pdfBytes) }
                logger("[IPP] Saved PDF document (${pdfBytes.size} bytes) for Job #$jobId [$osFamily]")
                ingestPdfToLocalPrint(tempPdfFile, jobId, osFamily)
                return
            }

            // 2. Check if incoming payload is a PNG or JPEG image
            val pngHeader = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
            val pngStart = findByteSequence(data, pngHeader)
            val jpgHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            val jpgStart = findByteSequence(data, jpgHeader)

            val imgStart = if (pngStart != -1) pngStart else jpgStart
            if (imgStart != -1) {
                val imgBytes = data.copyOfRange(imgStart, data.size)
                val bitmap = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                if (bitmap != null) {
                    logger("[IPP] Extracted Image document for Job #$jobId [$osFamily]. Converting to 4x6 PDF...")
                    createPdfFromBitmap(bitmap, tempPdfFile)
                    ingestPdfToLocalPrint(tempPdfFile, jobId, osFamily)
                    bitmap.recycle()
                    return
                }
            }

            // 3. Check for CUPS #PDF-BANNER test page
            val textContent = String(data, Charsets.UTF_8)
            if (textContent.startsWith("#PDF-BANNER") || textContent.contains("default-testpage.pdf") || textContent.contains("Test Page", ignoreCase = true)) {
                logger("[IPP] Received CUPS Test Page Banner. Rendering RolloPrint 4x6 Test Label...")
                createCupsTestPagePdf(tempPdfFile)
                ingestPdfToLocalPrint(tempPdfFile, jobId, osFamily)
                return
            }

            // 4. Fallback: PostScript / Plain Text stream -> Convert to 4x6 PDF
            logger("[IPP] Converting PostScript/Text stream to 4x6 PDF for Job #$jobId [$osFamily]...")
            createPdfFromText(textContent, tempPdfFile)
            ingestPdfToLocalPrint(tempPdfFile, jobId, osFamily)

        } catch (e: Exception) {
            logger("[IPP] ERROR processing IPP job #$jobId: ${e.message}")
        } finally {
            try { tempPdfFile.delete() } catch (_: Exception) {}
        }
    }

    private fun createCupsTestPagePdf(outputFile: File) {
        val pdfDoc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(UsbPrintManager.TARGET_WIDTH, UsbPrintManager.TARGET_HEIGHT, 1).create()
        val page = pdfDoc.startPage(pageInfo)
        val canvas = page.canvas

        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
        }

        // Draw Outer Border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        canvas.drawRect(20f, 20f, UsbPrintManager.TARGET_WIDTH - 20f, UsbPrintManager.TARGET_HEIGHT - 20f, paint)

        // Draw Title
        paint.style = Paint.Style.FILL
        paint.textSize = 44f
        paint.isFakeBoldText = true
        canvas.drawText("RolloPrint Test Page", 60f, 100f, paint)

        paint.strokeWidth = 3f
        canvas.drawLine(60f, 120f, UsbPrintManager.TARGET_WIDTH - 60f, 120f, paint)

        // Details
        paint.textSize = 28f
        paint.isFakeBoldText = false
        var y = 180f

        val details = listOf(
            "Printer: Rollo Thermal Printer 4x6",
            "Protocol: Driverless IPP Everywhere (Port 8631)",
            "Resolution: 203 DPI (816 x 1218 pixels)",
            "Media: 4x6 inch (101.6 x 152.4 mm)",
            "Status: Ready & Hardware Confirmed",
            "Timestamp: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        )

        for (detail in details) {
            canvas.drawText(detail, 60f, y, paint)
            y += 48f
        }

        // Decorative test pattern box
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(60f, y + 20f, UsbPrintManager.TARGET_WIDTH - 60f, y + 220f, paint)

        paint.style = Paint.Style.FILL
        paint.textSize = 32f
        paint.isFakeBoldText = true
        canvas.drawText("TEST PRINT SUCCESSFUL", 120f, y + 130f, paint)

        pdfDoc.finishPage(page)
        FileOutputStream(outputFile).use { pdfDoc.writeTo(it) }
        pdfDoc.close()
    }

    private fun createPdfFromBitmap(srcBitmap: Bitmap, outputFile: File) {
        var bitmap = srcBitmap
        if (bitmap.width > bitmap.height) {
            val matrix = Matrix().apply { postRotate(90f) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        val pdfDoc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(UsbPrintManager.TARGET_WIDTH, UsbPrintManager.TARGET_HEIGHT, 1).create()
        val page = pdfDoc.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val scale = Math.min(
            UsbPrintManager.TARGET_WIDTH.toFloat() / bitmap.width,
            UsbPrintManager.TARGET_HEIGHT.toFloat() / bitmap.height
        )
        val renderW = bitmap.width * scale
        val renderH = bitmap.height * scale
        val left = (UsbPrintManager.TARGET_WIDTH - renderW) / 2f
        val top = (UsbPrintManager.TARGET_HEIGHT - renderH) / 2f

        canvas.drawBitmap(bitmap, null, RectF(left, top, left + renderW, top + renderH), null)
        pdfDoc.finishPage(page)

        FileOutputStream(outputFile).use { pdfDoc.writeTo(it) }
        pdfDoc.close()
    }

    private fun createPdfFromText(textContent: String, outputFile: File) {
        val pdfDoc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(UsbPrintManager.TARGET_WIDTH, UsbPrintManager.TARGET_HEIGHT, 1).create()
        val page = pdfDoc.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
        }

        if (textContent.contains("cgpdftops") || textContent.contains("userdict/dscInfo") || textContent.contains("%!PS-Adobe")) {
            // Render formatted PostScript / Safari Document Label with extracted text content!
            var title = "Network Document"
            var creator = "CUPS PostScript Filter"

            val titleMatch = Regex("""/Title\s*\((.*?)\)""").find(textContent)
            if (titleMatch != null && titleMatch.groupValues[1].isNotBlank()) {
                title = titleMatch.groupValues[1]
            }

            val creatorMatch = Regex("""/Creator\s*\((.*?)\)""").find(textContent)
            if (creatorMatch != null && creatorMatch.groupValues[1].isNotBlank()) {
                creator = creatorMatch.groupValues[1]
            }

            // Extract PostScript text strings enclosed in (...)
            val extractedStrings = mutableListOf<String>()
            val psStringRegex = Regex("""\(([^()]{2,80})\)\s*(?:show|Tj|TJ|T|k|K)?""")
            psStringRegex.findAll(textContent).forEach { match ->
                val str = match.groupValues[1].trim()
                if (str.isNotBlank() && !str.startsWith("%") && !str.startsWith("/") && !str.contains("Font") && !str.contains("Encoding") && !str.contains("Adobe")) {
                    extractedStrings.add(str)
                }
            }

            // Outer Border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            canvas.drawRect(20f, 20f, UsbPrintManager.TARGET_WIDTH - 20f, UsbPrintManager.TARGET_HEIGHT - 20f, paint)

            // Header Title
            paint.style = Paint.Style.FILL
            paint.textSize = 38f
            paint.isFakeBoldText = true
            canvas.drawText(title.take(32), 40f, 80f, paint)

            paint.strokeWidth = 3f
            canvas.drawLine(40f, 100f, UsbPrintManager.TARGET_WIDTH - 40f, 100f, paint)

            paint.textSize = 24f
            paint.isFakeBoldText = false
            var y = 140f
            canvas.drawText("Source: $creator", 40f, y, paint)
            y += 36f

            if (extractedStrings.isNotEmpty()) {
                canvas.drawText("--- Document Content ---", 40f, y, paint)
                y += 40f
                paint.textSize = 24f

                val uniqueStrings = extractedStrings.distinct().take(22)
                for (str in uniqueStrings) {
                    canvas.drawText(str.take(52), 40f, y, paint)
                    y += 36f
                    if (y > UsbPrintManager.TARGET_HEIGHT - 50) break
                }
            } else {
                paint.strokeWidth = 2f
                paint.style = Paint.Style.STROKE
                canvas.drawRect(40f, y, UsbPrintManager.TARGET_WIDTH - 40f, y + 140f, paint)

                paint.style = Paint.Style.FILL
                paint.textSize = 28f
                paint.isFakeBoldText = true
                canvas.drawText("DOCUMENT PROCESSED SUCCESSFULLY", 60f, y + 80f, paint)
            }

        } else {
            // Plain text rendering
            paint.textSize = 28f
            val lines = textContent.lines().filter { !it.startsWith("%") && it.trim().isNotEmpty() }
            var y = 60f
            for (line in lines) {
                canvas.drawText(line.take(55), 40f, y, paint)
                y += 36f
                if (y > UsbPrintManager.TARGET_HEIGHT - 40) break
            }
        }

        pdfDoc.finishPage(page)
        FileOutputStream(outputFile).use { pdfDoc.writeTo(it) }
        pdfDoc.close()
    }

    private fun ingestPdfToLocalPrint(pdfFile: File, jobId: Int, osFamily: String) {
        val uri = Uri.fromFile(pdfFile)
        val bitmap = printManager.renderPdfToBitmap(uri)
        if (bitmap != null) {
            // Save diagnostic JPEG screenshot into print history cache
            PrintHistoryCacheManager.saveJobScreenshot(context, bitmap, jobId, "network", osFamily)

            val prefs = context.getSharedPreferences("rollo_prefs", Context.MODE_PRIVATE)
            val showNetworkPreview = prefs.getBoolean("PREF_NETWORK_PREVIEW", false)

            if (showNetworkPreview && onNetworkBitmapRendered != null) {
                logger("[IPP] Displaying Print Preview Dialog for Network Job #$jobId [$osFamily]...")
                onNetworkBitmapRendered.invoke(bitmap)
            } else {
                logger("[IPP] PDF converted to 816x1218 bitmap. Adding Network Job #$jobId [$osFamily] to Print Queue...")
                jobQueueManager.addJob(bitmap, "Network Job #$jobId ($osFamily)", false, jobId)
            }
        } else {
            logger("[IPP] ERROR: Failed to render bitmap for Network Job #$jobId")
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

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val inetAddress = addresses.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
