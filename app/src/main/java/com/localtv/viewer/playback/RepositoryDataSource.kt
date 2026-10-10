package com.localtv.viewer.playback

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.localtv.viewer.data.*
import java.io.EOFException
import java.io.IOException

/** Each extractor connection owns its handle, including independent tail/seek reads. */
@UnstableApi
class RepositoryDataSource(private val repository: MediaRepository, private val source: Source,
    private val item: MediaEntry, private val onSample: (ReadSample) -> Unit = {}) : BaseDataSource(true) {
    private var handle: ReadHandle? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var bytes = 0L
    private var waitMs = 0L
    private var windowStart = 0L
    override fun getUri(): Uri? = uri
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val opening = SystemClock.elapsedRealtime()
        try {
            val size = repository.size(source, item)
            if (size >= 0 && dataSpec.position > size) throw EOFException("读取位置超过文件大小，请刷新目录")
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                else if (size >= 0) size - dataSpec.position else C.LENGTH_UNSET.toLong()
            handle = repository.open(source, item, dataSpec.position, remaining.takeIf { it >= 0 })
            opened = true; bytes = 0; waitMs = 0; windowStart = SystemClock.elapsedRealtime()
            transferStarted(dataSpec)
            Log.i("LocalTV-read", "openMs=${SystemClock.elapsedRealtime() - opening} position=${dataSpec.position} length=$remaining")
            return remaining
        } catch (error: Exception) { throw IOException(friendlyError(error), error) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            val start = SystemClock.elapsedRealtime()
            val read = handle!!.input.read(buffer, offset, if (remaining < 0) length else minOf(length.toLong(), remaining).toInt())
            waitMs += SystemClock.elapsedRealtime() - start
            if (read < 0) {
                if (remaining > 0) throw EOFException("媒体文件读取提前中断，请重试或检查源设备")
                return C.RESULT_END_OF_INPUT
            }
            if (read > 0) {
                if (remaining >= 0) remaining -= read
                bytes += read; bytesTransferred(read)
                if (bytes >= 4L * 1024 * 1024) sample()
            }
            return read
        } catch (error: IOException) { throw error }
        catch (error: Exception) { throw IOException(friendlyError(error), error) }
    }
    private fun sample() {
        if (bytes == 0L) return
        val sample = ReadSample(bytes, waitMs, SystemClock.elapsedRealtime() - windowStart)
        // No source addresses, account details, media titles or tokens in diagnostics.
        Log.i("LocalTV-read", "${source.kind} bytes=${sample.bytes} readMs=${sample.readMs} elapsedMs=${sample.elapsedMs}")
        onSample(sample)
        bytes = 0; waitMs = 0; windowStart = SystemClock.elapsedRealtime()
    }
    override fun close() {
        try { sample(); handle?.close() }
        finally { handle = null; uri = null; if (opened) { opened = false; transferEnded() } }
    }
}

data class ReadSample(val bytes: Long, val readMs: Long, val elapsedMs: Long) {
    /** Time actually awaiting reads, excluding deliberate pauses when the buffer is full. */
    val megabitsPerSecond: Double get() = bytes * .008 / readMs.coerceAtLeast(1)
}
