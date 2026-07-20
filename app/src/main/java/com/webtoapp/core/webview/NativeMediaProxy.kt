package com.webtoapp.core.webview

import com.webtoapp.core.logging.AppLogger
import com.webtoapp.core.network.NetworkModule
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object NativeMediaProxy {
    private const val TAG = "NativeMediaProxy"
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val BUFFER_SIZE = 64 * 1024
    private const val CLIENT_TIMEOUT_MS = 30_000
    private const val ENTRY_TTL_MS = 65 * 60 * 1000L
    private const val MAX_ENTRIES = 256

    private data class Entry(
        val url: String,
        @Volatile var lastAccessAt: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var executor: ThreadPoolExecutor? = null

    @Volatile
    private var running = false

    @Volatile
    private var listenPort = 0

    @Synchronized
    fun register(url: String): String {
        val port = ensureStarted()
        if (port <= 0) throw IllegalStateException("Media proxy failed to start")
        pruneEntries()
        val key = UUID.randomUUID().toString().replace("-", "")
        entries[key] = Entry(url, System.currentTimeMillis())
        return "http://127.0.0.1:$port/media/$key"
    }

    @Synchronized
    private fun ensureStarted(): Int {
        if (running && listenPort > 0 && serverSocket?.isClosed == false) return listenPort
        stopInternal()
        return try {
            val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            val workers = ThreadPoolExecutor(
                0,
                64,
                30L,
                TimeUnit.SECONDS,
                SynchronousQueue()
            ) { task ->
                Thread(task, "NativeMediaProxy-Worker").apply { isDaemon = true }
            }.also { it.allowCoreThreadTimeOut(true) }
            serverSocket = socket
            executor = workers
            listenPort = socket.localPort
            running = true
            Thread({ acceptLoop(socket) }, "NativeMediaProxy-Accept").apply {
                isDaemon = true
                start()
            }
            AppLogger.i(TAG, "Media proxy listening on 127.0.0.1:$listenPort")
            listenPort
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start media proxy", e)
            stopInternal()
            -1
        }
    }

    private fun stopInternal() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        try {
            executor?.shutdownNow()
        } catch (_: Exception) {
        }
        serverSocket = null
        executor = null
        listenPort = 0
        entries.clear()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) AppLogger.w(TAG, "Accept failed: ${e.message}")
                break
            }
            val workers = executor
            if (workers == null) {
                closeQuietly(client)
                break
            }
            try {
                workers.execute { handleClient(client) }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Dispatch failed: ${e.message}")
                closeQuietly(client)
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = CLIENT_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream(), BUFFER_SIZE)
            val requestLine = readHttpLine(input) ?: return
            val requestParts = requestLine.split(' ', limit = 3)
            if (requestParts.size != 3) {
                sendStatus(output, 400, "Bad Request")
                return
            }
            val method = requestParts[0].uppercase(Locale.ROOT)
            val requestTarget = requestParts[1]
            val headers = readHeaders(input) ?: run {
                sendStatus(output, 431, "Request Header Fields Too Large")
                return
            }
            if (method == "OPTIONS") {
                sendOptions(output)
                return
            }
            if (method != "GET" && method != "HEAD") {
                sendStatus(output, 405, "Method Not Allowed")
                return
            }
            val path = if (requestTarget.startsWith("http://") || requestTarget.startsWith("https://")) {
                runCatching { URI(requestTarget).path }.getOrNull().orEmpty()
            } else {
                requestTarget.substringBefore('?')
            }
            val key = path.removePrefix("/media/")
            if (key.isBlank() || key.contains('/')) {
                sendStatus(output, 404, "Not Found")
                return
            }
            val entry = entries[key]
            if (entry == null || System.currentTimeMillis() - entry.lastAccessAt > ENTRY_TTL_MS) {
                entries.remove(key)
                sendStatus(output, 410, "Gone")
                return
            }
            entry.lastAccessAt = System.currentTimeMillis()
            forwardMedia(output, method, headers, entry.url)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Client request failed: ${e.message}")
        } finally {
            closeQuietly(client)
        }
    }

    private fun forwardMedia(
        output: BufferedOutputStream,
        method: String,
        requestHeaders: Map<String, String>,
        targetUrl: String
    ) {
        val requestBuilder = Request.Builder()
            .url(targetUrl)
            .header("Accept-Encoding", "identity")
            .header("X-WebToApp-Media-Proxy", "1")
        listOf("range", "if-range", "if-modified-since", "accept", "user-agent").forEach { name ->
            requestHeaders[name]?.takeIf { it.isNotBlank() }?.let { value ->
                requestBuilder.header(name, value)
            }
        }
        requestBuilder.method(method, null)
        NetworkModule.streamingClient.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body
            val responseHeaders = linkedMapOf<String, String>()
            listOf(
                "Content-Type",
                "Content-Length",
                "Content-Range",
                "Accept-Ranges",
                "Content-Disposition",
                "Last-Modified",
                "ETag",
                "Cache-Control"
            ).forEach { name ->
                response.header(name)?.takeIf { it.isNotBlank() }?.let { responseHeaders[name] = it }
            }
            if (!responseHeaders.containsKey("Content-Length")) {
                body?.contentLength()?.takeIf { it >= 0 }?.let { responseHeaders["Content-Length"] = it.toString() }
            }
            responseHeaders["Access-Control-Allow-Origin"] = "*"
            responseHeaders["Access-Control-Expose-Headers"] =
                "Accept-Ranges, Content-Range, Content-Length, Content-Type, Last-Modified, ETag"
            responseHeaders["Cross-Origin-Resource-Policy"] = "cross-origin"
            responseHeaders["Connection"] = "close"
            writeResponseHead(
                output,
                response.code,
                response.message.ifBlank { statusReason(response.code) },
                responseHeaders
            )
            if (method == "HEAD" || body == null) return@use
            body.byteStream().use { upstream ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = upstream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String>? {
        val headers = linkedMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readHttpLine(input) ?: break
            if (line.isEmpty()) break
            total += line.length
            if (total > MAX_HEADER_BYTES) return null
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim()
            if (name.isNotBlank()) headers[name] = value
        }
        return headers
    }

    private fun readHttpLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) {
                return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.ISO_8859_1.name())
            }
            if (next == '\n'.code) {
                val raw = bytes.toByteArray()
                val length = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, length, StandardCharsets.ISO_8859_1)
            }
            bytes.write(next)
        }
        return null
    }

    private fun sendOptions(output: OutputStream) {
        writeResponseHead(
            output,
            204,
            "No Content",
            linkedMapOf(
                "Content-Length" to "0",
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to "Range, If-Range",
                "Access-Control-Max-Age" to "86400",
                "Connection" to "close"
            )
        )
    }

    private fun sendStatus(output: OutputStream, code: Int, reason: String) {
        writeResponseHead(
            output,
            code,
            reason,
            linkedMapOf(
                "Content-Length" to "0",
                "Access-Control-Allow-Origin" to "*",
                "Connection" to "close"
            )
        )
    }

    private fun writeResponseHead(
        output: OutputStream,
        code: Int,
        reason: String,
        headers: Map<String, String>
    ) {
        val safeReason = reason.replace("\r", " ").replace("\n", " ")
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(safeReason).append("\r\n")
            headers.forEach { (name, value) ->
                append(name)
                    .append(": ")
                    .append(value.replace("\r", " ").replace("\n", " "))
                    .append("\r\n")
            }
            append("\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun statusReason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        304 -> "Not Modified"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        else -> "Upstream Response"
    }

    private fun pruneEntries() {
        val now = System.currentTimeMillis()
        entries.entries.forEach { entry ->
            if (now - entry.value.lastAccessAt > ENTRY_TTL_MS) {
                entries.remove(entry.key, entry.value)
            }
        }
        if (entries.size <= MAX_ENTRIES) return
        entries.entries
            .sortedBy { it.value.lastAccessAt }
            .take(entries.size - MAX_ENTRIES)
            .forEach { entries.remove(it.key) }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
