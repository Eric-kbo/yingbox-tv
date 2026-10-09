@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.localtv.viewer.ui

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.localtv.viewer.LocalTvApp
import com.localtv.viewer.data.*

@Composable
fun MediaTile(source: Source, item: MediaEntry, app: LocalTvApp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var thumbnail by remember(item.fingerprint, source.id) { mutableStateOf<java.io.File?>(null) }
    if (item.kind == MediaKind.VIDEO) LaunchedEffect(item.fingerprint, source.id) { thumbnail = app.thumbnails.thumbnail(source, item) }
    Card(onClick = onClick, modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.colors(containerColor = Panel, focusedContainerColor = PanelSoft, contentColor = TextMain, focusedContentColor = TextMain),
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)), scale = CardDefaults.scale(focusedScale = 1.045f),
        border = CardDefaults.border(focusedBorder = Border(border = androidx.compose.foundation.BorderStroke(2.dp, Mint), shape = RoundedCornerShape(14.dp)))) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(if (item.kind == MediaKind.FOLDER) PanelSoft else Color(0xFF18202C)), contentAlignment = Alignment.Center) {
                MediaGlyph(when (item.kind) { MediaKind.FOLDER -> Glyph.FOLDER; MediaKind.PHOTO -> Glyph.PHOTO; MediaKind.VIDEO -> Glyph.VIDEO }, Modifier.size(if (item.kind == MediaKind.FOLDER) 48.dp else 36.dp), if (item.kind == MediaKind.FOLDER) Mint else TextMuted.copy(alpha = .45f))
                if (item.kind == MediaKind.PHOTO) MediaImage(app.mediaServer.url(source, item), cacheKey(source, item), Modifier.fillMaxSize(), crop = true)
                if (thumbnail != null) MediaImage(thumbnail!!, cacheKey(source, item), Modifier.fillMaxSize(), crop = true)
                if (item.kind != MediaKind.FOLDER) {
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = .58f), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                        Text(if (item.kind == MediaKind.VIDEO) "▶ 视频" else item.extension.uppercase(), color = Color.White, fontSize = 9.sp)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(item.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(if (item.kind == MediaKind.FOLDER) "文件夹" else readableSize(item.size).ifBlank { "点击查看" }, fontSize = 10.sp, color = TextMuted, maxLines = 1)
            }
        }
    }
}

@Composable
fun MediaImage(data: Any, signature: String, modifier: Modifier = Modifier, crop: Boolean = false,
    onLoaded: () -> Unit = {}, onError: () -> Unit = {}) {
    val loaded by rememberUpdatedState(onLoaded)
    val failed by rememberUpdatedState(onError)
    AndroidView(modifier = modifier, factory = { context -> ImageView(context).apply {
        isFocusable = false
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
    } }, update = { image ->
        val identity = "$data:$signature:$crop"
        if (image.tag != identity) {
            image.tag = identity
            image.scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            val request = Glide.with(image).load(data).signature(ObjectKey(signature)).dontAnimate()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean { failed(); return false }
                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean { loaded(); return false }
                })
            if (crop) request.override(640, 400).centerCrop().into(image) else request.fitCenter().into(image)
        }
    }, onRelease = { image -> Glide.with(image).clear(image) })
}
