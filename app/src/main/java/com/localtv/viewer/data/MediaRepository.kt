package com.localtv.viewer.data

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ReadHandle(val input: InputStream, private val cleanup: () -> Unit = {}) : Closeable {
    override fun close() { try { input.close() } finally { cleanup() } }
}

class MediaRepository(private val context: Context) : Closeable {
    private val dav = WebDav()
    private val smbClient = SMBClient(SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS)
        .withSoTimeout(25, TimeUnit.SECONDS).withReadBufferSize(1024 * 1024).build())
    private val connections = ConcurrentHashMap<String, SmbSession>()
    private val locks = ConcurrentHashMap<String, Any>()

    private class SmbSession(val connection: Connection, val session: Session, val share: DiskShare, val base: String) : Closeable {
        override fun close() { runCatching { share.close() }; runCatching { session.close() }; runCatching { connection.close() } }
        fun path(relative: String): String = listOf(base, checkedRelativePath(relative)).filter { it.isNotEmpty() }.joinToString("/").replace('/', '\\')
    }

    private fun smb(source: Source): SmbSession = synchronized(locks.getOrPut(source.id) { Any() }) {
        connections[source.id]?.takeIf { it.connection.isConnected && it.share.isConnected }?.let { return@synchronized it }
        connections.remove(source.id)?.close()
        val uri = URI(source.address)
        val segments = decodePath(uri.rawPath).trim('/').split('/')
        val connection = smbClient.connect(uri.host, if (uri.port > 0) uri.port else 445)
        try {
            val domain = source.domain.ifBlank { source.username.substringBefore('\\', "") }
            val username = source.username.substringAfter('\\', source.username)
            val auth = if (username.isBlank() && source.password.isBlank()) AuthenticationContext.anonymous()
                else AuthenticationContext(username, source.password.toCharArray(), domain)
            val session = connection.authenticate(auth)
            val share = session.connectShare(segments.first()) as? DiskShare ?: throw IOException("这个共享不是文件目录")
            SmbSession(connection, session, share, segments.drop(1).joinToString("/")).also { connections[source.id] = it }
        } catch (error: Throwable) { runCatching { connection.close() }; throw error }
    }

    fun list(source: Source, path: String = ""): List<MediaEntry> = when (source.kind) {
        SourceKind.WEBDAV -> dav.list(source, path)
        SourceKind.SMB -> {
            val connection = smb(source)
            try {
                connection.share.list(connection.path(path)).mapNotNull { file ->
                    val name = file.fileName
                    if (name == "." || name == ".." || name.contains('/') || name.contains('\\')) return@mapNotNull null
                    val folder = file.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    val kind = if (folder) MediaKind.FOLDER else MediaFormats.kind(name) ?: return@mapNotNull null
                    MediaEntry(listOf(path, name).filter { it.isNotEmpty() }.joinToString("/"), name, kind, file.endOfFile, file.lastWriteTime.toString())
                }.sortedWith(mediaComparator)
            } catch (error: Throwable) { invalidate(source.id); throw error }
        }
        SourceKind.DEMO -> {
            require(path.isEmpty()) { "目录不存在" }
            context.assets.list("demo").orEmpty().mapNotNull { name ->
                val kind = MediaFormats.kind(name) ?: return@mapNotNull null
                val size = context.assets.open("demo/$name").use { it.available().toLong() }
                MediaEntry(name, name, kind, size, "1")
            }.sortedWith(mediaComparator)
        }
    }

    fun size(source: Source, item: MediaEntry): Long = when (source.kind) {
        SourceKind.WEBDAV -> dav.size(source, item)
        SourceKind.SMB -> if (item.size >= 0) item.size else {
            val session = smb(source)
            session.share.getFileInformation(session.path(item.path), FileStandardInformation::class.java).endOfFile
        }
        SourceKind.DEMO -> context.assets.open("demo/${checkedRelativePath(item.path)}").use { it.available().toLong() }
    }

    fun open(source: Source, item: MediaEntry, start: Long = 0, count: Long? = null): ReadHandle = when (source.kind) {
        SourceKind.WEBDAV -> dav.open(source, item, start, count)
        SourceKind.SMB -> {
            val session = smb(source)
            val file = session.share.openFile(session.path(item.path), EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.noneOf(FileAttributes::class.java), EnumSet.allOf(SMB2ShareAccess::class.java),
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            var position = start
            val input = object : InputStream() {
                override fun read(): Int { val byte = ByteArray(1); return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    val n = file.read(b, position, off, len)
                    if (n > 0) position += n
                    return n
                }
            }
            ReadHandle(input) { file.close() }
        }
        SourceKind.DEMO -> {
            val input = context.assets.open("demo/${checkedRelativePath(item.path)}")
            try { skipExactly(input, start); ReadHandle(input) } catch (error: Throwable) { input.close(); throw error }
        }
    }

    fun invalidate(sourceId: String) { connections.remove(sourceId)?.close() }
    override fun close() { connections.values.forEach { it.close() }; connections.clear(); smbClient.close() }
}

fun skipExactly(input: InputStream, count: Long) {
    var remaining = count
    val scratch = ByteArray(32 * 1024)
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped > 0) remaining -= skipped
        else {
            val read = input.read(scratch, 0, remaining.coerceAtMost(scratch.size.toLong()).toInt())
            if (read < 0) throw IOException("文件已经结束")
            remaining -= read
        }
    }
}

fun friendlyError(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        message.contains("LOGON_FAILURE", true) || message.contains("ACCESS_DENIED", true) -> "账号、密码或共享权限不正确"
        message.contains("BAD_NETWORK_NAME", true) -> "共享名称不存在，请检查地址中的共享文件夹名称"
        message.contains("OBJECT_NAME_NOT_FOUND", true) || message.contains("OBJECT_PATH_NOT_FOUND", true) -> "文件或目录不存在，请刷新后重试"
        error is java.net.UnknownHostException -> "找不到设备，请检查地址和网络连接"
        error is java.net.SocketTimeoutException || message.contains("timed out", true) -> "连接超时，请检查设备是否在线"
        error is java.net.ConnectException -> "连接失败，请检查服务是否开启，以及电视能否访问这个地址"
        error is javax.net.ssl.SSLException -> "HTTPS 证书验证失败，请使用有效证书或正确的服务地址"
        message.isNotBlank() && message.any { it.code in 0x4e00..0x9fff } -> message.take(160)
        else -> "无法读取媒体，请检查源地址、访问权限和网络连接"
    }
}
