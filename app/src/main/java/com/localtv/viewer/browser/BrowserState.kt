package com.localtv.viewer.browser

import com.localtv.viewer.data.*

data class BrowserState(val source: Source? = null, val path: String = "", val catalog: MediaCatalog? = null,
    val result: CatalogResult = CatalogResult(), val query: BrowserQuery = BrowserQuery(),
    val loading: Boolean = false, val filtering: Boolean = false, val error: String? = null,
    val selectedPath: String? = null, val revision: Long = 0) {
    val route get() = "${source?.id}:$path"
    val visible get() = result.entries
    val filter get() = query.type
    val selectedIndex get() = selectedPath?.let(result.visibleIndexByPath::get) ?: 0
}
