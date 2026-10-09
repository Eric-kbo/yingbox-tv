package com.localtv.viewer.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID

enum class SourceKind { SMB, WEBDAV, DEMO }
enum class MediaKind { FOLDER, PHOTO, VIDEO }

data class Source(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: SourceKind,
    val address: String,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    val caption: String get() = when (kind) {
        SourceKind.SMB -> "局域网共享 · ${runCatching { URI(address).host }.getOrNull().orEmpty()}"
        SourceKind.WEBDAV -> "WebDAV · ${address.toHttpUrlOrNull()?.host.orEmpty()}"
        SourceKind.DEMO -> "离线体验 · 照片与视频"
    }
    companion object {
        val demo = Source("demo", "体验相册", SourceKind.DEMO, "demo://gallery")
    }
}

data class MediaEntry(
    val path: String,
    val name: String,
    val kind: MediaKind,
    val size: Long = -1,
    val modified: String = "",
    val modifiedAt: Long = MediaTime.parse(modified),
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val fingerprint: String get() = "$path:$size:$modified"
}

object MediaFormats {
    private val photos = setOf("jpg", "jpeg", "jpe", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    private val videos = setOf("mp4", "m4v", "mkv", "mov", "avi", "webm", "ts", "m2ts", "mts", "mpg", "mpeg", "vob", "flv", "wmv", "asf", "3gp", "3g2", "ogv", "rm", "rmvb", "divx", "f4v", "mxf")
    fun kind(name: String, mime: String = ""): MediaKind? {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            extension in photos || mime.startsWith("image/", true) -> MediaKind.PHOTO
            extension in videos || mime.startsWith("video/", true) -> MediaKind.VIDEO
            else -> null
        }
    }
    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heif"
        "avif" -> "image/avif"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "ts", "mts", "m2ts" -> "video/mp2t"
        else -> "application/octet-stream"
    }
}

object SourceValidation {
    fun normalized(name: String, kind: SourceKind, rawAddress: String, username: String, password: String, domain: String, id: String? = null): Source {
        require(name.trim().isNotEmpty()) { "请填写源名称" }
        require(name.trim().length <= 60) { "名称请控制在 60 个字以内" }
        val raw = rawAddress.trim()
        require(raw.isNotEmpty()) { "请填写共享地址" }
        val address = when (kind) {
            SourceKind.WEBDAV -> {
                val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("请填写 http:// 或 https:// 开头的 WebDAV 地址")
                require(url.username.isEmpty() && url.password.isEmpty()) { "账号和密码请填在下方，不要写在地址里" }
                require(url.query == null && url.fragment == null) { "请填写 WebDAV 目录地址，不要包含查询参数或 #" }
                require(url.pathSegments.none { it == ".." || it == "." || it.contains('\\') }) { "目录地址无效" }
                url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString()
            }
            SourceKind.SMB -> {
                val candidate = if (raw.startsWith("\\\\")) "smb://" + raw.trimStart('\\').replace('\\', '/') else if (!raw.startsWith("smb://", true)) "smb://$raw" else raw
                val uri = runCatching { URI(candidate.replace(" ", "%20")) }.getOrElse { throw IllegalArgumentException("共享地址格式不正确") }
                require(uri.scheme.equals("smb", true) && !uri.host.isNullOrBlank()) { "例如 smb://192.168.1.10/Photos" }
                require(uri.userInfo == null) { "账号和密码请填在下方" }
                require(uri.query == null && uri.fragment == null) { "共享地址不能包含查询参数或 #" }
                require(uri.port == -1 || uri.port in 1..65535) { "端口不正确" }
                val segments = decodePath(uri.rawPath.orEmpty()).split('/').filter { it.isNotEmpty() }
                require(segments.isNotEmpty()) { "请在地址后填写共享名称，例如 /Photos" }
                require(segments.none { it == "." || it == ".." || it.contains('\\') || it.contains('\u0000') }) { "共享目录无效" }
                URI("smb", null, uri.host, uri.port, "/" + segments.joinToString("/"), null, null).toASCIIString().trimEnd('/')
            }
            SourceKind.DEMO -> "demo://gallery"
        }
        return Source(id ?: UUID.randomUUID().toString(), name.trim(), kind, address, username.trim(), password, domain.trim())
    }
}

fun decodePath(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

fun checkedRelativePath(path: String): String {
    require(!path.startsWith('/') && !path.startsWith('\\')) { "目录路径无效" }
    require(path.split('/').none { it == ".." || it == "." || it.contains('\\') || it.contains('\u0000') }) { "目录路径无效" }
    return path.trimEnd('/')
}

val mediaComparator = Comparator<MediaEntry> { a, b ->
    if ((a.kind == MediaKind.FOLDER) != (b.kind == MediaKind.FOLDER)) {
        if (a.kind == MediaKind.FOLDER) -1 else 1
    } else naturalCompare(a.name, b.name)
}

fun naturalCompare(a: String, b: String): Int {
    val aa = a.lowercase(Locale.ROOT)
    val bb = b.lowercase(Locale.ROOT)
    var i = 0
    var j = 0
    while (i < aa.length && j < bb.length) {
        if (aa[i].isDigit() && bb[j].isDigit()) {
            val si = i; val sj = j
            while (i < aa.length && aa[i].isDigit()) i++
            while (j < bb.length && bb[j].isDigit()) j++
            val na = aa.substring(si, i).trimStart('0').ifEmpty { "0" }
            val nb = bb.substring(sj, j).trimStart('0').ifEmpty { "0" }
            val numberComparison = na.length.compareTo(nb.length).takeIf { it != 0 } ?: na.compareTo(nb)
            if (numberComparison != 0) return numberComparison
        } else {
            val comparison = aa[i].compareTo(bb[j])
            if (comparison != 0) return comparison
            i++; j++
        }
    }
    return (aa.length - i).compareTo(bb.length - j).takeIf { it != 0 } ?: a.compareTo(b)
}

data class ByteRange(val start: Long, val endInclusive: Long) {
    val length get() = endInclusive - start + 1
    companion object {
        fun parse(header: String?, size: Long): ByteRange? {
            if (header == null) return null
            require(size > 0 && header.startsWith("bytes=") && !header.contains(',')) { "Invalid range" }
            val pieces = header.removePrefix("bytes=").trim().split('-', limit = 2)
            require(pieces.size == 2) { "Invalid range" }
            if (pieces[0].isEmpty()) {
                val suffix = pieces[1].toLongOrNull() ?: throw IllegalArgumentException("Invalid suffix")
                require(suffix > 0) { "Invalid suffix" }
                return ByteRange((size - suffix).coerceAtLeast(0), size - 1)
            }
            val start = pieces[0].toLongOrNull() ?: throw IllegalArgumentException("Invalid offset")
            val end = if (pieces[1].isEmpty()) size - 1 else pieces[1].toLongOrNull() ?: throw IllegalArgumentException("Invalid end")
            require(start >= 0 && start < size && end >= start) { "Unsatisfiable range" }
            return ByteRange(start, end.coerceAtMost(size - 1))
        }
    }
}

fun readableSize(size: Long): String = when {
    size < 0 -> ""
    size >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GB", size / (1024.0 * 1024 * 1024))
    size >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024))
    else -> "${(size / 1024).coerceAtLeast(1)} KB"
}
