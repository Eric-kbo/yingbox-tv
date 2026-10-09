package com.localtv.viewer.browser

import com.localtv.viewer.data.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class MediaFilter(val label: String) { ALL("全部"), PHOTOS("照片"), VIDEOS("视频") }
enum class SortOrder(val label: String) { NEWEST("最新优先"), OLDEST("最早优先"), NAME("名称") }
data class BrowserQuery(val type: MediaFilter = MediaFilter.ALL, val sort: SortOrder = SortOrder.NEWEST,
    val keyword: String = "", val year: Int? = null, val month: Int? = null,
    val extension: String? = null, val recentDays: Int? = null) {
    val isFiltered get() = type != MediaFilter.ALL || keyword.isNotBlank() || year != null || extension != null || recentDays != null
    val summary: String get() = listOfNotNull(sort.label,
        type.takeIf { it != MediaFilter.ALL }?.label,
        year?.let { "$it 年${month?.let { m -> " · $m 月" }.orEmpty()}" },
        extension?.uppercase(Locale.ROOT), recentDays?.let { "最近 $it 天" },
        keyword.trim().takeIf { it.isNotEmpty() }?.let { "“$it”" }).joinToString(" · ")
}
data class MonthKey(val year: Int, val month: Int) {
    val label get() = "$year 年 $month 月"
}
data class MonthJump(val key: MonthKey, val firstIndex: Int, val count: Int)
data class CatalogResult(val entries: List<MediaEntry> = emptyList(), val media: List<MediaEntry> = emptyList(),
    val indexByPath: Map<String, Int> = emptyMap(), val photoCount: Int = 0, val videoCount: Int = 0,
    val months: List<MonthJump> = emptyList(), val visibleIndexByPath: Map<String, Int> = emptyMap())

/** Immutable per-directory index. Queries use metadata only; no remote media reads. */
class MediaCatalog(entries: List<MediaEntry>, zone: ZoneId = ZoneId.systemDefault()) {
    private data class Indexed(val entry: MediaEntry, val searchName: String, val month: MonthKey?)
    private val folders = entries.filter { it.kind == MediaKind.FOLDER }.sortedWith(mediaComparator)
    private val indexed = entries.filter { it.kind != MediaKind.FOLDER }.map { entry ->
        val date = entry.modifiedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone) }
        Indexed(entry, entry.name.lowercase(Locale.ROOT), date?.let { MonthKey(it.year, it.monthValue) })
    }
    val years: List<Int> = indexed.mapNotNull { it.month?.year }.distinct().sortedDescending()
    val extensions: List<String> = indexed.map { it.entry.extension }.distinct().sorted()
    val size = entries.size
    private val newest = indexed.sortedWith(compareByDescending<Indexed> { it.entry.modifiedAt }
        .thenComparator { a, b -> naturalCompare(a.entry.name, b.entry.name) })
    private val oldest = indexed.sortedWith(compareBy<Indexed> { it.entry.modifiedAt == 0L }
        .thenBy { it.entry.modifiedAt }.thenComparator { a, b -> naturalCompare(a.entry.name, b.entry.name) })
    private val named by lazy { indexed.sortedWith(Comparator { a, b -> naturalCompare(a.entry.name, b.entry.name) }) }

    suspend fun query(query: BrowserQuery, now: Long = System.currentTimeMillis()): CatalogResult {
        val needle = query.keyword.trim().lowercase(Locale.ROOT)
        val since = query.recentDays?.let { now - it * 86400000L }
        val ordered = when (query.sort) { SortOrder.NEWEST -> newest; SortOrder.OLDEST -> oldest; SortOrder.NAME -> named }
        val visible = ArrayList<MediaEntry>(ordered.size)
        // During date/format filters, matching media take precedence over unrelated directories.
        if (query.year == null && query.extension == null && since == null)
            visible.addAll(folders.filter { needle.isEmpty() || it.name.lowercase(Locale.ROOT).contains(needle) })
        val media = ArrayList<MediaEntry>()
        val indices = HashMap<String, Int>()
        val months = LinkedHashMap<MonthKey, Pair<Int, Int>>()
        var photos = 0
        var videos = 0
        for ((index, item) in ordered.withIndex()) {
            if (index % 512 == 0) currentCoroutineContext().ensureActive()
            val entry = item.entry
            if (query.type == MediaFilter.PHOTOS && entry.kind != MediaKind.PHOTO || query.type == MediaFilter.VIDEOS && entry.kind != MediaKind.VIDEO) continue
            if (needle.isNotEmpty() && !item.searchName.contains(needle)) continue
            if (query.year != null && item.month?.year != query.year) continue
            if (query.month != null && item.month?.month != query.month) continue
            if (query.extension != null && entry.extension != query.extension) continue
            if (since != null && entry.modifiedAt < since) continue
            val position = visible.size
            visible.add(entry)
            indices[entry.path] = media.size
            media.add(entry)
            if (entry.kind == MediaKind.PHOTO) photos++ else videos++
            item.month?.let { key ->
                val previous = months[key]
                months[key] = (previous?.first ?: position) to ((previous?.second ?: 0) + 1)
            }
        }
        return CatalogResult(visible, media, indices, photos, videos,
            months.map { (key, value) -> MonthJump(key, value.first, value.second) },
            visible.mapIndexed { index, entry -> entry.path to index }.toMap())
    }
}
