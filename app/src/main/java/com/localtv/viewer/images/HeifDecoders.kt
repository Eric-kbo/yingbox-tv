package com.localtv.viewer.images

import android.graphics.Bitmap
import android.os.Build
import com.bumptech.glide.Glide
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import com.radzivon.bartoshyk.avif.coder.Coder
import com.radzivon.bartoshyk.avif.coder.PreferredColorConfig
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** Keep the fast platform path for supported images, then try software HEVC for
 * vendor HEIF grid/bit-depth failures. Glide rewinds input between decoder attempts. */
object HeifDecoders {
    private const val MAX_BYTES = 64 * 1024 * 1024
    private val decodeLock = Any()
    fun install(glide: Glide) {
        if (Build.VERSION.SDK_INT < 24) return
        fun bitmap(bytes: ByteArray, width: Int, height: Int): Resource<Bitmap> = synchronized(decodeLock) {
            try {
                val coder = Coder()
                val size = coder.getSize(bytes) ?: throw IOException("HEIC 文件没有有效图像")
                if (size.width.toLong() * size.height > 100_000_000L) throw IOException("照片分辨率超过解码上限")
                val result = coder.decodeSampled(bytes, width.coerceIn(1, 3840), height.coerceIn(1, 2160), PreferredColorConfig.RGBA_8888)
                android.util.Log.i("LocalTV-image", "software HEIF ${size.width}x${size.height} -> ${result.width}x${result.height}")
                BitmapResource.obtain(result, glide.bitmapPool)!!
            } catch (error: Exception) { throw IOException("HEIC 软件解码失败", error) }
            catch (error: LinkageError) { throw IOException("HEIC 解码器不可用", error) }
        }
        glide.registry.append(ByteBuffer::class.java, Bitmap::class.java, object : ResourceDecoder<ByteBuffer, Bitmap> {
            override fun handles(source: ByteBuffer, options: Options): Boolean {
                val copy = source.asReadOnlyBuffer(); val header = ByteArray(minOf(copy.remaining(), 64)); copy.get(header)
                return isHeif(header)
            }
            override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<Bitmap> {
                val copy = source.asReadOnlyBuffer()
                if (copy.remaining() > MAX_BYTES) throw IOException("HEIC 文件过大")
                val bytes = ByteArray(copy.remaining()); copy.get(bytes)
                return bitmap(bytes, width, height)
            }
        })
        glide.registry.append(InputStream::class.java, Bitmap::class.java, object : ResourceDecoder<InputStream, Bitmap> {
            override fun handles(source: InputStream, options: Options): Boolean {
                if (!source.markSupported()) return false
                source.mark(64)
                val header = ByteArray(64)
                var n = 0
                try { while (n < header.size) { val read = source.read(header, n, header.size - n); if (read <= 0) break; n += read } }
                finally { source.reset() }
                return isHeif(header.copyOf(n))
            }
            override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Bitmap> {
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(chunk); if (n < 0) break
                    if (output.size() + n > MAX_BYTES) throw IOException("HEIC 文件过大")
                    output.write(chunk, 0, n)
                }
                return bitmap(output.toByteArray(), width, height)
            }
        })
    }
    internal fun isHeif(header: ByteArray): Boolean {
        if (header.size < 12 || String(header, 4, 4, Charsets.US_ASCII) != "ftyp") return false
        return (8..header.size - 4 step 4).any {
            String(header, it, 4, Charsets.US_ASCII) in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
        }
    }
}
