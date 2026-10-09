package com.localtv.viewer.pairing

import com.localtv.viewer.data.SourceKind
import java.io.Closeable
import java.net.*
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Temporary, token-protected LAN form. Exists only while the TV's add-source dialog is open. */
class SourcePairingServer(host: String, private val html: String,
    private val onSubmit: (SourceDraft) -> PairingReply,
    private val lifetimeMillis: Long = 10 * 60 * 1000L) : Closeable {
    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) }
    private val path = "/" + UUID.randomUUID().toString().replace("-", "")
    val url = "http://$host:${server.localPort}$path"
    private val authority = "$host:${server.localPort}"
    private val expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lifetimeMillis)
    private val closed = AtomicBoolean()
    private val submitting = AtomicBoolean()
    private val sockets: MutableSet<Socket> = java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val workers = ThreadPoolExecutor(2, 4, 20, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { work -> Thread(work, "source-pairing-worker").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val acceptor = Thread({
        while (!closed.get()) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            sockets.add(socket)
            try { workers.execute { socket.use { runCatching { serve(it) } }; sockets.remove(socket) } }
            catch (_: RejectedExecutionException) { sockets.remove(socket); socket.close() }
        }
    }, "source-pairing-listener").apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        socket.soTimeout = 8000
        val input = socket.getInputStream().buffered()
        var headerBytes = 0
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val byte = input.read(); if (byte < 0) throw java.io.EOFException()
                require(++headerBytes <= 8192)
                if (byte == 10) break
                if (byte != 13) bytes.write(byte)
            }
            return bytes.toString("US-ASCII")
        }
        val request = line().split(' ')
        if (request.size != 3) { respond(socket, 400, "Bad request"); return }
        val headers = HashMap<String, String>()
        while (true) {
            val row = line(); if (row.isEmpty()) break
            val key = row.substringBefore(':').lowercase(java.util.Locale.ROOT)
            if (headers.put(key, row.substringAfter(':').trim()) != null) { respond(socket, 400, "Bad request"); return }
        }
        if (request[1] != path || headers["host"] != authority) { respond(socket, 404, "Not found"); return }
        if (System.nanoTime() >= expires) { respond(socket, 410, "二维码已过期，请在电视关闭添加页面后重新打开。"); return }
        if (request[0] == "GET") { respond(socket, 200, html, "text/html; charset=utf-8"); return }
        if (request[0] != "POST") { respond(socket, 405, "Method not allowed"); return }
        val size = headers["content-length"]?.toIntOrNull()
        if (size == null || size !in 1..16384 || !headers["content-type"].orEmpty().startsWith("application/x-www-form-urlencoded")) {
            // Drain a small rejected body so closing the socket does not reset the error response.
            if (size != null && size in 1..65536) repeat(size) { if (input.read() < 0) return }
            respond(socket, 400, "请求格式不正确"); return
        }
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) { val count = input.read(bytes, read, size - read); if (count < 0) return; read += count }
        if (headers["origin"]?.let { it != "http://$authority" } == true || headers.containsKey("transfer-encoding")) {
            respond(socket, 403, "Forbidden"); return
        }
        val draft = try {
            val fields = String(bytes, UTF_8).split('&').associate { pair ->
                URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
            require(fields.values.all { it.length <= 2048 })
            val kind = when (fields["kind"]) { "SMB" -> SourceKind.SMB; "WEBDAV" -> SourceKind.WEBDAV; else -> throw IllegalArgumentException() }
            SourceDraft(kind, fields["address"].orEmpty(), fields["username"].orEmpty(), fields["password"].orEmpty(), fields["name"].orEmpty(), fields["domain"].orEmpty()).also { it.normalized() }
        } catch (error: IllegalArgumentException) { respond(socket, 400, error.message ?: "请检查连接信息"); return }
        if (!submitting.compareAndSet(false, true)) { respond(socket, 409, "正在验证连接，请稍等"); return }
        try {
            val result = onSubmit(draft).get(60, TimeUnit.SECONDS)
            respond(socket, if (result.success) 200 else 422, result.message)
        } catch (_: Exception) { respond(socket, 503, "连接未完成，请检查电视上的提示后重试") }
        finally { submitting.set(false) }
    }

    private fun respond(socket: Socket, status: Int, text: String, type: String = "text/plain; charset=utf-8") {
        val bytes = text.toByteArray(UTF_8)
        val headers = "HTTP/1.1 $status Response\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(UTF_8)); write(bytes); flush() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close(); sockets.forEach { runCatching { it.close() } }; sockets.clear(); workers.shutdownNow()
    }
}
