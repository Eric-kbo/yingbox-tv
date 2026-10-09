package com.localtv.viewer.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.localtv.viewer.LocalTvApp
import com.localtv.viewer.ViewerState
import com.localtv.viewer.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

private data class PlaybackStatus(val loading: Boolean = true, val playing: Boolean = false,
    val time: Long = 0, val length: Long = 0, val error: String? = null, val ended: Boolean = false)
private class PlaybackControl {
    var player: MediaPlayer? = null
    var wantsToPlay = true
}

@Composable
fun ViewerScreen(state: ViewerState, app: LocalTvApp, onNext: (Int) -> Unit, onClose: () -> Unit) {
    val item = state.item
    val focus = remember { FocusRequester() }
    val control = remember { PlaybackControl() }
    var status by remember(item.path) { mutableStateOf(PlaybackStatus()) }
    var photoLoading by remember(item.path) { mutableStateOf(true) }
    var photoError by remember(item.path) { mutableStateOf(false) }
    var retry by remember(item.path) { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0) }
    var slideshow by remember { mutableStateOf(false) }
    var lib by remember { mutableStateOf<LibVLC?>(null) }
    LaunchedEffect(item.kind) {
        if (item.kind == MediaKind.VIDEO && lib == null) lib = withContext(Dispatchers.IO) { app.playerEngine }
    }
    LaunchedEffect(item.path) { control.wantsToPlay = true; showInfo = true; interaction++; delay(80); focus.requestFocus() }
    LaunchedEffect(interaction, item.path, status.playing, status.loading, photoLoading, status.error, photoError) {
        val loading = if (item.kind == MediaKind.PHOTO) photoLoading else status.loading
        if (loading) showInfo = true
        else if (status.error == null && !photoError) { delay(4200); showInfo = false }
    }
    LaunchedEffect(slideshow, item.path, photoLoading) {
        if (slideshow && item.kind == MediaKind.PHOTO && !photoLoading && !photoError) {
            delay(7000)
            if (state.index < state.items.lastIndex) onNext(1) else slideshow = false
        }
    }
    LaunchedEffect(item.path) {
        listOf(state.index - 1, state.index + 1).filter { it in state.items.indices }.map { state.items[it] }
            .filter { it.kind == MediaKind.PHOTO }.forEach { next ->
                Glide.with(app).load(app.mediaServer.url(state.source, next)).signature(ObjectKey(cacheKey(state.source, next)))
                    .override(1920, 1080).fitCenter().preload()
            }
    }

    fun action(code: Int): Boolean {
        interaction++; showInfo = true
        when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> { slideshow = false; onNext(-1) }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { slideshow = false; onNext(1) }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> control.player?.let { player -> if (player.isSeekable) player.setTime((player.time - 10000).coerceAtLeast(0)) }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> control.player?.let { player -> if (player.isSeekable) player.setTime((player.time + 10000).coerceAtMost((player.length - 500).coerceAtLeast(0))) }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> {
                if (photoError || status.error != null) { control.wantsToPlay = true; photoError = false; photoLoading = true; retry++ }
                else if (item.kind == MediaKind.PHOTO) slideshow = !slideshow
                else if (status.ended) {
                    control.wantsToPlay = true; control.player?.let { it.stop(); it.play() }
                } else {
                    control.wantsToPlay = !control.wantsToPlay
                    status = status.copy(playing = control.wantsToPlay)
                    control.player?.let { if (control.wantsToPlay) it.play() else it.pause() }
                }
            }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { control.wantsToPlay = true; control.player?.play() }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { control.wantsToPlay = false; control.player?.pause() }
            AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_INFO -> { showInfo = true }
            else -> return false
        }
        return true
    }

    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
        .onPreviewKeyEvent { event ->
            val code = event.nativeKeyEvent.keyCode
            val supported = code in listOf(AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS,
                AndroidKeyEvent.KEYCODE_MEDIA_NEXT, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY,
                AndroidKeyEvent.KEYCODE_MEDIA_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_REWIND, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_INFO, AndroidKeyEvent.KEYCODE_SPACE)
            if (supported) {
                if (event.type == KeyEventType.KeyDown && (event.nativeKeyEvent.repeatCount == 0 || code == AndroidKeyEvent.KEYCODE_DPAD_LEFT || code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT)) action(code)
                true
            } else false
        }.focusable().pointerInput(item.path) { detectTapGestures(onTap = { interaction++; showInfo = !showInfo }) }) {
        if (item.kind == MediaKind.PHOTO) {
            key(item.path, retry) {
                MediaImage(app.mediaServer.url(state.source, item), cacheKey(state.source, item) + ":$retry", Modifier.fillMaxSize(),
                    onLoaded = { photoLoading = false; photoError = false }, onError = { photoLoading = false; photoError = true; slideshow = false; showInfo = true })
            }
        } else if (lib != null) key(item.path, retry) { VideoPane(lib!!, state.source, item, app, control) { status = it } }

        if ((item.kind == MediaKind.PHOTO && photoLoading) || (item.kind == MediaKind.VIDEO && status.loading && status.error == null)) {
            Box(Modifier.align(Alignment.Center).background(Panel.copy(alpha = .9f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(if (item.kind == MediaKind.PHOTO) "正在打开照片…" else "正在缓冲视频…", color = TextMain, fontSize = 16.sp)
            }
        }
        if (photoError || status.error != null) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp).background(Panel, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (photoError) "无法显示这张照片" else "暂时无法播放", fontSize = 23.sp)
                Spacer(Modifier.height(14.dp))
                Text(status.error ?: "图片格式可能不受当前电视支持，或网络读取失败。", fontSize = 15.sp, color = TextMuted)
                Spacer(Modifier.height(18.dp))
                Text("确认键重试  ·  ↓ 下一个  ·  返回键退出", color = Mint, fontSize = 14.sp)
            }
        }
        if (showInfo || (item.kind == MediaKind.VIDEO && (!status.playing || status.ended) && !status.loading)) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .92f))))
                .padding(horizontal = 40.dp, vertical = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Text("${state.source.name}   ·   ${item.extension.uppercase(Locale.ROOT)}   ·   ${readableSize(item.size)}", fontSize = 12.sp, color = TextMuted)
                    }
                    Spacer(Modifier.width(20.dp))
                    Text("${state.index + 1} / ${state.items.size}", color = Mint, fontSize = 16.sp)
                }
                if (item.kind == MediaKind.VIDEO) {
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .22f))) {
                        if (status.length > 0) Box(Modifier.fillMaxWidth((status.time.toFloat() / status.length).coerceIn(0f, 1f)).fillMaxHeight().background(Mint))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${timeLabel(status.time)} / ${timeLabel(status.length)}", fontSize = 12.sp, color = TextMuted)
                        Text(if (status.ended) "播放结束 · 确认键重播" else if (status.playing) "正在播放" else "已暂停", color = Mint, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(if (item.kind == MediaKind.VIDEO) "↑ 上一个    ↓ 下一个    ← → 跳转    确认 暂停 / 继续    返回 退出"
                    else "↑ 上一张    ↓ 下一个    确认 ${if (slideshow) "停止幻灯片" else "开始幻灯片"}    返回 退出", color = TextMuted, fontSize = 12.sp)
            }
        }
        if (slideshow && item.kind == MediaKind.PHOTO) Text("幻灯片 · 7 秒", Modifier.align(Alignment.TopEnd).padding(30.dp), color = Mint, fontSize = 12.sp)
    }
}

@Composable
private fun VideoPane(lib: LibVLC, source: Source, item: MediaEntry, app: LocalTvApp,
    control: PlaybackControl, onStatus: (PlaybackStatus) -> Unit) {
    val player = remember { MediaPlayer(lib) }
    val latestCallback by rememberUpdatedState(onStatus)
    val key = remember { cacheKey(source, item) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf(PlaybackStatus()) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    var continueOnResume by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        control.player = player
        player.setEventListener { event -> handler.post {
            if (!alive.get()) return@post
            status = when (event.type) {
                MediaPlayer.Event.Playing -> {
                    if (!control.wantsToPlay) player.pause()
                    status.copy(loading = false, playing = control.wantsToPlay, error = null, ended = false)
                }
                MediaPlayer.Event.Paused -> status.copy(playing = false)
                MediaPlayer.Event.Buffering -> status.copy(loading = event.buffering < 100f)
                MediaPlayer.Event.TimeChanged -> status.copy(time = event.timeChanged)
                MediaPlayer.Event.LengthChanged -> status.copy(length = event.lengthChanged)
                MediaPlayer.Event.EndReached -> { control.wantsToPlay = false; app.store.savePosition(key, 0); status.copy(playing = false, loading = false, ended = true) }
                MediaPlayer.Event.EncounteredError -> status.copy(playing = false, loading = false,
                    error = app.mediaServer.lastError ?: "文件格式、编码或音轨可能不受支持，也可能是源连接中断。")
                else -> status
            }
            latestCallback(status)
        } }
        onDispose {
            alive.set(false)
            if (control.player === player) control.player = null
            if (!status.ended && player.time > 0) app.store.savePosition(key, player.time)
            player.setEventListener(null)
            player.stop(); player.detachViews(); player.release()
        }
    }
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    continueOnResume = control.wantsToPlay
                    control.wantsToPlay = false
                    if (player.time > 0) app.store.savePosition(key, player.time)
                    player.pause()
                }
                Lifecycle.Event.ON_START -> if (continueOnResume) { control.wantsToPlay = true; player.play(); continueOnResume = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        VLCVideoLayout(context).apply {
            isFocusable = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            keepScreenOn = true
            player.attachViews(this, null, true, false)
            val media = Media(lib, Uri.parse(app.mediaServer.url(source, item)))
            media.setHWDecoderEnabled(true, false)
            val resume = app.store.position(key)
            if (resume > 0) media.addOption(":start-time=${resume / 1000.0}")
            player.media = media
            media.release()
            player.play()
        }
    })
}

private fun timeLabel(time: Long): String {
    val seconds = (time / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
