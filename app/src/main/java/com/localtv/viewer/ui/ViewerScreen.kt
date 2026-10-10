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

internal data class PlaybackStatus(val loading: Boolean = true, val playing: Boolean = false,
    val time: Long = 0, val length: Long = 0, val error: String? = null, val ended: Boolean = false,
    val buffered: Long = 0, val detail: String = "", val decoder: String = "", val dropped: Int = 0,
    val engine: String = "兼容播放", val readSpeed: Double = 0.0, val tracksVersion: Int = 0)
internal class PlaybackControl {
    var player: MediaPlayer? = null
    var native: androidx.media3.exoplayer.ExoPlayer? = null
    var wantsToPlay = true
    @Volatile var readSpeed = 0.0
    var fillScreen by mutableStateOf(false)
    val time: Long get() = native?.currentPosition ?: player?.time ?: 0
    val length: Long get() = native?.duration?.coerceAtLeast(0) ?: player?.length ?: 0
    val seekable: Boolean get() = native?.isCurrentMediaItemSeekable ?: (player?.isSeekable == true)
    fun seek(time: Long) { native?.seekTo(time); player?.setTime(time) }
    fun play() { native?.play(); player?.play() }
    fun pause() { native?.pause(); player?.pause() }
    fun replay() { seek(0); play() }
}

@Composable
fun ViewerScreen(state: ViewerState, app: LocalTvApp, onNext: (Int) -> Unit, onClose: () -> Unit) {
    val item = state.item
    val focus = remember { FocusRequester() }
    val control = remember(state.source.id, item.path) { PlaybackControl() }
    var status by remember(item.path) { mutableStateOf(PlaybackStatus()) }
    var photoLoading by remember(item.path) { mutableStateOf(true) }
    var photoError by remember(item.path) { mutableStateOf(false) }
    var retry by remember(item.path) { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0) }
    var slideshow by remember { mutableStateOf(false) }
    var settings by remember(item.path) { mutableStateOf(false) }
    var compatible by remember(item.path) { mutableStateOf(item.extension in setOf("avi", "wmv", "asf", "flv", "rm", "rmvb", "vob")) }
    var seekTarget by remember(item.path) { mutableStateOf<Long?>(null) }
    var lib by remember { mutableStateOf<LibVLC?>(null) }
    LaunchedEffect(item.kind, compatible) {
        if (item.kind == MediaKind.VIDEO && compatible && lib == null) lib = withContext(Dispatchers.IO) { app.playerEngine }
    }
    DisposableEffect(item.path) {
        app.videoActive.set(item.kind == MediaKind.VIDEO)
        onDispose { app.videoActive.set(false) }
    }
    LaunchedEffect(seekTarget) {
        seekTarget?.let { target -> delay(300); control.seek(target); seekTarget = null }
    }
    LaunchedEffect(settings) { if (!settings) { delay(80); focus.requestFocus() } }
    LaunchedEffect(item.path) { control.wantsToPlay = true; showInfo = true; interaction++; delay(80); focus.requestFocus() }
    LaunchedEffect(interaction, item.path, status.playing, status.loading, photoLoading, status.error, photoError) {
        val loading = if (item.kind == MediaKind.PHOTO) photoLoading else status.loading
        if (loading || seekTarget != null || settings) showInfo = true
        else if (status.error == null && !photoError) { delay(4200); showInfo = false }
    }
    LaunchedEffect(slideshow, item.path, photoLoading) {
        if (slideshow && item.kind == MediaKind.PHOTO && !photoLoading && !photoError) {
            delay(7000)
            if (state.index < state.items.lastIndex) onNext(1) else slideshow = false
        }
    }
    DisposableEffect(item.path) {
        val preloads = if (item.kind == MediaKind.PHOTO) listOf(state.index - 1, state.index + 1).filter { it in state.items.indices }.map { state.items[it] }
            .filter { it.kind == MediaKind.PHOTO }.map { next ->
                Glide.with(app).load(app.mediaServer.url(state.source, next)).signature(ObjectKey(cacheKey(state.source, next) + ":decoder-v2"))
                    .override(1920, 1080).fitCenter().preload()
            } else emptyList()
        onDispose { preloads.forEach { Glide.with(app).clear(it) } }
    }

    fun action(code: Int): Boolean {
        interaction++; showInfo = true
        when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> { slideshow = false; onNext(-1) }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { slideshow = false; onNext(1) }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> if (control.seekable) seekTarget = ((seekTarget ?: control.time) - 10000).coerceAtLeast(0)
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (control.seekable) seekTarget = ((seekTarget ?: control.time) + 10000).coerceAtMost((control.length - 500).coerceAtLeast(0))
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> {
                if (photoError || status.error != null) { control.wantsToPlay = true; photoError = false; photoLoading = true; retry++ }
                else if (item.kind == MediaKind.PHOTO) slideshow = !slideshow
                else if (status.ended) {
                    control.wantsToPlay = true; control.replay()
                } else {
                    control.wantsToPlay = !control.wantsToPlay
                    status = status.copy(playing = control.wantsToPlay)
                    if (control.wantsToPlay) control.play() else control.pause()
                }
            }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { control.wantsToPlay = true; control.play() }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { control.wantsToPlay = false; control.pause() }
            AndroidKeyEvent.KEYCODE_MENU -> { if (item.kind == MediaKind.VIDEO) settings = true }
            AndroidKeyEvent.KEYCODE_INFO -> { showInfo = true }
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
        } else key(item.path, retry, compatible) {
            if (!compatible) NativeVideoPane(state.source, item, app, control,
                onUnsupported = { compatible = true; status = PlaybackStatus() }) { status = it }
            else if (lib != null) VideoPane(lib!!, state.source, item, app, control) { status = it }
        }

        if ((item.kind == MediaKind.PHOTO && photoLoading) || (item.kind == MediaKind.VIDEO && status.loading && status.error == null)) {
            LoadingIndicator(Modifier.align(Alignment.Center), if (item.kind == MediaKind.PHOTO) "正在打开照片…" else "正在缓冲视频…")
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
                .padding(horizontal = 40.dp, vertical = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Text(if (item.kind == MediaKind.VIDEO && status.detail.isNotBlank()) status.detail else "${state.source.name}   ·   ${item.extension.uppercase(Locale.ROOT)}", fontSize = 11.sp, color = TextMuted)
                    }
                    Spacer(Modifier.width(20.dp))
                    Text("${state.index + 1} / ${state.items.size}", color = TextMuted, fontSize = 13.sp)
                }
                if (item.kind == MediaKind.VIDEO) {
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth().height(if (seekTarget != null) 5.dp else 3.dp).background(Color.White.copy(alpha = .18f))) {
                        if (status.length > 0) {
                            Box(Modifier.fillMaxWidth((status.buffered.toFloat() / status.length).coerceIn(0f, 1f)).fillMaxHeight().background(Color.White.copy(alpha = .38f)))
                            Box(Modifier.fillMaxWidth(((seekTarget ?: status.time).toFloat() / status.length).coerceIn(0f, 1f)).fillMaxHeight().background(Color.White))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${timeLabel(seekTarget ?: status.time)} / ${timeLabel(status.length)}", fontSize = 12.sp, color = TextMain)
                        Text(if (seekTarget != null) "跳转至 ${timeLabel(seekTarget!!)}" else if (status.loading) "正在缓冲" else if (status.ended) "播放结束 · 确认键重播" else if (status.playing) "正在播放" else "已暂停", color = TextMain, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(if (item.kind == MediaKind.VIDEO) "确认 暂停 / 继续    ← → 跳转    ↑ ↓ 切换    菜单 播放设置"
                    else "↑ 上一张    ↓ 下一个    确认 ${if (slideshow) "停止幻灯片" else "开始幻灯片"}    返回 退出", color = TextMuted, fontSize = 12.sp)
            }
        }
        if (slideshow && item.kind == MediaKind.PHOTO) Text("幻灯片 · 7 秒", Modifier.align(Alignment.TopEnd).padding(30.dp), color = Mint, fontSize = 12.sp)
    }
    if (settings) PlaybackSettings(status, control, compatible, onMode = {
        app.store.savePosition(cacheKey(state.source, item), if (status.ended) 0 else control.time)
        control.pause()
        compatible = !compatible; retry++; status = PlaybackStatus(); settings = false
    }, onDismiss = { settings = false; interaction++ })
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
            player.detachViews()
            app.playerCleanup.execute { runCatching { player.stop(); player.release() } }
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
    }, update = { player.videoScale = if (control.fillScreen) MediaPlayer.ScaleType.SURFACE_FILL else MediaPlayer.ScaleType.SURFACE_BEST_FIT })
}

private fun timeLabel(time: Long): String {
    val seconds = (time / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
