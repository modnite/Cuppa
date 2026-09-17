package com.cuppa.app.server

import android.content.Context
import android.util.Base64
import com.cuppa.app.util.CuppaLog
import com.cuppa.cups.CupsEngine
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * A lightweight Coroutine-based TCP server that handles incoming HTTP IPP requests.
 *
 * Manages HTTP transport, responds to Expect: 100-continue, handles Content-Length and
 * Chunked transfer encodings, passes IPP payloads to the native CUPS engine, and replies.
 *
 * When [tlsEnabled], the listener additionally offers IPPS on the SAME port: it peeks the first
 * byte of each accepted connection to tell a TLS ClientHello (0x16) apart from a plaintext HTTP
 * request, mirroring the opportunistic-TLS pattern our own client side already implements
 * (see cups/request.c's HTTP_STATUS_UPGRADE_REQUIRED handling) — a plaintext request gets a
 * 426 Upgrade Required response and the client is expected to reconnect fresh with TLS from
 * byte 0, exactly like real IPP Everywhere / AirPrint printers do.
 */
class IppServer(
    private val port: Int = 631,
    private val context: Context? = null,
    private val tlsEnabled: Boolean = false,
    private val accessMode: Int = 0, // 0=all, 1=local subnet, 2=localhost
    private val httpAuthUsername: String? = null,
    private val httpAuthPassword: String? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "IppServer"
        private const val MAX_IPP_PAYLOAD = 100 * 1024 * 1024 // 100 MB max limit
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var sslContext: SSLContext? = null

    var actualPort: Int = port
        private set

    var isUsingIptablesRedirect: Boolean = false
        private set

    val isBound: Boolean
        get() = serverSocket != null && !serverSocket!!.isClosed

    /**
     * Start the IPP HTTP listener on a background thread.
     */
    fun start() {
        if (serverSocket != null) return

        if (tlsEnabled) {
            sslContext = context?.let { com.cuppa.app.server.TlsCertificateManager.getSslContext(it) }
            if (sslContext == null) {
                CuppaLog.e(TAG, "TLS was requested but SSLContext could not be built — falling back to plaintext-only")
            } else {
                CuppaLog.i(TAG, "IPPS (TLS) enabled — plaintext requests will receive 426 Upgrade Required")
            }
        }

        var bound = false

        // Attempt primary port (e.g. 631 or user configured port)
        try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            actualPort = port
            bound = true
            CuppaLog.i(TAG, "IPP Server listening natively on port $actualPort")
        } catch (e: Exception) {
            CuppaLog.w(TAG, "Failed initial bind to port $port (${e.message})")
        }

        // If port 631 failed due to privilege, try root sysctl if root is available
        if (!bound && port < 1024 && com.cuppa.app.util.RootHelper.isRootAvailable()) {
            try {
                runBlocking {
                    val sysctlOk = com.cuppa.app.util.RootHelper.enablePort631Sysctl()
                    CuppaLog.i(TAG, "Root sysctl port unlock result: ${sysctlOk.isSuccess}")
                }
            } catch (_: Exception) {}

            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                actualPort = port
                bound = true
                CuppaLog.i(TAG, "IPP Server successfully bound to privileged port $actualPort via root sysctl")
            } catch (e: Exception) {
                CuppaLog.w(TAG, "Failed bind to port $port even after sysctl unlock (${e.message})")
            }
        }

        // If classic root isn't available, try the identical sysctl unlock via Shizuku's
        // elevated shell process instead. Shizuku is a fully-built, already-authorized privilege
        // path in this app (see ShizukuHelper) that was never actually wired into port binding
        // before — this device shows "Shizuku: Authorized" in Settings but that authorization
        // did nothing for port 631 until now. Note this runs Shizuku's process as the *shell*
        // UID (2000), not root (UID 0), so on devices with a hardened SELinux policy (e.g.
        // Samsung/Knox) this sysctl write may still be refused — that's a device limitation, not
        // a bug, and the server falls back to the unprivileged port exactly as before either way.
        if (!bound && port < 1024 && !com.cuppa.app.util.RootHelper.isRootAvailable()) {
            try {
                runBlocking {
                    val result = com.cuppa.app.util.ShizukuHelper.executeCommand(
                        "sysctl -w net.ipv4.ip_unprivileged_port_start=0 || echo 0 > /proc/sys/net/ipv4/ip_unprivileged_port_start"
                    )
                    CuppaLog.i(TAG, "Shizuku sysctl port unlock result: ${result.isSuccess}${result.exceptionOrNull()?.let { " (${it.message})" } ?: ""}")
                }
            } catch (_: Exception) {}

            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                actualPort = port
                bound = true
                CuppaLog.i(TAG, "IPP Server successfully bound to privileged port $actualPort via Shizuku")
            } catch (e: Exception) {
                CuppaLog.w(TAG, "Failed bind to port $port even after Shizuku sysctl attempt (${e.message}) — shell UID likely lacks permission for this sysctl on this device")
            }
        }

        // Fallback to high port 8631 if primary port could not be bound
        if (!bound) {
            val fallbackPort = if (port == 8631) 9100 else 8631
            CuppaLog.i(TAG, "Attempting unprivileged fallback port $fallbackPort")
            try {
                serverSocket = ServerSocket(fallbackPort).apply {
                    reuseAddress = true
                }
                actualPort = fallbackPort
                bound = true
                CuppaLog.i(TAG, "IPP Server listening on unprivileged fallback port $actualPort")

                // If root is available, set up iptables redirect 631 -> fallbackPort
                if (com.cuppa.app.util.RootHelper.isRootAvailable()) {
                    scope.launch {
                        val redirectResult = com.cuppa.app.util.RootHelper.setupIptablesRedirect(631, actualPort)
                        isUsingIptablesRedirect = redirectResult.isSuccess
                        CuppaLog.i(TAG, "iptables redirect 631 -> $actualPort enabled: $isUsingIptablesRedirect")
                    }
                }
            } catch (e: Exception) {
                CuppaLog.e(TAG, "Failed fallback bind to port $fallbackPort", e)
                return
            }
        }

        serverJob = scope.launch {
            while (isActive) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch { handleClient(clientSocket) }
                } catch (e: SocketException) {
                    if (isActive) CuppaLog.w(TAG, "Socket exception accepting client: ${e.message}")
                    break
                } catch (e: Exception) {
                    CuppaLog.e(TAG, "Error accepting client", e)
                }
            }
        }
    }

    /**
     * Stop the server and clean up resources.
     */
    fun stop() {
        CuppaLog.i(TAG, "Stopping IPP Server")
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            CuppaLog.w(TAG, "Error closing server socket", e)
        }
        serverSocket = null

        if (isUsingIptablesRedirect) {
            scope.launch {
                com.cuppa.app.util.RootHelper.removeIptablesRedirect(631, actualPort)
            }
            isUsingIptablesRedirect = false
        }

        scope.coroutineContext.cancelChildren()
    }

    /**
     * javax.net.ssl.SSLSocketFactory#createSocket(Socket, InputStream, boolean) exists on every
     * Android version (it's part of the standard JDK javax.net.ssl API, added upstream well
     * before API 26) but is excluded from Android's compile-time SDK stub jar, so calling it
     * directly is a compile error even though it resolves and runs fine via reflection at
     * runtime — the same workaround several other Android STARTTLS implementations use.
     */
    private fun createTlsSocketPreservingPeekedByte(rawSocket: Socket, consumed: InputStream): SSLSocket {
        val factory = sslContext!!.socketFactory
        val method = javax.net.ssl.SSLSocketFactory::class.java.getMethod(
            "createSocket", Socket::class.java, InputStream::class.java, Boolean::class.javaPrimitiveType
        )
        return method.invoke(factory, rawSocket, consumed, true) as SSLSocket
    }

    private data class HttpRequestMeta(
        val method: String,
        val path: String,
        val contentLength: Int,
        val isChunked: Boolean,
        val expect100Continue: Boolean,
        val authorizationHeader: String?
    )

    private suspend fun handleClient(rawSocket: Socket) = withContext(dispatcher) {
        try {
            rawSocket.soTimeout = 10000 // 10 sec timeout for reading headers/body/handshake

            if (!isAccessAllowed(rawSocket.inetAddress)) {
                CuppaLog.w(TAG, "Rejecting connection from ${rawSocket.inetAddress} — outside configured access mode ($accessMode)")
                sendHttpResponse(rawSocket.getOutputStream(), 403, "Forbidden")
                rawSocket.close()
                return@withContext
            }

            val effectiveSocket: Socket = if (sslContext != null) {
                val peek = PushbackInputStream(rawSocket.getInputStream(), 1)
                val firstByte = peek.read()
                if (firstByte == -1) {
                    rawSocket.close()
                    return@withContext
                }
                peek.unread(firstByte)

                if (firstByte == 0x16) {
                    // TLS record header (ContentType.handshake) — this connection is a fresh
                    // TLS ClientHello, exactly what a client sends after we've told it 426
                    // Upgrade Required on a prior plaintext attempt. Wrap it in place, preserving
                    // the one byte we already peeked via the consumed-InputStream overload — it's
                    // present at runtime on every Android version since API 26 but excluded from
                    // the compile-time SDK stub, so it's invoked via reflection here.
                    val sslSocket = createTlsSocketPreservingPeekedByte(rawSocket, peek)
                    sslSocket.useClientMode = false
                    sslSocket.soTimeout = 10000
                    sslSocket.startHandshake()
                    CuppaLog.d(TAG, "TLS handshake completed with ${rawSocket.remoteSocketAddress}")
                    sslSocket
                } else {
                    // Plaintext request while TLS is required: tell the client to reconnect
                    // over TLS, the same signal our own client-side code already reacts to.
                    sendHttpResponse(
                        rawSocket.getOutputStream(), 426, "Upgrade Required",
                        extraHeaders = mapOf("Upgrade" to "TLS/1.2, HTTP/1.1", "Connection" to "Upgrade")
                    )
                    rawSocket.close()
                    return@withContext
                }
            } else {
                rawSocket
            }

            effectiveSocket.use { s ->
                val input = s.getInputStream()
                val output = s.getOutputStream()

                // 1. Read HTTP request line and headers
                val meta = readHttpRequestMeta(input)
                if (meta == null) {
                    CuppaLog.w(TAG, "Failed reading HTTP request metadata from client ${s.remoteSocketAddress}")
                    sendHttpResponse(output, 400, "Bad Request")
                    return@withContext
                }

                CuppaLog.d(TAG, "HTTP Request: ${meta.method} ${meta.path} (len: ${meta.contentLength}, chunked: ${meta.isChunked}, expect100: ${meta.expect100Continue})")

                if (!isAuthorized(meta.authorizationHeader)) {
                    CuppaLog.w(TAG, "Rejecting unauthenticated request from ${s.remoteSocketAddress}")
                    sendHttpResponse(
                        output, 401, "Unauthorized",
                        extraHeaders = mapOf("WWW-Authenticate" to "Basic realm=\"Cuppa\"")
                    )
                    return@withContext
                }

                // Handle HTTP GET (e.g. browser probe)
                if (meta.method.equals("GET", ignoreCase = true) || meta.method.equals("HEAD", ignoreCase = true)) {
                    val html = "<html><body><h1>Cuppa Local CUPS Print Server</h1><p>IPP Endpoint is active on port $actualPort.</p></body></html>"
                    sendHttpResponse(output, 200, "OK", "text/html", html.toByteArray(Charsets.UTF_8))
                    return@withContext
                }

                // 2. If client sent "Expect: 100-continue", reply immediately so CUPS client unblocks
                if (meta.expect100Continue) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                }

                // 3. Read the IPP payload body (either via chunked transfer or Content-Length)
                val requestBody: ByteArray? = when {
                    meta.isChunked -> readChunkedPayload(input)
                    meta.contentLength > 0 -> {
                        if (meta.contentLength > MAX_IPP_PAYLOAD) {
                            CuppaLog.w(TAG, "Content-Length out of bounds: ${meta.contentLength}")
                            sendHttpResponse(output, 413, "Payload Too Large")
                            return@withContext
                        }
                        readFully(input, meta.contentLength)
                    }
                    else -> {
                        CuppaLog.w(TAG, "No Content-Length or Chunked encoding specified for POST ${meta.path}")
                        sendHttpResponse(output, 400, "Bad Request")
                        return@withContext
                    }
                }

                if (requestBody == null || requestBody.isEmpty()) {
                    CuppaLog.w(TAG, "Failed to read full IPP payload body")
                    sendHttpResponse(output, 400, "Bad Request")
                    return@withContext
                }

                CuppaLog.d(TAG, "Processing IPP payload (${requestBody.size} bytes) via native engine")

                // 4. Process via native engine
                val responseBody = CupsEngine.processIppRequest(requestBody)
                if (responseBody == null) {
                    CuppaLog.e(TAG, "Native CUPS engine returned null response for IPP request")
                    sendHttpResponse(output, 500, "Internal Server Error")
                    return@withContext
                }

                // 5. Send successful IPP response
                sendHttpResponse(output, 200, "OK", "application/ipp", responseBody)
            }
        } catch (e: Exception) {
            CuppaLog.e(TAG, "Error handling IPP client connection", e)
            try { rawSocket.close() } catch (_: Exception) {}
        }
    }

    private fun readHttpRequestMeta(input: InputStream): HttpRequestMeta? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].trim()
        val path = parts[1].trim()

        var contentLength = -1
        var isChunked = false
        var expect100 = false
        var authHeader: String? = null

        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break // End of headers
            val lower = line.lowercase()
            if (lower.startsWith("content-length:")) {
                contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: -1
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                isChunked = true
            } else if (lower.startsWith("expect:") && lower.contains("100-continue")) {
                expect100 = true
            } else if (lower.startsWith("authorization:")) {
                authHeader = line.substringAfter(":").trim()
            }
        }

        return HttpRequestMeta(
            method = method,
            path = path,
            contentLength = contentLength,
            isChunked = isChunked,
            expect100Continue = expect100,
            authorizationHeader = authHeader
        )
    }

    /**
     * True if this app's own configured access_mode setting permits a client at [remote].
     * accessMode: 0 = allow all, 1 = local /24 subnet only, 2 = loopback only.
     */
    private fun isAccessAllowed(remote: InetAddress?): Boolean {
        if (accessMode == 0) return true
        if (remote == null) return false
        if (remote.isLoopbackAddress) return true
        if (accessMode == 2) return false // localhost-only, and this isn't loopback

        // accessMode == 1: local subnet — compare the first three IPv4 octets against this
        // device's own LAN address, a reasonable approximation of "same /24" for typical
        // home/office Wi-Fi without needing the actual subnet mask.
        val localIp = com.cuppa.app.util.NetworkUtils.getLocalIpAddress() ?: return true
        val remoteHost = remote.hostAddress ?: return false
        val localParts = localIp.split(".")
        val remoteParts = remoteHost.split(".")
        if (localParts.size != 4 || remoteParts.size != 4) return true
        return localParts[0] == remoteParts[0] && localParts[1] == remoteParts[1] && localParts[2] == remoteParts[2]
    }

    /** True if HTTP Basic Auth is configured and [authHeader] matches it. */
    private fun isAuthorized(authHeader: String?): Boolean {
        if (httpAuthUsername.isNullOrBlank() || httpAuthPassword.isNullOrBlank()) return true
        val expected = "Basic " + Base64.encodeToString(
            "$httpAuthUsername:$httpAuthPassword".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        return authHeader == expected
    }

    private fun readChunkedPayload(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input)?.trim() ?: return null
            if (line.isEmpty()) continue
            val chunkSizeStr = line.split(";")[0].trim()
            val chunkSize = chunkSizeStr.toIntOrNull(16) ?: return null
            if (chunkSize == 0) {
                // Read trailing headers until empty line
                while (true) {
                    val trailer = readLine(input)
                    if (trailer.isNullOrEmpty()) break
                }
                break
            }
            if (out.size() + chunkSize > MAX_IPP_PAYLOAD) {
                return null
            }
            val chunk = readFully(input, chunkSize) ?: return null
            out.write(chunk)
            // Discard \r\n after chunk
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (true) {
            c = input.read()
            if (c == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (c == '\n'.code) {
                break
            }
            if (c != '\r'.code) {
                sb.append(c.toChar())
            }
        }
        return sb.toString()
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = input.read(buffer, totalRead, length - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return if (totalRead == length) buffer else null
    }

    private fun sendHttpResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String = "text/plain",
        body: ByteArray = ByteArray(0),
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Server: Cuppa-CUPS/2.2.9\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            if (!extraHeaders.containsKey("Connection")) append("Connection: close\r\n")
            for ((key, value) in extraHeaders) append("$key: $value\r\n")
            append("\r\n")
        }
        
        try {
            output.write(header.toByteArray(Charsets.US_ASCII))
            if (body.isNotEmpty()) {
                output.write(body)
            }
            output.flush()
        } catch (e: Exception) {
            CuppaLog.w(TAG, "Failed to write HTTP response: ${e.message}")
        }
    }
}
