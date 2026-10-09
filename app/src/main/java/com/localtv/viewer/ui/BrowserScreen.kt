package com.localtv.viewer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.localtv.viewer.MainViewModel
import com.localtv.viewer.LocalTvApp
import com.localtv.viewer.browser.*
import com.localtv.viewer.data.*
import kotlinx.coroutines.delay

@Composable
fun BrowserScreen(state: BrowserState, grid: LazyGridState, focusEnabled: Boolean, app: LocalTvApp, model: MainViewModel, onEdit: () -> Unit) {
    val source = state.source ?: return
    val entries = state.visible
    var filters by remember(state.route) { mutableStateOf(false) }
    var jump by remember(state.route) { mutableStateOf(false) }
    val initial = remember(state.route) { FocusRequester() }
    val back = remember { FocusRequester() }
    val selected = state.selectedIndex
    LaunchedEffect(state.route, state.loading, state.filtering, state.revision, focusEnabled, filters, jump) {
        if (!focusEnabled || filters || jump || state.loading || state.filtering) return@LaunchedEffect
        if (entries.isNotEmpty()) {
            if (selected !in grid.layoutInfo.visibleItemsInfo.map { it.index }) grid.scrollToItem(selected)
            delay(120); runCatching { initial.requestFocus() }
        } else { delay(120); runCatching { back.requestFocus() } }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 38.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvButton("‹ 返回", Modifier.focusRequester(back), onClick = model::back)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (state.path.isEmpty()) source.name else state.path.substringAfterLast('/'), fontSize = 25.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(if (state.path.isEmpty()) source.caption else "${source.name} / ${state.path.replace("/", " / ")}", color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (source.kind != SourceKind.DEMO) { TvButton("源设置", onClick = onEdit); Spacer(Modifier.width(8.dp)) }
            TvButton("刷新", onClick = model::refresh)
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MediaFilter.entries.forEach { type -> TvButton(type.label, selected = state.filter == type, enabled = !state.loading) { model.filter(type) } }
            Spacer(Modifier.width(10.dp))
            TvButton(if (state.query.isFiltered) "筛选 · 已启用" else "搜索与筛选", enabled = state.catalog != null) { filters = true }
            if (state.result.months.isNotEmpty() && state.query.sort != SortOrder.NAME)
                TvButton("按月定位", enabled = !state.filtering) { jump = true }
            Spacer(Modifier.weight(1f))
            Text(if (state.filtering) "筛选中…" else "${state.result.photoCount} 张 · ${state.result.videoCount} 部", color = TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(state.query.summary, fontSize = 10.sp, color = TextMuted, maxLines = 1, modifier = Modifier.weight(1f))
            Text("按文件时间", fontSize = 10.sp, color = TextMuted)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                state.loading || state.filtering && entries.isEmpty() -> EmptyMessage("正在整理媒体…", "读取文件时间，不下载完整照片", Glyph.FOLDER)
                state.error != null && entries.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyMessage("暂时无法打开", state.error, Glyph.CLOUD)
                    Spacer(Modifier.height(18.dp)); TvButton("重新连接", selected = true, onClick = model::refresh)
                }
                entries.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyMessage(if (state.query.isFiltered) "没有符合条件的媒体" else "这里还没有媒体", "筛选只查找当前文件夹", Glyph.FOLDER)
                    if (state.query.isFiltered) { Spacer(Modifier.height(16.dp)); TvButton("清除筛选") { model.applyQuery(BrowserQuery(sort = state.query.sort)) } }
                }
                else -> LazyVerticalGrid(GridCells.Fixed(5), state = grid, modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(5.dp)) {
                    itemsIndexed(entries, key = { _, item -> item.path }) { index, item ->
                        MediaTile(source, item, app, (if (index == selected) Modifier.focusRequester(initial) else Modifier)
                            .onFocusChanged { if (it.isFocused) model.select(item) }) { model.open(item) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("确认键查看   ·   全屏 ↑↓ 上一个 / 下一个", color = TextMuted, fontSize = 10.sp)
            Text(if (entries.isEmpty()) "" else "${selected + 1} / ${entries.size}", color = TextMuted, fontSize = 10.sp)
        }
    }
    if (filters) state.catalog?.let { catalog -> BrowserFilters(state.query, catalog, onApply = { model.applyQuery(it); filters = false }, onDismiss = { filters = false }) }
    if (jump) MonthPicker(state.result.months, onPick = { model.jumpTo(it); jump = false }, onDismiss = { jump = false })
}
