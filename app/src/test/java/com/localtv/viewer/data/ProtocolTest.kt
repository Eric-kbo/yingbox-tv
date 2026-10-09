package com.localtv.viewer.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun rangesIncludeSuffixAndLargeOffsets() {
        assertEquals(ByteRange(0, 99), ByteRange.parse("bytes=0-99", 1000))
        assertEquals(ByteRange(100, 999), ByteRange.parse("bytes=100-", 1000))
        assertEquals(ByteRange(900, 999), ByteRange.parse("bytes=-100", 1000))
        assertEquals(ByteRange(0, 999), ByteRange.parse("bytes=-2000", 1000))
        assertEquals(ByteRange(4_000_000_000L, 4_999_999_999L), ByteRange.parse("bytes=4000000000-", 5_000_000_000L))
        assertNull(ByteRange.parse(null, 1000))
        listOf("bytes=1000-", "bytes=-0", "bytes=100-99", "bytes=0-1,3-4", "items=0-10", "bytes=garbage").forEach { header ->
            assertThrows(IllegalArgumentException::class.java) { ByteRange.parse(header, 1000) }
        }
    }

    @Test fun sourceAddressesPreserveSpacesAndPlusAndRequireShare() {
        val smb = SourceValidation.normalized(" NAS ", SourceKind.SMB, "\\\\192.168.1.10\\家庭照片\\旅行 + 2026", "u", " p ", "")
        assertEquals("NAS", smb.name)
        assertEquals(" p ", smb.password)
        assertTrue(smb.address.startsWith("smb://192.168.1.10/"))
        assertTrue(decodePath(java.net.URI(smb.address).rawPath).endsWith("旅行 + 2026"))
        assertThrows(IllegalArgumentException::class.java) { SourceValidation.normalized("a", SourceKind.SMB, "smb://192.168.1.10", "", "", "") }
        assertThrows(IllegalArgumentException::class.java) { SourceValidation.normalized("a", SourceKind.SMB, "smb://host/share/../secret", "", "", "") }
        assertThrows(IllegalArgumentException::class.java) { SourceValidation.normalized("a", SourceKind.WEBDAV, "https://user:secret@host/dav", "", "", "") }
        assertEquals("https://example.com/dav/", SourceValidation.normalized("a", SourceKind.WEBDAV, "https://example.com/dav", "", "", "").address)
    }

    @Test fun naturalSortAndFormatDetectionAreUsefulForMixedMedia() {
        val entries = listOf(MediaEntry("p10", "Photo10.JPG", MediaKind.PHOTO), MediaEntry("p2", "Photo2.jpg", MediaKind.PHOTO), MediaEntry("p1", "Photo1.jpg", MediaKind.PHOTO), MediaEntry("z", "ZZ", MediaKind.FOLDER))
        assertEquals(listOf("z", "p1", "p2", "p10"), entries.sortedWith(mediaComparator).map { it.path })
        assertEquals(MediaKind.VIDEO, MediaFormats.kind("电影.MKV"))
        assertEquals(MediaKind.PHOTO, MediaFormats.kind("IMG.HEIC"))
        assertNull(MediaFormats.kind("document.txt"))
        assertTrue(naturalCompare("photo9999999999999999999.jpg", "photo10000000000000000000.jpg") < 0)
    }

    @Test fun davParserHandlesNamespacesFailedPropertiesAndBoundaryPaths() {
        val root = "https://example.com/dav/photos/".toHttpUrl()
        val xml = """<d:multistatus xmlns:d="DAV:">
            ${response("/dav/photos/", "", folder = true)}
            ${response("/dav/photos/Album/", "", folder = true)}
            ${response("/dav/photos/%E5%AE%B6%E5%BA%AD%20%2B%201.jpg", "image/jpeg", length = "321")}
            ${response("/dav/photos/Movie2.mkv", "video/x-matroska")}
            ${response("/dav/photos/document.txt", "text/plain")}
            ${response("https://evil.example/dav/photos/leak.jpg", "image/jpeg")}
            ${response("/dav/photos2/outside.jpg", "image/jpeg")}
            ${response("/dav/photos/Album/nested.jpg", "image/jpeg")}
            ${response("/dav/photos/failed.jpg", "image/jpeg", status = "404")}
            </d:multistatus>"""
        val result = parseDav(xml, root, root)
        assertEquals(3, result.size)
        assertEquals(MediaKind.FOLDER, result[0].kind)
        assertEquals("家庭 + 1.jpg", result.first { it.kind == MediaKind.PHOTO }.path)
        assertEquals(321, result.first { it.kind == MediaKind.PHOTO }.size)
        assertThrows(IllegalArgumentException::class.java) { parseDav("<!DOCTYPE foo [<!ENTITY x SYSTEM 'file:///private'>]><foo/>", root, root) }
    }

    @Test fun davRequestsAuthenticateAndRangeReadHandlesIgnoringServer() {
        MockWebServer().use { server ->
            val source = Source(name = "test", kind = SourceKind.WEBDAV, address = server.url("/dav/").toString(), username = "user", password = "secret")
            val dav = WebDav()
            server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">${response("/dav/a.jpg", "image/jpeg", "10")}</d:multistatus>"))
            val entries = dav.list(source, "")
            assertEquals(1, entries.size)
            val listing = server.takeRequest()
            assertEquals("PROPFIND", listing.method)
            assertEquals("1", listing.getHeader("Depth"))
            assertEquals("Basic dXNlcjpzZWNyZXQ=", listing.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("0123456789"))
            dav.open(source, entries[0], 4, 3).use { handle -> assertEquals("456", String(handle.input.readNBytes(3))) }
            assertEquals("bytes=4-6", server.takeRequest().getHeader("Range"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-6/10").setBody("456"))
            dav.open(source, entries[0], 4, 3).use { assertEquals("456", it.input.reader().readText()) }
        }
    }

    @Test fun davNeverForwardsCredentialsAcrossOrigins() {
        MockWebServer().use { server ->
            val source = Source(name = "test", kind = SourceKind.WEBDAV, address = server.url("/dav/").toString(), username = "user", password = "secret")
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.example/dav/"))
            assertThrows(IllegalArgumentException::class.java) { WebDav().list(source, "") }
        }
    }

    private fun response(href: String, mime: String, length: String = "100", folder: Boolean = false, status: String = "200") =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>${if (folder) "<d:collection/>" else ""}</d:resourcetype><d:getcontenttype>$mime</d:getcontenttype><d:getcontentlength>$length</d:getcontentlength></d:prop><d:status>HTTP/1.1 $status OK</d:status></d:propstat></d:response>"""
}
