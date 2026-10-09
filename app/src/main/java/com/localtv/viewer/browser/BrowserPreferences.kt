package com.localtv.viewer.browser

import android.content.Context

/** Keep only the user's sort choice; transient search/date filters never hide next-session media. */
class BrowserPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    fun sort(sourceId: String): SortOrder = runCatching {
        SortOrder.valueOf(prefs.getString("sort.$sourceId", SortOrder.NEWEST.name)!!)
    }.getOrDefault(SortOrder.NEWEST)
    fun saveSort(sourceId: String, sort: SortOrder) { prefs.edit().putString("sort.$sourceId", sort.name).apply() }
}
