package com.localtv.viewer.data

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class WebDav(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {

    fun url(source: Source, path: String, folder: Boolean = false): HttpUrl {
        val builder = source.address.toHttpUrl().newBuilder()
        checkedRelativePath(path).split('/').filter { it.isNotEmpty() }.forEach(builder::addPathSegment)
        if (folder && path.isNotEmpty()) builder.addPathSegment("")
        return builder.build()
    }

    private fun request(source: Source, url: HttpUrl): Request.Builder = Request.Builder().url(url)
        .header("User-Agent", "LocalTV/1.0")
        .apply {
            if (source.username.isNotEmpty() || source.password.isNotEmpty())
                header("Authorization", Credentials.basic(source.username, source.password, Charsets.UTF_8))
        }

    fun execute(source: Source, builder: Request.Builder): Response {
        var req = builder.build()
        repeat(5) {
            val response = client.newCall(req).execute()
            if (response.code !in listOf(301, 302, 303, 307, 308)) return response
            val next = response.header("Location")?.let { req.url.resolve(it) }
            response.close()
            require(next != null && sameOrigin(next, source.address.toHttpUrl())) { "服务器重定向到其他地址，请直接填写最终 WebDAV 地址" }
            require(next.encodedPath.startsWith(source.address.toHttpUrl().encodedPath)) { "服务器重定向到源目录之外" }
            req = req.newBuilder().url(next).build()
        }
        throw IOException("服务器重定向次数过多")
    }

    fun list(source: Source, path: String): List<MediaEntry> {
        val directory = url(source, path, true)
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getcontenttype/><d:getlastmodified/></d:prop></d:propfind>"""
        execute(source, request(source, directory).header("Depth", "1")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))).use { response ->
            if (response.code != 207 && !response.isSuccessful) throw IOException(httpMessage(response.code))
            val stream = response.body?.source() ?: throw IOException("服务器没有返回目录")
            val limit = 16L * 1024 * 1024
            if (stream.request(limit + 1)) throw IOException("目录过大，请将文件分到几个子文件夹")
            val xml = stream.readUtf8()
            return parseDav(xml, directory, source.address.toHttpUrl())
        }
    }

    fun size(source: Source, item: MediaEntry): Long {
        if (item.size >= 0) return item.size
        execute(source, request(source, url(source, item.path)).head()).use { response ->
            if (!response.isSuccessful) throw IOException(httpMessage(response.code))
            return response.header("Content-Length")?.toLongOrNull() ?: -1
        }
    }

    fun open(source: Source, item: MediaEntry, start: Long, count: Long?): ReadHandle {
        val builder = request(source, url(source, item.path)).header("Accept-Encoding", "identity")
        if (start > 0 || count != null) builder.header("Range", "bytes=$start-${count?.let { start + it - 1 } ?: ""}")
        val response = execute(source, builder)
        try {
            if (!response.isSuccessful) throw IOException(httpMessage(response.code))
            val input = response.body?.byteStream() ?: throw IOException("服务器没有返回文件")
            if (response.code == 206) {
                val actualStart = response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                if (actualStart != start) throw IOException("服务器返回的文件片段位置不正确")
            } else if (start > 0) skipExactly(input, start)
            return ReadHandle(input) { response.close() }
        } catch (error: Throwable) { response.close(); throw error }
    }
}

fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port

fun httpMessage(code: Int): String = when (code) {
    401 -> "账号或密码不正确，或服务器需要其他认证方式"
    403 -> "没有访问权限，请检查目录权限"
    404 -> "目录或文件不存在，请检查源地址"
    405, 501 -> "这个地址没有开启 WebDAV，请填写 WebDAV 服务地址"
    else -> "服务器请求失败（HTTP $code）"
}

/** Parse only successful, direct descendants inside the configured source root. */
fun parseDav(xml: String, directory: HttpUrl, root: HttpUrl): List<MediaEntry> {
    require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "服务器返回了不安全的目录数据" }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
    val document = builder.parse(InputSource(StringReader(xml)))
    val nodes = document.getElementsByTagNameNS("*", "response")
    val result = ArrayList<MediaEntry>(nodes.length)
    val directoryDecoded = decodePath(directory.encodedPath).trimEnd('/') + "/"
    val rootDecoded = decodePath(root.encodedPath).trimEnd('/') + "/"
    for (index in 0 until nodes.length) {
        val element = nodes.item(index) as? Element ?: continue
        val href = element.text("href") ?: continue
        val resolved = directory.resolve(href.trim()) ?: continue
        if (!sameOrigin(resolved, root) || resolved.query != null || resolved.fragment != null) continue
        val decoded = decodePath(resolved.encodedPath).trimEnd('/')
        if (!decoded.startsWith(directoryDecoded) || !decoded.startsWith(rootDecoded)) continue
        val child = decoded.removePrefix(directoryDecoded)
        if (child.isEmpty() || child.contains('/') || child == "." || child == ".." || child.contains('\\') || child.contains('\u0000')) continue
        val propstats = element.getElementsByTagNameNS("*", "propstat")
        val props = (0 until propstats.length).mapNotNull { propstats.item(it) as? Element }
            .firstOrNull { it.text("status")?.contains(Regex("\\s2\\d\\d(?:\\s|$)")) == true } ?: continue
        val folder = props.getElementsByTagNameNS("*", "collection").length > 0
        val kind = if (folder) MediaKind.FOLDER else MediaFormats.kind(child, props.text("getcontenttype").orEmpty()) ?: continue
        val relative = decoded.removePrefix(rootDecoded)
        runCatching { checkedRelativePath(relative) }.getOrNull() ?: continue
        result.add(MediaEntry(relative, child, kind, props.text("getcontentlength")?.toLongOrNull() ?: -1, props.text("getlastmodified").orEmpty()))
    }
    return result.distinctBy { it.path }.sortedWith(mediaComparator)
}

private fun Element.text(name: String): String? = getElementsByTagNameNS("*", name).item(0)?.textContent?.trim()
