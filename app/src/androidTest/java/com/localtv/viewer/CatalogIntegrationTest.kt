package com.localtv.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localtv.viewer.data.*
import com.localtv.viewer.browser.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogIntegrationTest {
    @Test fun fiftyThousandEntriesAndLatestQueryWins() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LocalTvApp
        val entries = (0 until 50000).map { index -> MediaEntry("$index.jpg", "IMG$index.jpg", MediaKind.PHOTO, modifiedAt = 1700000000000L + index * 86400000L) }
        val start = android.os.SystemClock.elapsedRealtime()
        val catalog = withContext(Dispatchers.Default) { MediaCatalog(entries) }
        val indexed = android.os.SystemClock.elapsedRealtime()
        val result = catalog.query(BrowserQuery(keyword = "IMG4999"))
        val end = android.os.SystemClock.elapsedRealtime()
        assertEquals(11, result.photoCount)
        assertEquals("49999.jpg", result.entries.first().path)
        android.util.Log.i("LocalTV-Benchmark", "50000 entries: index=${indexed-start}ms query=${end-indexed}ms")
        assertTrue("Generous cold-emulator limit", end-start < 15000)
        val model = MainViewModel(app)
        withContext(Dispatchers.Main) { model.openSource(Source.demo) }
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        while (model.browser.value.catalog == null || model.browser.value.filtering || model.browser.value.loading) {
            assertTrue(android.os.SystemClock.uptimeMillis() < deadline); delay(100)
        }
        withContext(Dispatchers.Main) {
            model.applyQuery(BrowserQuery(keyword = "Mountain"))
            model.applyQuery(BrowserQuery(type = MediaFilter.VIDEOS))
        }
        while (model.browser.value.filtering) { assertTrue(android.os.SystemClock.uptimeMillis() < deadline); delay(50) }
        assertEquals(listOf("05_Motion.mp4"), model.browser.value.visible.map { it.name })
        withContext(Dispatchers.Main) { model.back() }
    }
}
