@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.localtv.viewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import com.localtv.viewer.*
import com.localtv.viewer.data.*
import kotlinx.coroutines.delay

@Composable
fun LocalTvUi(model: MainViewModel, app: LocalTvApp) = TvTheme {
    val sources by model.sources.collectAsStateWithLifecycle()
    val browser by model.browser.collectAsStateWithLifecycle()
    val viewer by model.viewer.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Source?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val grids = remember { mutableMapOf<String, LazyGridState>() }
    val sourceGrid = rememberLazyGridState()
    BackHandler(enabled = browser.source != null || viewer != null) { model.back() }
    Box(Modifier.fillMaxSize().background(Background)) {
        if (viewer == null) {
            if (browser.source == null) SourcesScreen(sources, sourceGrid, !showForm && !showManage && !showHelp, model::openSource,
                onAdd = { editing = null; showForm = true },
                onEdit = { editing = it; showForm = true },
                onManage = { showManage = true }, onHelp = { showHelp = true })
            else BrowserScreen(browser, grids.getOrPut(browser.route) { LazyGridState() }, !showForm, app, model,
                onEdit = { editing = browser.source; showForm = true })
        } else ViewerScreen(viewer!!, app, model::next, model::closeViewer)
    }
    if (showForm) SourceForm(editing, model, onDismiss = { showForm = false })
    if (showManage) ManageDialog(sources, onEdit = { editing = it; showManage = false; showForm = true },
        onDelete = model::deleteSource, onDismiss = { showManage = false })
    if (showHelp) HelpDialog { showHelp = false }
}

@Composable
private fun SourcesScreen(sources: List<Source>, grid: LazyGridState, focusEnabled: Boolean, onOpen: (Source) -> Unit,
    onAdd: () -> Unit, onEdit: (Source) -> Unit, onManage: () -> Unit, onHelp: () -> Unit) {
    val initial = remember { FocusRequester() }
    LaunchedEffect(sources.size, focusEnabled) { if (focusEnabled) { delay(120); runCatching { initial.requestFocus() } } }
    Column(Modifier.fillMaxSize().padding(horizontal = 38.dp, vertical = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            MediaGlyph(Glyph.LOGO, Modifier.size(34.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("映匣", fontSize = 25.sp, fontWeight = FontWeight.Medium)
                Text("LOCAL TV", fontSize = 9.sp, color = TextMuted, letterSpacing = 2.sp)
            }
            Spacer(Modifier.weight(1f))
            TvButton("使用说明", onClick = onHelp)
            Spacer(Modifier.width(12.dp))
            if (sources.isNotEmpty()) { TvButton("管理源", onClick = onManage); Spacer(Modifier.width(12.dp)) }
            TvButton("＋ 添加源", modifier = if (sources.isEmpty()) Modifier.focusRequester(initial) else Modifier, selected = true, onClick = onAdd)
        }
        Spacer(Modifier.height(28.dp))
        if (sources.isEmpty()) {
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1.2f)) {
                    Text("把回忆，放上大屏。", fontSize = 36.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(16.dp))
                    Text("连接电脑、NAS 或网络相册\n照片与视频，在一个地方轻松查看。", color = TextMuted, fontSize = 17.sp, lineHeight = 28.sp)
                    Spacer(Modifier.height(26.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton("连接我的设备", selected = true, onClick = onAdd)
                        TvButton("先体验一下", onClick = { onOpen(Source.demo) })
                    }
                }
                Box(Modifier.weight(1f).padding(start = 36.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(236.dp).background(Panel, RoundedCornerShape(42.dp)), contentAlignment = Alignment.Center) {
                        MediaGlyph(Glyph.PHOTO, Modifier.size(125.dp))
                    }
                    Box(Modifier.align(Alignment.BottomEnd).offset((-12).dp, 16.dp).size(88.dp).background(PanelSoft, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                        MediaGlyph(Glyph.VIDEO, Modifier.size(48.dp))
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("我的媒体源", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(14.dp))
                Text("${sources.size} 个设备", fontSize = 13.sp, color = TextMuted)
            }
            Spacer(Modifier.height(18.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(3), state = grid, modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(5.dp)) {
                itemsIndexed(sources, key = { _, source -> source.id }) { index, source ->
                    SourceTile(source, if (index == 0) Modifier.focusRequester(initial) else Modifier, { onOpen(source) }, { onEdit(source) })
                }
                item("add") { SourceTile(null, Modifier, onAdd, onAdd) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("方向键选择   ·   确认键打开   ·   长按确认修改源", color = TextMuted, fontSize = 12.sp)
            Text("本机保存  ·  无需注册", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SourceTile(source: Source?, modifier: Modifier, onClick: () -> Unit, onEdit: () -> Unit) {
    Card(onClick = onClick, onLongClick = onEdit, modifier = modifier.fillMaxWidth().height(172.dp)
        .onPreviewKeyEvent { if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU && it.type == KeyEventType.KeyUp) { onEdit(); true } else false },
        colors = CardDefaults.colors(containerColor = Panel, focusedContainerColor = PanelSoft, contentColor = TextMain, focusedContentColor = TextMain),
        shape = CardDefaults.shape(RoundedCornerShape(20.dp)), scale = CardDefaults.scale(focusedScale = 1.035f),
        border = CardDefaults.border(focusedBorder = Border(border = androidx.compose.foundation.BorderStroke(2.dp, Mint), shape = RoundedCornerShape(20.dp)))) {
        Column(Modifier.fillMaxSize().padding(22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MediaGlyph(when (source?.kind) { SourceKind.SMB -> Glyph.SERVER; SourceKind.WEBDAV -> Glyph.CLOUD; else -> Glyph.PLUS }, Modifier.size(36.dp))
                Spacer(Modifier.weight(1f))
                Text(when (source?.kind) { SourceKind.SMB -> "SMB"; SourceKind.WEBDAV -> "WEBDAV"; else -> "NEW" }, color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(source?.name ?: "添加媒体源", fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(7.dp))
            Text(source?.caption ?: "连接另一台设备或网络目录", fontSize = 12.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BrowserScreen(state: BrowserState, grid: LazyGridState, focusEnabled: Boolean, app: LocalTvApp, model: MainViewModel, onEdit: () -> Unit) {
    val source = state.source ?: return
    val entries = state.visible
    val initial = remember(state.route, state.loading) { FocusRequester() }
    val back = remember { FocusRequester() }
    val selected = entries.indexOfFirst { it.path == state.selectedPath }.takeIf { it >= 0 } ?: 0
    LaunchedEffect(state.route, state.loading, entries.size, focusEnabled) {
        if (!focusEnabled) return@LaunchedEffect
        if (!state.loading && entries.isNotEmpty()) {
            if (selected !in grid.layoutInfo.visibleItemsInfo.map { it.index }) grid.scrollToItem(selected)
            delay(120); runCatching { initial.requestFocus() }
        } else if (!state.loading) { delay(120); runCatching { back.requestFocus() } }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 38.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TvButton("‹ 返回", Modifier.focusRequester(back), onClick = model::back)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(if (state.path.isEmpty()) source.name else state.path.substringAfterLast('/'), fontSize = 25.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(if (state.path.isEmpty()) source.caption else "${source.name} / ${state.path.replace("/", " / ")}", color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (source.kind != SourceKind.DEMO) { TvButton("源设置", onClick = onEdit); Spacer(Modifier.width(12.dp)) }
            TvButton("刷新", onClick = model::refresh)
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("全部", selected = state.filter == Filter.ALL) { model.filter(Filter.ALL) }
            TvButton("照片", selected = state.filter == Filter.PHOTOS) { model.filter(Filter.PHOTOS) }
            TvButton("视频", selected = state.filter == Filter.VIDEOS) { model.filter(Filter.VIDEOS) }
            Spacer(Modifier.weight(1f))
            Text("${state.entries.count { it.kind == MediaKind.PHOTO }} 张照片  ·  ${state.entries.count { it.kind == MediaKind.VIDEO }} 个视频", color = TextMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                state.loading && entries.isEmpty() -> EmptyMessage("正在连接媒体源…", "文件列表加载后，缩略图会陆续显示", Glyph.CLOUD)
                state.error != null && entries.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyMessage("暂时无法打开", state.error, Glyph.CLOUD)
                    Spacer(Modifier.height(18.dp))
                    TvButton("重新连接", selected = true, onClick = model::refresh)
                }
                entries.isEmpty() -> EmptyMessage("这里还没有媒体", "请检查文件夹，或切换到「全部」查看", Glyph.FOLDER)
                else -> LazyVerticalGrid(columns = GridCells.Fixed(4), state = grid, modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(5.dp)) {
                    itemsIndexed(entries, key = { _, item -> item.path }) { index, item ->
                        MediaTile(source, item, app, (if (index == selected) Modifier.focusRequester(initial) else Modifier)
                            .onFocusChanged { if (it.isFocused) model.select(item) }) { model.open(item) }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("方向键选择   ·   确认键查看   ·   返回键上一级", color = TextMuted, fontSize = 12.sp)
            Text(state.error?.let { "刷新失败，可重试" } ?: "照片与视频，一起浏览", color = if (state.error != null) ErrorColor else TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
fun EmptyMessage(title: String, description: String, glyph: Glyph) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MediaGlyph(glyph, Modifier.size(48.dp), TextMuted)
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 21.sp)
        Spacer(Modifier.height(10.dp))
        Text(description, color = TextMuted, fontSize = 14.sp, maxLines = 3)
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focus.requestFocus() }
        Column(Modifier.width(620.dp).background(Panel, RoundedCornerShape(24.dp)).padding(30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("简单连接，轻松查看", fontSize = 25.sp)
            Text("电脑 / NAS：开启 SMB 共享，在添加源中填写 smb://设备IP/共享名。\n网络相册：开启 WebDAV，填写完整的 http:// 或 https:// 目录地址。\n需要登录时，填写账号和密码；不需要登录则留空。", fontSize = 15.sp, color = TextMuted, lineHeight = 26.sp)
            Text("遥控器操作", color = Mint, fontSize = 16.sp)
            Text("浏览：方向键选择，确认键打开，返回键上一级。\n全屏：上一个按↑，下一个按↓，确认键暂停 / 继续视频。\n视频：← / → 跳转 10 秒，长按连续跳转。\n照片：确认键开始 / 停止幻灯片；菜单键显示信息。", fontSize = 15.sp, color = TextMuted, lineHeight = 26.sp)
            Text("常见照片和视频可直接查看。HEIC、AVIF、HDR 和高码率视频的效果取决于电视系统与硬件。", fontSize = 12.sp, color = TextMuted)
            TvButton("知道了", Modifier.align(Alignment.End).focusRequester(focus), selected = true, onClick = onDismiss)
        }
    }
}

@Composable
private fun ManageDialog(sources: List<Source>, onEdit: (Source) -> Unit, onDelete: (Source) -> Unit, onDismiss: () -> Unit) {
    var deleting by remember { mutableStateOf<Source?>(null) }
    val initial = remember { FocusRequester() }
    LaunchedEffect(sources.size, deleting) { if (deleting == null) { delay(120); runCatching { initial.requestFocus() } } }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(640.dp).heightIn(max = 450.dp).background(Panel, RoundedCornerShape(24.dp)).padding(28.dp)) {
            Text("管理媒体源", fontSize = 25.sp)
            Spacer(Modifier.height(18.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(sources.size, key = { sources[it].id }) { index ->
                    val source = sources[index]
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(source.name, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(source.caption, fontSize = 12.sp, color = TextMuted, maxLines = 1)
                        }
                        TvButton("修改", if (index == 0) Modifier.focusRequester(initial) else Modifier) { onEdit(source) }
                        TvButton("移除") { deleting = source }
                    }
                }
            }
            if (sources.isEmpty()) Text("还没有添加媒体源", color = TextMuted)
            Spacer(Modifier.height(20.dp))
            TvButton("返回", Modifier.align(Alignment.End).then(if (sources.isEmpty()) Modifier.focusRequester(initial) else Modifier), onClick = onDismiss)
        }
    }
    deleting?.let { source ->
        Dialog(onDismissRequest = { deleting = null }) {
            val cancel = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(100); cancel.requestFocus() }
            Column(Modifier.width(480.dp).background(Panel, RoundedCornerShape(24.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("移除「${source.name}」？", fontSize = 22.sp)
                Text("只移除本机的连接记录，源设备的文件会保留。", color = TextMuted, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("取消", Modifier.focusRequester(cancel)) { deleting = null }
                    TvButton("移除") { onDelete(source); deleting = null }
                }
            }
        }
    }
}
