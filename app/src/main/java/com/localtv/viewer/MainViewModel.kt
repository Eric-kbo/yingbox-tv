package com.localtv.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localtv.viewer.data.*
import com.localtv.viewer.browser.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

typealias Filter = MediaFilter
typealias BrowserState = com.localtv.viewer.browser.BrowserState
data class ViewerState(val source: Source, val items: List<MediaEntry>, val index: Int) {
    val item get() = items[index]
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LocalTvApp
    private val _sources = MutableStateFlow(app.store.load())
    val sources = _sources.asStateFlow()
    private val _browser = MutableStateFlow(BrowserState())
    val browser = _browser.asStateFlow()
    private val _viewer = MutableStateFlow<ViewerState?>(null)
    val viewer = _viewer.asStateFlow()
    private val directoryCache = LinkedHashMap<String, MediaCatalog>(8, .75f, true)
    private val preferences = BrowserPreferences(application)
    private val queryCache = HashMap<String, BrowserQuery>()
    private val selectionCache = HashMap<String, String?>()
    private var loadingJob: Job? = null
    private var generation = 0
    private var queryJob: Job? = null
    private var queryGeneration = 0

    fun openSource(source: Source) {
        load(source, "", force = true)
    }

    fun select(item: MediaEntry) { _browser.update { it.copy(selectedPath = item.path) } }
    fun open(item: MediaEntry) {
        if (_browser.value.filtering) return
        select(item)
        val state = _browser.value
        selectionCache["${state.source?.id}:${state.path}"] = item.path
        val source = state.source ?: return
        if (item.kind == MediaKind.FOLDER) load(source, item.path)
        else {
            val index = state.result.indexByPath[item.path] ?: return
            _viewer.value = ViewerState(source, state.result.media, index)
        }
    }

    fun filter(filter: Filter) = applyQuery(_browser.value.query.copy(type = filter))

    fun applyQuery(query: BrowserQuery, resetSelection: Boolean = true) {
        queryJob?.cancel()
        val state = _browser.value
        val source = state.source ?: return
        val request = ++queryGeneration
        queryCache[state.route] = query
        preferences.saveSort(source.id, query.sort)
        _browser.update { it.copy(query = query, filtering = true, selectedPath = if (resetSelection) null else it.selectedPath) }
        val catalog = state.catalog ?: return
        queryJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { catalog.query(query) }
            if (request == queryGeneration && _browser.value.catalog === catalog)
                _browser.update { it.copy(result = result, filtering = false, revision = it.revision + 1) }
        }
    }

    fun jumpTo(index: Int) { _browser.value.visible.getOrNull(index)?.let { item ->
        _browser.update { it.copy(selectedPath = item.path, revision = it.revision + 1) }
    } }

    fun refresh() { _browser.value.source?.let { load(it, _browser.value.path, force = true) } }

    private fun load(source: Source, path: String, force: Boolean = false) {
        loadingJob?.cancel()
        queryJob?.cancel(); queryGeneration++
        val request = ++generation
        val key = "${source.id}:$path"
        val cached = if (force) null else directoryCache[key]
        val query = queryCache[key] ?: BrowserQuery(sort = preferences.sort(source.id))
        _browser.value = BrowserState(source = source, path = path, catalog = cached, query = query, loading = cached == null,
            selectedPath = selectionCache[key])
        if (cached != null) { applyQuery(query, resetSelection = false); return }
        loadingJob = viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { app.repository.list(source, path) }
                val catalog = withContext(Dispatchers.Default) { MediaCatalog(entries) }
                if (request != generation) return@launch
                directoryCache[key] = catalog
                while (directoryCache.size > 6 || directoryCache.size > 1 && directoryCache.values.sumOf { it.size } > 60000)
                    directoryCache.remove(directoryCache.keys.first())
                _browser.update { it.copy(catalog = catalog, loading = false, error = null) }
                applyQuery(_browser.value.query, resetSelection = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (request == generation) _browser.update { it.copy(loading = false, filtering = false, error = friendlyError(error)) }
            }
        }
    }

    fun next(delta: Int) {
        _viewer.update { viewer ->
            viewer?.copy(index = (viewer.index + delta).coerceIn(0, viewer.items.lastIndex))
        }
    }

    fun closeViewer() {
        _viewer.value?.let { viewer -> _browser.update { it.copy(selectedPath = viewer.item.path) } }
        _viewer.value = null
    }

    fun back() {
        if (_viewer.value != null) { closeViewer(); return }
        val state = _browser.value
        if (state.source == null) return
        if (state.path.isEmpty()) {
            loadingJob?.cancel(); queryJob?.cancel(); generation++; queryGeneration++
            _browser.value = BrowserState()
        } else load(state.source, state.path.substringBeforeLast('/', ""))
    }

    suspend fun testSource(source: Source): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            // Validation owns a separate SMB client; even closing or failing the
            // connection cannot interrupt another saved source on the same PC.
            MediaRepository(app).use { it.list(source).size }
        }
    }

    suspend fun saveSource(source: Source) = withContext(Dispatchers.IO) {
        val changed = _sources.value.toMutableList()
        val index = changed.indexOfFirst { it.id == source.id }
        if (index < 0) changed.add(source) else changed[index] = source
        app.store.save(changed)
        app.repository.invalidate(source.id)
        app.playbackRepository.invalidate(source.id)
        app.mediaServer.invalidate(source.id)
        withContext(Dispatchers.Main) {
            directoryCache.keys.filter { it.startsWith("${source.id}:") }.forEach(directoryCache::remove)
            _sources.value = changed
            if (_browser.value.source?.id == source.id) load(source, "")
        }
    }

    fun deleteSource(source: Source) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val changed = _sources.value.filter { it.id != source.id }
                app.store.save(changed)
                app.repository.invalidate(source.id)
                app.playbackRepository.invalidate(source.id)
                app.mediaServer.invalidate(source.id)
                withContext(Dispatchers.Main) { _sources.value = changed }
            }
            if (_browser.value.source?.id == source.id) { loadingJob?.cancel(); generation++; _browser.value = BrowserState() }
        }
    }
}
