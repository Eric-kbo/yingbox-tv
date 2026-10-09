package com.localtv.viewer.browser

import com.localtv.viewer.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MediaCatalogTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private fun photo(name: String, date: String = "") = MediaEntry(name, name, MediaKind.PHOTO, modified = date)

    @Test fun parsesSourceDatesWithoutInventingUnknownTimes() {
        val epoch = Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()
        assertEquals(epoch, MediaTime.parse("Fri, 09 Oct 2026 12:00:00 GMT"))
        assertEquals(epoch, MediaTime.parse("2026-10-09T12:00:00Z"))
        assertEquals(0L, MediaTime.parse("unknown"))
        assertEquals(0L, MediaTime.parse(""))
    }

    @Test fun ordersLatestFirstWithFoldersFirstAndUnknownDatesLast() = runBlocking {
        val entries = listOf(photo("old.jpg", "2025-01-01T00:00:00Z"), photo("IMG10.jpg", "2026-10-01T00:00:00Z"),
            photo("unknown.jpg"), MediaEntry("folder", "folder", MediaKind.FOLDER), photo("IMG2.jpg", "2026-10-01T00:00:00Z"))
        val catalog = MediaCatalog(entries, zone)
        val latest = catalog.query(BrowserQuery())
        assertEquals(listOf("folder", "IMG2.jpg", "IMG10.jpg", "old.jpg", "unknown.jpg"), latest.entries.map { it.name })
        assertEquals(4, latest.photoCount)
        assertEquals(0, latest.indexByPath["IMG2.jpg"])
        assertEquals(1, latest.visibleIndexByPath["IMG2.jpg"])
        assertEquals(1, latest.months.first().firstIndex)
        assertEquals(2, latest.months.first().count)
        assertEquals(listOf("old.jpg", "IMG2.jpg", "IMG10.jpg", "unknown.jpg"), catalog.query(BrowserQuery(sort = SortOrder.OLDEST)).media.map { it.name })
    }

    @Test fun combinesFilenameTypeYearMonthAndFormatInLocalTimezone() = runBlocking {
        val catalog = MediaCatalog(listOf(photo("Trip.JPG", "2025-12-31T17:00:00Z"), photo("Trip.png", "2026-02-01T00:00:00Z"),
            MediaEntry("Trip.mp4", "Trip.mp4", MediaKind.VIDEO, modified = "2025-12-31T17:00:00Z"), photo("other.jpg")), zone)
        assertEquals(listOf(2026), catalog.years)
        val result = catalog.query(BrowserQuery(type = MediaFilter.PHOTOS, keyword = "tRiP", year = 2026, month = 1, extension = "jpg"))
        assertEquals(listOf("Trip.JPG"), result.entries.map { it.name })
        assertEquals(MonthKey(2026, 1), result.months.single().key)
        assertTrue(catalog.query(BrowserQuery(year = 2025)).entries.isEmpty())
    }

    @Test fun recentRangeIncludesBoundaryAndExcludesUndatedFiles() = runBlocking {
        val now = Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
        val catalog = MediaCatalog(listOf(photo("at.jpg", "2026-10-03T00:00:00Z"), photo("before.jpg", "2026-10-02T23:59:59Z"), photo("unknown.jpg")), zone)
        assertEquals(listOf("at.jpg"), catalog.query(BrowserQuery(recentDays = 7), now).entries.map { it.name })
    }

    @Test fun fiftyThousandEntriesSupportRepeatedQueriesAndDirectLookup() = runBlocking {
        val entries = (0 until 50000).map { index -> MediaEntry("$index.jpg", "IMG$index.jpg", MediaKind.PHOTO, modifiedAt = 1700000000000L + index * 86400000L) }
        val catalog = MediaCatalog(entries, zone)
        val all = catalog.query(BrowserQuery())
        assertEquals("49999.jpg", all.media.first().path)
        assertEquals(49999, all.indexByPath["0.jpg"])
        val filtered = catalog.query(BrowserQuery(keyword = "IMG4999"))
        assertEquals(11, filtered.media.size)
        assertEquals("49999.jpg", filtered.media.first().path)
        assertEquals(50000, all.photoCount)
    }
}
