package com.localtv.viewer.pairing

import com.localtv.viewer.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class SourcePairingTest {
    @Test fun derivesNamesAndAcceptsWindowsPaths() {
        val value = SourceDraft(SourceKind.SMB, "\\\\192.168.1.10\\家庭 照片").normalized()
        assertEquals("家庭 照片", value.name)
        assertEquals("192.168.1.10", java.net.URI(value.address).host)
        assertEquals("Photos", SourceDraft(SourceKind.WEBDAV, "https://example.com/dav/Photos/").normalized().name)
    }

    @Test fun servesPrivateFormAndValidatesBeforeSubmitting() {
        val calls = AtomicInteger()
        SourcePairingServer("127.0.0.1", "<html>private form</html>", { draft ->
            calls.incrementAndGet()
            assertEquals("secret+password", draft.password)
            PairingReply.completed(PairingResponse(true, "已保存"))
        }).use { server ->
            assertFalse(server.url.contains("password"))
            request(server.url).let { assertEquals(200, it.first); assertTrue(it.second.contains("private form")) }
            assertEquals(404, request(server.url.substringBeforeLast('/') + "/invalid").first)
            assertEquals(400, request(server.url, "kind=SMB&address=192.168.1.10").first)
            assertEquals(400, request(server.url, "kind=DEMO&address=demo").first)
            assertEquals(403, request(server.url, "kind=SMB&address=192.168.1.10%2FPhotos", "http://untrusted.example").first)
            assertEquals(0, calls.get())
            assertEquals(200, request(server.url, "kind=SMB&address=192.168.1.10%2FPhotos&password=secret%2Bpassword").first)
            assertEquals(1, calls.get())
            assertEquals(400, request(server.url, "x=" + "a".repeat(17000)).first)
        }
    }

    @Test fun expiredLinksDoNotAcceptCredentials() {
        SourcePairingServer("127.0.0.1", "form", { throw AssertionError("expired request submitted") }, lifetimeMillis = 0).use { server ->
            assertEquals(410, request(server.url).first)
            assertEquals(410, request(server.url, "kind=SMB&address=192.168.1.10%2FPhotos").first)
        }
    }

    private fun request(url: String, body: String? = null, origin: String? = null): Pair<Int, String> {
        val request = Request.Builder().url(url)
        origin?.let { request.header("Origin", it) }
        body?.let { request.post(it.toRequestBody("application/x-www-form-urlencoded".toMediaType())) }
        return OkHttpClient().newCall(request.build()).execute().use { it.code to it.body!!.string() }
    }
}
