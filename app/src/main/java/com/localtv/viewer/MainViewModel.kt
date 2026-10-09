package com.localtv.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localtv.viewer.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Filter { ALL, PHOTOS, VIDEOS }
data class BrowserState(
    val source: Source? = null,
    val path: String = "",
    val entries: List<MediaEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val filter: Filter = Filter.ALL,
    val selectedPath: String? = null,
) {
    val route get() = "${source?.id}:$path:${filter.name}"
    val visible get() = entries.filter { it.kind == MediaKind.FOLDER || filter == Filter.ALL || (filter == Filter.PHOTOS && it.kind == MediaKind.PHOTO) || (filter == Filter.VIDEOS && it.kind == MediaKind.VIDEO) }
}
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
    private val directoryCache = LinkedHashMap<String, List<MediaEntry>>()
    private val selectionCache = HashMap<String, String?>()
    private var loadingJob: Job? = null
    private var generation = 0

    fun openSource(source: Source) {
        _browser.value = BrowserState(source = source)
        load(source, "")
    }

    fun select(item: MediaEntry) { _browser.update { it.copy(selectedPath = item.path) } }
    fun open(item: MediaEntry) {
        select(item)
        val state = _browser.value
        selectionCache["${state.source?.id}:${state.path}"] = item.path
        val source = state.source ?: return
        if (item.kind == MediaKind.FOLDER) load(source, item.path)
        else {
            val media = state.visible.filter { it.kind != MediaKind.FOLDER }
            _viewer.value = ViewerState(source, media, media.indexOfFirst { it.path == item.path }.coerceAtLeast(0))
        }
    }

    fun filter(filter: Filter) { _browser.update { it.copy(filter = filter, selectedPath = null) } }

    fun refresh() { _browser.value.source?.let { load(it, _browser.value.path, force = true) } }

    private fun load(source: Source, path: String, force: Boolean = false) {
        loadingJob?.cancel()
        val request = ++generation
        val key = "${source.id}:$path"
        val cached = if (force) null else directoryCache[key]
        _browser.value = BrowserState(source, path, cached.orEmpty(), cached == null, selectedPath = selectionCache[key])
        loadingJob = viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { app.repository.list(source, path) }
                if (request != generation) return@launch
                directoryCache[key] = entries
                while (directoryCache.size > 30) directoryCache.remove(directoryCache.keys.first())
                _browser.update { it.copy(entries = entries, loading = false, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (request == generation) _browser.update { it.copy(loading = false, error = friendlyError(error)) }
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
            loadingJob?.cancel(); generation++
            _browser.value = BrowserState()
        } else load(state.source, state.path.substringBeforeLast('/', ""))
    }

    suspend fun testSource(source: Source): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            // Separate id prevents a form test from invalidating an active source connection.
            val temporary = source.copy(id = "test-${java.util.UUID.randomUUID()}")
            try { app.repository.list(temporary).size } finally { app.repository.invalidate(temporary.id) }
        }
    }

    suspend fun saveSource(source: Source) = withContext(Dispatchers.IO) {
        val changed = _sources.value.toMutableList()
        val index = changed.indexOfFirst { it.id == source.id }
        if (index < 0) changed.add(source) else changed[index] = source
        app.store.save(changed)
        app.repository.invalidate(source.id)
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
                app.mediaServer.invalidate(source.id)
                withContext(Dispatchers.Main) { _sources.value = changed }
            }
            if (_browser.value.source?.id == source.id) { loadingJob?.cancel(); generation++; _browser.value = BrowserState() }
        }
    }
}
