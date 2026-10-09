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
            Text("使用说明", fontSize = 25.sp)
            Text("添加媒体源：优先用手机扫码填写；手机与电视需在同一网络。\n电脑 / NAS：开启 SMB 共享，地址为 smb://设备IP/共享名。\n网络相册：开启 WebDAV，填写完整的 http:// 或 https:// 目录地址。\n需要登录时，填写账号和密码；不需要登录则留空。", fontSize = 15.sp, color = TextMuted, lineHeight = 26.sp)
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
