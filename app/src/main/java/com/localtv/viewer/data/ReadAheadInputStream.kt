package com.localtv.viewer.data

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, positional read-ahead. Never reads past the requested range/EOF.
 * One queued block overlaps decoding with the next SMB round trip; small decoder
 * reads are served from memory. Closing cancels the pending block immediately. */
internal class ReadAheadInputStream(start: Long, private val length: Long,
    private val executor: ExecutorService, private val blockSize: Int = 1024 * 1024,
    private val readBlock: (ByteArray, Long, Int) -> Int) : InputStream() {
    private data class Block(val bytes: ByteArray, val count: Int)
    private val closed = AtomicBoolean(false)
    private var position = start
    private var fetched = 0L
    private var buffer = ByteArray(0)
    private var cursor = 0
    private var limit = 0
    @Volatile private var pending: Future<Block>? = null
    init { require(start >= 0 && length >= 0 && blockSize > 0); schedule() }

    private fun schedule() {
        if (closed.get() || fetched >= length) { pending = null; return }
        val offset = position
        val count = minOf(blockSize.toLong(), length - fetched).toInt()
        val task = executor.submit<Block> {
            if (closed.get()) throw IOException("读取已取消")
            val bytes = ByteArray(count)
            val n = readBlock(bytes, offset, count)
            if (n <= 0) throw IOException("媒体文件读取提前中断，请刷新目录后重试")
            Block(bytes, n)
        }
        pending = task
        if (closed.get()) task.cancel(true)
    }
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }
    override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
        if (offset < 0 || count < 0 || offset > bytes.size - count) throw IndexOutOfBoundsException()
        if (count == 0) return 0
        if (closed.get()) throw IOException("读取已关闭")
        if (cursor == limit) {
            val next = pending ?: return -1
            val block = try { next.get() }
            catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("读取已取消", error) }
            catch (error: Exception) { throw IOException("无法读取下一段媒体", error.cause ?: error) }
            if (closed.get()) throw IOException("读取已关闭")
            buffer = block.bytes; cursor = 0; limit = block.count
            fetched += block.count; position += block.count
            schedule()
        }
        val n = minOf(count, limit - cursor)
        buffer.copyInto(bytes, offset, cursor, cursor + n); cursor += n
        return n
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) { pending?.cancel(true); pending = null }
    }
}
