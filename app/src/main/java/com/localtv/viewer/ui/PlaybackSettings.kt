@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localtv.viewer.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun LoadingIndicator(modifier: Modifier, label: String) {
    var visible by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(700); visible = true; delay(1800); explain = true }
    if (visible) {
        val transition = rememberInfiniteTransition(label = "buffering")
        val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "spinner")
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.size(32.dp)) { drawArc(Color.White, angle, 260f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) }
            if (explain) Text(label, fontSize = 12.sp, color = TextMain)
        }
    }
}

@Composable
internal fun PlaybackSettings(status: PlaybackStatus, control: PlaybackControl, compatible: Boolean,
    onMode: () -> Unit, onDismiss: () -> Unit) {
    val initial = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); initial.requestFocus() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(630.dp).heightIn(max = 475.dp).background(Panel, RoundedCornerShape(18.dp)).padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("播放设置", fontSize = 23.sp, modifier = Modifier.weight(1f))
                TvButton("完成", Modifier.focusRequester(initial), onClick = onDismiss)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text(status.detail.ifBlank { status.engine }, color = TextMuted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton("适合屏幕", selected = !control.fillScreen) { control.fillScreen = false }
                    TvButton("填满屏幕", selected = control.fillScreen) { control.fillScreen = true }
                }
                val player = control.native
                if (player != null) {
                    for ((type, title) in listOf(C.TRACK_TYPE_AUDIO to "音轨", C.TRACK_TYPE_TEXT to "字幕")) {
                        val groups = player.currentTracks.groups.filter { it.type == type }
                        if (groups.isNotEmpty()) {
                            Text(title, fontSize = 12.sp, color = TextMuted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (type == C.TRACK_TYPE_TEXT) TvButton("关闭字幕", selected = player.trackSelectionParameters.disabledTrackTypes.contains(type)) {
                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
                                }
                                groups.forEachIndexed { g, group ->
                                    for (index in 0 until group.length) {
                                        val format = group.getTrackFormat(index)
                                        val name = format.label ?: format.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage }
                                            ?: "$title ${g + 1}.${index + 1}"
                                        TvButton(name, selected = group.isTrackSelected(index), enabled = group.isTrackSupported(index)) {
                                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                                .setTrackTypeDisabled(type, false)
                                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Text("播放方式", fontSize = 12.sp, color = TextMuted)
                TvButton(if (compatible) "切换为原生播放" else "尝试兼容播放", onClick = onMode)
                Text(if (compatible) "兼容模式用于部分旧格式；HDR 文件建议使用原生播放。" else "原生模式使用电视硬件解码，并保留 HDR 色彩信息。", color = TextMuted, fontSize = 11.sp)
                val speed = if (status.readSpeed > 0) String.format(Locale.ROOT, "%.1f Mbps", status.readSpeed) else "等待采样"
                val buffered = ((status.buffered - status.time).coerceAtLeast(0) / 1000)
                Text("${status.engine}  ·  已缓冲 $buffered 秒  ·  读取 $speed", color = TextMuted, fontSize = 11.sp)
                if (status.decoder.isNotBlank()) Text("${status.decoder}  ·  丢帧 ${status.dropped}", color = TextMuted, fontSize = 10.sp)
            }
        }
    }
}
