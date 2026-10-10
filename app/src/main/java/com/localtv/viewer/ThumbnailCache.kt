package com.localtv.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.localtv.viewer.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

class ThumbnailCache(context: Context, private val server: MediaServer) {
    private val directory = File(context.cacheDir, "video-thumbnails").apply { mkdirs() }
    private val semaphore = Semaphore(1)
    private val failures = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val context = context.applicationContext

    suspend fun thumbnail(source: Source, item: MediaEntry): File? = withContext(Dispatchers.IO) {
        val key = cacheKey(source, item)
        val file = File(directory, "$key.jpg")
        if (file.exists()) return@withContext file
        if (key in failures) return@withContext null
        semaphore.withPermit {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            while ((context as LocalTvApp).videoActive.get()) kotlinx.coroutines.delay(250)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (file.exists()) return@withPermit file
            val retriever = MediaMetadataRetriever()
            try {
                if (source.kind == SourceKind.DEMO) {
                    context.assets.openFd("demo/${item.path}").use { retriever.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                } else retriever.setDataSource(server.url(source, item), emptyMap())
                val bitmap = if (android.os.Build.VERSION.SDK_INT >= 27)
                    retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                    else retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { original ->
                        val scaled = Bitmap.createScaledBitmap(original, 480, (original.height * 480f / original.width).toInt().coerceAtLeast(1), true)
                        if (scaled !== original) original.recycle()
                        scaled
                    }
                if (bitmap == null) { failures.add(key); return@withPermit null }
                val temp = File(directory, "$key.tmp-${Thread.currentThread().id}")
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                bitmap.recycle()
                if (!temp.renameTo(file)) temp.delete()
                if (directory.listFiles().orEmpty().sumOf { it.length() } > 100L * 1024 * 1024)
                    directory.listFiles().orEmpty().sortedBy { it.lastModified() }.take(50).forEach { it.delete() }
                file.takeIf { it.exists() }
            } catch (_: Exception) { failures.add(key); null }
            finally { runCatching { retriever.release() } }
        }
    }
}
