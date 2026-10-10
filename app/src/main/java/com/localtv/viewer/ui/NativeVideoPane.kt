@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localtv.viewer.ui

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.localtv.viewer.LocalTvApp
import com.localtv.viewer.data.*
import com.localtv.viewer.playback.RepositoryDataSource
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun NativeVideoPane(source: Source, item: MediaEntry, app: LocalTvApp, control: PlaybackControl,
    onUnsupported: () -> Unit, onStatus: (PlaybackStatus) -> Unit) {
    val player = remember {
        ExoPlayer.Builder(app)
            .setRenderersFactory(DefaultRenderersFactory(app).setEnableDecoderFallback(true))
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 45_000, 1_500, 3_000)
                .setTargetBufferBytes(64 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .setMediaSourceFactory(ProgressiveMediaSource.Factory {
                RepositoryDataSource(app.playbackRepository, source, item) { sample ->
                    control.readSpeed = sample.megabitsPerSecond
                }
            })
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
            }
    }
    val callback by rememberUpdatedState(onStatus)
    val fallback by rememberUpdatedState(onUnsupported)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failure by remember { mutableStateOf<String?>(null) }
    var decoder by remember { mutableStateOf("") }
    var dropped by remember { mutableIntStateOf(0) }
    var continueOnResume by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        control.native = player
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.i("LocalTV-player", "state=$playbackState positionMs=${player.currentPosition} bufferedMs=${player.bufferedPosition}")
                if (playbackState == Player.STATE_ENDED) player.videoDecoderCounters?.let {
                    it.ensureUpdated()
                    Log.i("LocalTV-player", "ended rendered=${it.renderedOutputBufferCount} dropped=${it.droppedBufferCount}")
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { Log.i("LocalTV-player", "playing=$isPlaying") }
            override fun onPlayerError(error: PlaybackException) {
                Log.e("LocalTV-player", "native error code=${error.errorCodeName}", error)
                val unsupported = error.errorCode in setOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
                // Never silently reroute HDR through a renderer with different color handling.
                val video = player.videoFormat ?: player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.getTrackFormat(0)
                val hdr = video?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || video?.colorInfo?.let {
                    it.colorTransfer == C.COLOR_TRANSFER_ST2084 || it.colorTransfer == C.COLOR_TRANSFER_HLG } == true
                if (unsupported && !hdr) fallback()
                else failure = when {
                    error.errorCode in 2000..2999 -> "源设备读取失败或连接中断。请重试；菜单中的读取速度可帮助判断网络是否足够。"
                    hdr -> "电视的原生解码器无法播放这个 HDR 文件。可以在菜单中尝试兼容播放。"
                    else -> "当前编码无法播放，请在菜单中尝试兼容播放。"
                }
            }
        }
        val analytics = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                decoder = decoderName
                Log.i("LocalTV-player", "decoder=$decoderName initializationMs=$initializationDurationMs")
            }
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                dropped += droppedFrames
                Log.w("LocalTV-player", "dropped=$droppedFrames elapsedMs=$elapsedMs")
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                Log.i("LocalTV-player", "first-frame")
            }
        }
        player.addListener(listener); player.addAnalyticsListener(analytics)
        player.setMediaItem(MediaItem.fromUri(app.mediaServer.url(source, item)))
        player.seekTo(app.store.position(cacheKey(source, item)))
        player.playWhenReady = control.wantsToPlay
        player.prepare()
        onDispose {
            if (control.native === player) control.native = null
            if (player.playbackState != Player.STATE_ENDED && player.currentPosition > 0)
                app.store.savePosition(cacheKey(source, item), player.currentPosition)
            else if (player.playbackState == Player.STATE_ENDED) app.store.savePosition(cacheKey(source, item), 0)
            player.removeListener(listener); player.removeAnalyticsListener(analytics)
            player.release()
        }
    }
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { continueOnResume = control.wantsToPlay; control.wantsToPlay = false; player.pause() }
                Lifecycle.Event.ON_START -> if (continueOnResume) { control.wantsToPlay = true; player.play(); continueOnResume = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player) {
        while (true) {
            val format = player.videoFormat
            val color = if (format?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) "Dolby Vision" else when (format?.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_HLG -> "HDR · HLG"
                C.COLOR_TRANSFER_ST2084 -> if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) "Dolby Vision" else "HDR10"
                else -> "SDR"
            }
            val detail = if (format == null) "" else listOfNotNull(format.width.takeIf { it > 0 }?.let { "${it} × ${format.height}" },
                format?.frameRate?.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.0f fps", it) }, color).joinToString(" · ")
            val ended = player.playbackState == Player.STATE_ENDED
            player.videoDecoderCounters?.ensureUpdated()
            if (ended) control.wantsToPlay = false
            callback(PlaybackStatus(loading = failure == null && player.playbackState in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING),
                playing = player.isPlaying, time = player.currentPosition, length = player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
                error = failure, ended = ended, buffered = player.bufferedPosition, detail = detail, decoder = decoder,
                dropped = player.videoDecoderCounters?.droppedBufferCount ?: dropped,
                engine = "原生播放", readSpeed = control.readSpeed,
                tracksVersion = player.currentTracks.hashCode() xor player.trackSelectionParameters.hashCode()))
            delay(400)
        }
    }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        // PlayerView's default SurfaceView preserves the hardware HDR/Dolby Vision
        // signal and avoids converting frames through an SDR TextureView/GL surface.
        PlayerView(context).apply {
            useController = false; isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            keepScreenOn = true; this.player = player
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }, update = { view -> view.resizeMode = if (control.fillScreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
        onRelease = { it.player = null })
}
