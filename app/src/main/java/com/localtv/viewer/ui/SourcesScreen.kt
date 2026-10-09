@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.localtv.viewer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.localtv.viewer.data.*
import kotlinx.coroutines.delay

@Composable
fun SourcesScreen(sources: List<Source>, grid: LazyGridState, focusEnabled: Boolean, onOpen: (Source) -> Unit,
    onAdd: () -> Unit, onEdit: (Source) -> Unit, onManage: () -> Unit, onHelp: () -> Unit) {
    val initial = remember { FocusRequester() }
    LaunchedEffect(sources.size, focusEnabled) { if (focusEnabled) { delay(120); runCatching { initial.requestFocus() } } }
    Column(Modifier.fillMaxSize().padding(horizontal = 38.dp, vertical = 26.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("映匣", color = TextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text("资料库", fontSize = 32.sp, fontWeight = FontWeight.Medium)
            }
            TvButton("使用说明", onClick = onHelp)
            if (sources.isNotEmpty()) { Spacer(Modifier.width(8.dp)); TvButton("管理源", onClick = onManage) }
            Spacer(Modifier.width(8.dp))
            TvButton("＋ 添加源", if (sources.isEmpty()) Modifier.focusRequester(initial) else Modifier, selected = true, onClick = onAdd)
        }
        Spacer(Modifier.height(30.dp))
        if (sources.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Text("添加你的照片与影片", fontSize = 27.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Text("连接电脑、NAS 或 WebDAV 目录。\n用手机扫码填写连接信息，即可在电视上浏览。", color = TextMuted, fontSize = 16.sp, lineHeight = 27.sp)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("添加媒体源", selected = true, onClick = onAdd)
                    TvButton("先体验一下", onClick = { onOpen(Source.demo) })
                }
            }
        } else {
            Text("${sources.size} 个设备", fontSize = 13.sp, color = TextMuted)
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(GridCells.Fixed(3), state = grid, modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(5.dp)) {
                itemsIndexed(sources, key = { _, source -> source.id }) { index, source ->
                    SourceTile(source, if (index == 0) Modifier.focusRequester(initial) else Modifier, { onOpen(source) }, { onEdit(source) })
                }
                item("add") { SourceTile(null, Modifier, onAdd, onAdd) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("方向键选择   ·   确认键打开   ·   长按确认修改源", color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun SourceTile(source: Source?, modifier: Modifier, onClick: () -> Unit, onEdit: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Card(onClick = onClick, onLongClick = onEdit, modifier = modifier.fillMaxWidth().height(146.dp)
        .onPreviewKeyEvent { if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU && it.type == KeyEventType.KeyUp) { onEdit(); true } else false },
        colors = CardDefaults.colors(containerColor = Panel, focusedContainerColor = PanelSoft, contentColor = TextMain, focusedContentColor = TextMain),
        shape = CardDefaults.shape(shape), scale = CardDefaults.scale(focusedScale = 1.025f),
        border = CardDefaults.border(focusedBorder = Border(border = BorderStroke(2.dp, TextMain), shape = shape))) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            MediaGlyph(when (source?.kind) { SourceKind.SMB -> Glyph.SERVER; SourceKind.WEBDAV -> Glyph.CLOUD; else -> Glyph.PLUS }, Modifier.size(26.dp), TextMain)
            Spacer(Modifier.weight(1f))
            Text(source?.name ?: "添加媒体源", fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(source?.caption ?: "手机扫码或遥控器填写", fontSize = 11.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
