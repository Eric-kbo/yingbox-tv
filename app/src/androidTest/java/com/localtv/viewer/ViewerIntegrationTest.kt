package com.localtv.viewer

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.localtv.viewer.data.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ViewerIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as LocalTvApp
    private val device = UiDevice.getInstance(instrumentation)
    private val host = InstrumentationRegistry.getArguments().getString("fixtureHost") ?: "10.0.2.2"
    private fun dav(password: String = "localtv-test") = Source(name = "Test WebDAV", kind = SourceKind.WEBDAV, address = "http://$host:8765/dav/", username = "viewer", password = password)

    @Test fun credentialsSurviveReloadAndStayEncrypted() {
        val original = dav()
        val store = SourceStore(context)
        store.save(listOf(original))
        val restored = SourceStore(context).load().single()
        assertEquals(original, restored)
        assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/localtv.xml").readText().contains("localtv-test"))
        store.save(emptyList())
    }

    @Test fun authenticatedWebDavAndLoopbackRangesWorkEndToEnd() {
        MediaRepository(context).use { repository ->
            val source = dav()
            val entries = repository.list(source)
            assertEquals(6, entries.size)
            assertEquals(MediaKind.FOLDER, entries.first().kind)
            val chinese = repository.list(source, "Album").single { it.name == "家庭 + 1.jpg" }
            assertEquals("家庭 + 1.jpg", chinese.name)
            val local = context.assets.open("demo/01_Mountain.jpg").use { it.readBytes() }
            repository.open(source, chinese, 110, 120).use { file -> assertArrayEquals(local.copyOfRange(110, 230), readExact(file.input, 120)) }
            MediaServer(repository).use { adapter ->
                val client = OkHttpClient()
                val url = adapter.url(source, chinese)
                client.newCall(Request.Builder().url(url).header("Range", "bytes=110-229").build()).execute().use { response ->
                    assertEquals(206, response.code)
                    assertEquals("bytes 110-229/${local.size}", response.header("Content-Range"))
                    assertArrayEquals(local.copyOfRange(110, 230), response.body!!.bytes())
                }
                client.newCall(Request.Builder().url(url).header("Range", "bytes=-10").build()).execute().use {
                    assertEquals(206, it.code); assertArrayEquals(local.takeLast(10).toByteArray(), it.body!!.bytes())
                }
                client.newCall(Request.Builder().url(url).header("Range", "bytes=${local.size}-").build()).execute().use { assertEquals(416, it.code) }
                client.newCall(Request.Builder().url(url).head().build()).execute().use { assertEquals(local.size.toString(), it.header("Content-Length")) }
                client.newCall(Request.Builder().url(url.replace(url.split('/')[3], "invalid")).build()).execute().use { assertEquals(404, it.code) }
            }
            try { repository.list(dav("wrong")); fail("Invalid password accepted") } catch (expected: IOException) { assertTrue(expected.message!!.contains("账号")) }
        }
    }

    @Test fun smb2DirectoryUnicodeAndRandomAccessWorkEndToEnd() {
        val source = Source(name = "Test SMB", kind = SourceKind.SMB, address = "smb://$host:1445/media", username = "viewer", password = "localtv-test")
        MediaRepository(context).use { repository ->
            val entries = repository.list(source)
            assertEquals(6, entries.size)
            val chinese = repository.list(source, "Album").single { it.name == "家庭 + 1.jpg" }
            assertEquals("家庭 + 1.jpg", chinese.name)
            val local = context.assets.open("demo/01_Mountain.jpg").use { it.readBytes() }
            repository.open(source, chinese, 200, 100).use { assertArrayEquals(local.copyOfRange(200, 300), readExact(it.input, 100)) }
            MediaServer(repository).use { server ->
                OkHttpClient().newCall(Request.Builder().url(server.url(source, chinese)).header("Range", "bytes=200-299").build()).execute().use {
                    assertEquals(206, it.code); assertArrayEquals(local.copyOfRange(200, 300), it.body!!.bytes())
                }
            }
        }
    }

    @Test fun remoteNavigatesPhotosGifVideoAndRestoresFocus() {
        app.store.save(emptyList())
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("添加你的照片与影片")), 15000))
            assertTrue(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("＋ 添加源"))), 5000))
            device.pressDPadDown()
            if (!device.hasObject(By.focused(true).hasDescendant(By.text("先体验一下")))) device.pressDPadRight()
            assertTrue(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("先体验一下"))), 5000))
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text("01_Mountain.jpg")), 10000))
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text("1 / 5")), 5000))
            device.pressDPadDown()
            assertTrue(device.wait(Until.hasObject(By.text("2 / 5")), 5000))
            device.pressDPadDown(); device.pressDPadDown()
            assertTrue(device.wait(Until.hasObject(By.text("4 / 5")), 5000))
            device.pressDPadDown()
            assertTrue(device.wait(Until.hasObject(By.text("正在播放")), 15000))
            device.pressMenu()
            assertUi(By.text(java.util.regex.Pattern.compile("0:[0-9]{2} / 0:(19|20)")), "video-time")
            device.pressDPadCenter()
            assertUi(By.text("已暂停"), "video-pause")
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("全部")), 5000))
            assertTrue(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("05_Motion.mp4"))), 5000))
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text("5 / 5")), 5000))
            device.pressBack(); device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("添加你的照片与影片")), 5000))
        }
    }

    @Test fun nativeDecoderDisplaysMkvMovAviWebmAndReadsBmpOverWebDav() {
        val source = dav().copy(address = "http://$host:8765/dav/Album/Formats/")
        val entries = app.repository.list(source)
        val videos = entries.filter { it.kind == MediaKind.VIDEO }
        assertEquals(setOf("mkv", "mov", "avi", "webm"), videos.map { it.extension }.toSet())
        val bitmap = entries.single { it.extension == "bmp" }
        val image = com.bumptech.glide.Glide.with(app).asBitmap().load(app.mediaServer.url(source, bitmap)).submit(640, 400)
        try { assertEquals(640, image.get(15, java.util.concurrent.TimeUnit.SECONDS).width) }
        finally { com.bumptech.glide.Glide.with(app).clear(image) }
        val player = org.videolan.libvlc.MediaPlayer(app.playerEngine)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = org.videolan.libvlc.util.VLCVideoLayout(activity)
                activity.setContentView(view)
                player.attachViews(view, null, true, false)
            }
            try {
                for (entry in videos) {
                    val media = org.videolan.libvlc.Media(app.playerEngine, android.net.Uri.parse(app.mediaServer.url(source, entry)))
                    media.setHWDecoderEnabled(true, false)
                    try {
                        player.media = media
                        player.play()
                        val deadline = android.os.SystemClock.uptimeMillis() + 20000
                        while (android.os.SystemClock.uptimeMillis() < deadline && (media.stats?.displayedPictures ?: 0) < 3)
                            android.os.SystemClock.sleep(100)
                        assertTrue("Decoded and displayed frames: ${entry.name}", (media.stats?.displayedPictures ?: 0) >= 3)
                        player.pause()
                        android.os.SystemClock.sleep(400)
                        assertFalse("Native pause: ${entry.name}", player.isPlaying)
                    } finally { player.stop(); media.release() }
                }
            } finally { scenario.onActivity { player.detachViews(); player.release() } }
        }
    }

    @Test fun sourceFormPairsFromPhoneAndSupportsManualRemoteEditing() {
        app.store.save(emptyList())
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("＋ 添加源"))), 10000))
            device.pressDPadCenter()
            assertUi(By.text("用手机填写，电视自动保存"), "phone-form")
            assertUi(By.textStartsWith("也可在浏览器输入："), "pairing-url")
            val url = device.findObject(By.textStartsWith("也可在浏览器输入：")).text.substringAfter('\n')
            val body = okhttp3.FormBody.Builder().add("kind", "WEBDAV").add("address", "http://$host:8765/dav/")
                .add("username", "viewer").add("password", "localtv-test").add("name", "Integration DAV").build()
            OkHttpClient.Builder().readTimeout(70, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(url).post(body).build()).execute().use { response -> assertEquals(200, response.code) }
            assertTrue(device.wait(Until.hasObject(By.text("Integration DAV")), 5000))
            assertTrue(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("Integration DAV"))), 5000))
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("Album"))), 10000))
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text("家庭 + 1.jpg")), 10000))
            device.pressBack()
            assertUi(By.focused(true).hasDescendant(By.text("Album")), "parent-focus-restored")
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("资料库")), 5000))
            activate("＋ 添加源")
            assertTrue(device.wait(Until.hasObject(By.text("添加媒体源")), 5000))
            activate("遥控器填写")
            fillForm("Integration SMB", "smb://$host:1445/media")
            activate("连接并保存")
            assertUi(By.text("2 个设备"), "multiple-sources", 70000)
            assertEquals(2, app.store.load().size)
            activate("管理源")
            assertTrue(device.wait(Until.hasObject(By.text("管理媒体源")), 5000))
            activate("修改")
            assertTrue(device.wait(Until.hasObject(By.text("修改媒体源")), 5000))
            activate("名称与高级设置")
            assertUi(By.desc("显示名称（可选）"), "edit-name-field")
            editable("显示名称（可选）").text = "Living Room DAV"
            activate("连接并保存")
            assertUi(By.text("Living Room DAV"), "edited-source", 70000)
            activate("管理源")
            assertTrue(device.wait(Until.hasObject(By.text("管理媒体源")), 5000))
            activate("移除")
            assertUi(By.focused(true).hasDescendant(By.text("取消")), "remove-confirmation-focus")
            device.pressDPadRight()
            assertUi(By.focused(true).hasDescendant(By.text("移除")), "remove-button-focus")
            device.pressDPadCenter()
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (app.store.load().size != 1 && android.os.SystemClock.uptimeMillis() < deadline)
                android.os.SystemClock.sleep(100)
            assertEquals(1, app.store.load().size)
        }
        app.store.save(emptyList())
    }

    @Test fun filtersSearchWithoutLosingRemoteFocusOrPlaybackSequence() {
        app.store.save(emptyList())
        ActivityScenario.launch(MainActivity::class.java).use {
            assertUi(By.text("添加你的照片与影片"), "empty-library")
            activate("先体验一下")
            assertUi(By.text("01_Mountain.jpg"), "demo-ready")
            activate("搜索与筛选")
            assertUi(By.desc("文件名包含"), "search-field")
            editable("文件名包含").text = "Mountain"
            assertUi(By.text("Mountain"), "search-entered")
            activate("应用")
            assertUi(By.text("1 张 · 0 部"), "search-result")
            assertUi(By.focused(true).hasDescendant(By.text("01_Mountain.jpg")), "search-focus")
            device.pressDPadCenter(); assertUi(By.text("1 / 1"), "filtered-playback")
            device.pressBack(); activate("筛选 · 已启用"); activate("重置"); activate("最早优先"); activate("应用")
            assertUi(By.text("4 张 · 1 部"), "reset-result")
            activate("视频")
            assertUi(By.text("0 张 · 1 部"), "video-result")
            assertUi(By.focused(true).hasDescendant(By.text("05_Motion.mp4")), "video-filter-focus")
        }
    }

    @Test fun monthJumpOpensOnTvAndFocusesMediaAfterFolders() = kotlinx.coroutines.runBlocking {
        val source = dav().copy(name = "Month Jump DAV")
        val result = com.localtv.viewer.browser.MediaCatalog(app.repository.list(source))
            .query(com.localtv.viewer.browser.BrowserQuery())
        val expected = result.entries[result.months.first().firstIndex]
        assertEquals(1, result.months.first().firstIndex)
        app.store.save(listOf(source))
        ActivityScenario.launch(MainActivity::class.java).use {
            assertUi(By.text(source.name), "month-source")
            activate(source.name)
            assertUi(By.text("按月定位"), "month-action")
            activate("按月定位")
            assertUi(By.text("直接跳到所选月份"), "month-dialog")
            assertUi(By.focused(true).hasDescendant(By.textStartsWith(result.months.first().key.label)), "month-initial-focus")
            device.pressDPadCenter()
            assertUi(By.focused(true).hasDescendant(By.text(expected.name)), "month-jump-focus")
            device.pressDPadCenter(); assertUi(By.text("1 / 5"), "month-jump-playback")
            device.pressBack(); assertUi(By.focused(true).hasDescendant(By.text(expected.name)), "month-return-focus")
        }
        app.store.save(emptyList())
    }

    private fun fillForm(name: String, address: String) {
        assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5000))
        var fields = device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals(3, fields.size)
        fields[0].text = address
        fields[1].text = "viewer"
        fields[2].text = "localtv-test"
        activate("名称与高级设置")
        assertUi(By.desc("显示名称（可选）"), "name-field")
        editable("显示名称（可选）").text = name
        device.waitForIdle()
    }

    private fun editable(label: String): androidx.test.uiautomator.UiObject2 {
        return requireNotNull(device.findObject(By.clazz("android.widget.EditText").hasDescendant(By.desc(label)))
            ?: device.findObject(By.clazz("android.widget.EditText").desc(label)))
    }

    private fun assertUi(selector: androidx.test.uiautomator.BySelector, label: String, timeout: Long = 15000) {
        val deadline = android.os.SystemClock.uptimeMillis() + timeout
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            // API 36 may retain cached Compose nodes across text-only state changes.
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (device.hasObject(selector)) return
            android.os.SystemClock.sleep(200)
        }
        run {
            val folder = File(context.getExternalFilesDir(null), "test-diagnostics").apply { mkdirs() }
            device.dumpWindowHierarchy(File(folder, "$label.xml"))
            device.takeScreenshot(File(folder, "$label.png"))
            fail("UI assertion failed: $label; hierarchy: ${File(folder, "$label.xml").readText()}")
        }
    }

    private fun activate(label: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        // A TV IME is a separate window; close it as a remote user does before pressing a form button.
        if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) { device.pressBack(); device.waitForIdle() }
        assertUi(By.text(label), "activate-$label")
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            if (node.text?.toString() == label) {
                var button: android.view.accessibility.AccessibilityNodeInfo? = node
                while (button != null && !button.isClickable) button = button.parent
                return button?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { if (visit(it)) return true }
            return false
        }
        assertTrue("Accessible activation: $label", visit(instrumentation.uiAutomation.rootInActiveWindow))
        device.waitForIdle()
    }

    private fun readExact(input: java.io.InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var count = 0
        while (count < size) { val read = input.read(bytes, count, size - count); if (read < 0) throw IOException("Unexpected EOF"); count += read }
        return bytes
    }
}
