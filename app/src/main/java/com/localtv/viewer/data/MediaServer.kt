package com.localtv.viewer.data

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.security.MessageDigest

/** A loopback-only, capability-token HTTP adapter with real random-access SMB reads. */
class MediaServer(private val repository: MediaRepository) : Closeable {
    private data class Ticket(val source: Source, val entry: MediaEntry)
    private val server = ServerSocket(0, 24, InetAddress.getByName("127.0.0.1"))
    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val tokens = ConcurrentHashMap<String, String>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val pool = ThreadPoolExecutor(2, 12, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { work -> Thread(work, "LocalTV-media").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    @Volatile var lastError: String? = null
        private set
    @Volatile private var running = true

    init {
        Thread({
            while (running) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                sockets.add(socket)
                try { pool.execute { handle(socket) } } catch (_: java.util.concurrent.RejectedExecutionException) { sockets.remove(socket); socket.close() }
            }
        }, "LocalTV-loopback").apply { isDaemon = true; start() }
    }

    fun url(source: Source, item: MediaEntry): String {
        val key = "${source.id}:${source.address}:${item.fingerprint}"
        val token = tokens.getOrPut(key) { UUID.randomUUID().toString().replace("-", "") }
        tickets[token] = Ticket(source, item)
        return "http://127.0.0.1:${server.localPort}/$token/${java.net.URLEncoder.encode(item.name, "UTF-8")}"
    }

    fun invalidate(sourceId: String) {
        val dead = tickets.filterValues { it.source.id == sourceId }.keys
        dead.forEach(tickets::remove)
        tokens.entries.removeAll { it.value in dead }
        lastError = null
    }

    private fun handle(socket: Socket) {
        var headersWritten = false
        try {
            socket.soTimeout = 30000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val first = readLine(input) ?: return
            val request = first.split(' ')
            if (request.size != 3 || request[0] !in listOf("GET", "HEAD")) { sendError(socket, 405, "Method Not Allowed"); return }
            val headers = HashMap<String, String>()
            var total = first.length
            while (true) {
                val line = readLine(input) ?: return
                total += line.length
                if (total > 16384) { sendError(socket, 431, "Headers Too Large"); return }
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split > 0) headers[line.substring(0, split).lowercase(java.util.Locale.ROOT)] = line.substring(split + 1).trim()
            }
            val ticket = tickets[request[1].substringBefore('?').split('/').getOrNull(1)]
            if (ticket == null) { sendError(socket, 404, "Not Found"); return }
            val size = repository.size(ticket.source, ticket.entry)
            val range = try {
                if (size < 0 && headers["range"] == "bytes=0-") null else ByteRange.parse(headers["range"], size)
            } catch (_: IllegalArgumentException) {
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val start = range?.start ?: 0
            val count = range?.length ?: size.takeIf { it >= 0 }
            // Open before committing HTTP headers, so network/auth failures become proper 502 responses.
            val file = if (request[0] == "GET" && count != 0L) repository.open(ticket.source, ticket.entry, start, count) else null
            try {
                val responseHeaders = buildString {
                    append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${MediaFormats.mime(ticket.entry.name)}\r\nConnection: close\r\nCache-Control: private, max-age=3600\r\n")
                    if (size >= 0) append("Accept-Ranges: bytes\r\n")
                    if (count != null) append("Content-Length: $count\r\n")
                    if (range != null) append("Content-Range: bytes ${range.start}-${range.endInclusive}/$size\r\n")
                    append("\r\n")
                }
                val output = socket.getOutputStream()
                output.write(responseHeaders.toByteArray(Charsets.US_ASCII))
                headersWritten = true
                if (file != null) {
                    val buffer = ByteArray(256 * 1024)
                    var remaining = count ?: Long.MAX_VALUE
                    while (running && remaining > 0 && !socket.isClosed) {
                        val read = file.input.read(buffer, 0, remaining.coerceAtMost(buffer.size.toLong()).toInt())
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
                output.flush()
            } finally { file?.close() }
        } catch (error: Exception) {
            if (!headersWritten) { lastError = friendlyError(error); runCatching { sendError(socket, 502, "Source Unavailable") } }
        } finally { sockets.remove(socket); runCatching { socket.close() } }
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        socket.getOutputStream().write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= 8192) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == 10) return bytes.toString("US-ASCII").trimEnd('\r')
            bytes.write(byte)
        }
        throw IOException("Header too long")
    }

    override fun close() {
        running = false
        server.close()
        sockets.forEach { runCatching { it.close() } }
        pool.shutdownNow()
        tickets.clear(); tokens.clear()
    }
}

fun cacheKey(source: Source, item: MediaEntry): String = MessageDigest.getInstance("SHA-256")
    .digest("${source.id}:${source.address}:${item.fingerprint}".toByteArray())
    .joinToString("") { "%02x".format(it) }
