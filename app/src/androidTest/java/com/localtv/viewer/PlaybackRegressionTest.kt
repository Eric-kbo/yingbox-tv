@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localtv.viewer

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.*
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.localtv.viewer.data.*
import com.localtv.viewer.playback.RepositoryDataSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PlaybackRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as LocalTvApp
    private val host = InstrumentationRegistry.getArguments().getString("fixtureHost") ?: "10.0.2.2"
    private fun smb() = Source(name = "Regression", kind = SourceKind.SMB, address = "smb://$host:1445/media", username = "viewer", password = "localtv-test")

    @Test fun sourceValidationAndClosingAnotherSourceDoNotInterruptAnOpenRead() = kotlinx.coroutines.runBlocking {
        val source = smb()
        val repository = app.repository
        val item = repository.list(source).single { it.extension == "mp4" }
        val expected = app.assets.open("demo/${item.name}").use { it.readBytes() }
        repository.open(source, item, 0, item.size).use { handle ->
            val first = handle.input.read()
            assertEquals(expected[0].toInt() and 255, first)
            val model = MainViewModel(app)
            assertTrue(model.testSource(source).isSuccess)
            assertTrue(model.testSource(source.copy(password = "wrong-password")).isFailure)
            val another = source.copy(id = java.util.UUID.randomUUID().toString())
            repository.list(another); repository.invalidate(another.id)
            assertArrayEquals(expected.copyOfRange(1, expected.size), handle.input.readBytes())
        }
    }

    @Test fun directSmbDataSourceReadsSmallChunksAndSeeksWithoutDownloadingPrefix() {
        val source = smb()
        val item = app.repository.list(source).single { it.extension == "mp4" }
        val bytes = app.assets.open("demo/${item.name}").use { it.readBytes() }
        val reader = RepositoryDataSource(app.repository, source, item)
        for (position in listOf(0L, 17L, bytes.size - 500L)) {
            assertEquals(500L, reader.open(DataSpec.Builder().setUri(Uri.parse("localtv://test")).setPosition(position).setLength(500).build()))
            try {
                val result = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(37)
                while (true) { val n = reader.read(chunk, 0, chunk.size); if (n < 0) break; result.write(chunk, 0, n) }
                assertArrayEquals(bytes.copyOfRange(position.toInt(), position.toInt() + 500), result.toByteArray())
            } finally { reader.close() }
        }
        try {
            reader.open(DataSpec.Builder().setUri(Uri.parse("localtv://test")).setPosition(bytes.size + 1L).build())
            fail("Out-of-bounds seek must fail")
        } catch (_: java.io.IOException) { } finally { reader.close() }
    }

    @Test fun media3RendersSmbVideoAndSupportsPauseResumeAndSeek() {
        val source = smb()
        val items = app.repository.list(source, "Album/Formats").filter { it.extension in setOf("mkv", "mov", "webm") }
        assertEquals(3, items.size)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (item in items) {
                val firstFrame = AtomicBoolean(false)
                val error = AtomicReference<PlaybackException?>(null)
                lateinit var player: ExoPlayer
                scenario.onActivity { activity ->
                    player = ExoPlayer.Builder(activity).setMediaSourceFactory(ProgressiveMediaSource.Factory { RepositoryDataSource(app.repository, source, item) }).build()
                    player.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { firstFrame.set(true) }
                        override fun onPlayerError(failure: PlaybackException) { error.set(failure) }
                    })
                    activity.setContentView(PlayerView(activity).apply { this.player = player; useController = false })
                    player.setMediaItem(MediaItem.fromUri("localtv://test/${item.name}")); player.prepare(); player.play()
                }
                try {
                    val deadline = android.os.SystemClock.uptimeMillis() + 20000
                    while (!firstFrame.get() && error.get() == null && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(100)
                    assertNull("${item.name}: ${error.get()}", error.get())
                    assertTrue("First frame ${item.name}", firstFrame.get())
                    scenario.onActivity { player.pause(); assertFalse(player.playWhenReady); player.seekTo(5000); player.play() }
                    android.os.SystemClock.sleep(1200)
                    scenario.onActivity { assertTrue("Seek/resume ${item.name}", player.currentPosition >= 5000); assertTrue(player.playWhenReady) }
                } finally { scenario.onActivity { player.release() } }
            }
        }
    }

    @Test fun softwareHeifDecoderHandlesEncodedPhotoAndNetworkImage() {
        // Some x86 emulators advertise translated ARM as a secondary ABI, while
        // the app actually loads its x86_64 native library.
        val arm = android.os.Build.SUPPORTED_ABIS.first().startsWith("arm")
        val names = listOf("sample.heic", "sample-10bit.avif") + if (arm) listOf("sample-10bit.heic") else emptyList()
        // The emulator lacks Main10 HEIF decoding. ARM also exercises the software
        // 10-bit HEVC path; all architectures exercise software 10-bit AVIF.
        for (name in names) {
            val encoded = instrumentation.context.assets.open(name).use { it.readBytes() }
            if (arm || name.endsWith(".avif")) {
                val independent = com.radzivon.bartoshyk.avif.coder.Coder().decodeSampled(encoded, 320, 180,
                    com.radzivon.bartoshyk.avif.coder.PreferredColorConfig.RGBA_8888)
                try { assertEquals(320, independent.width); assertEquals(180, independent.height) }
                finally { independent.recycle() }
            }
            val request = Glide.with(app).asBitmap().load(encoded).submit(320, 180)
            try {
                val decoded = request.get(30, TimeUnit.SECONDS)
                assertEquals(320, decoded.width); assertEquals(180, decoded.height)
                assertTrue("Decoded image has colors", decoded.getPixel(160, 90) != android.graphics.Color.BLACK)
            } finally { Glide.with(app).clear(request) }
        }
        // Real iPhone samples may be supplied in the ignored, local fixture directory.
        // They are never bundled in the app or source distribution.
        val source = Source(name = "HEIC", kind = SourceKind.WEBDAV, address = "http://$host:8765/dav/Album/Heif/", username = "viewer", password = "localtv-test")
        val samples = runCatching { app.repository.list(source) }.getOrDefault(emptyList())
        for (item in samples) {
            val image = Glide.with(app).asBitmap().load(app.mediaServer.url(source, item)).submit(1920, 1080)
            try { val bitmap = image.get(40, TimeUnit.SECONDS); assertTrue(bitmap.width > 100 && bitmap.height > 100) }
            finally { Glide.with(app).clear(image) }
        }
    }
}
