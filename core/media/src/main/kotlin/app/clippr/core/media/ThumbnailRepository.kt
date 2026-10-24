package app.clippr.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Produces small frames for the timeline, media library and project cards.
 *
 * - Requests are quantized into time buckets ([bucketFor]) so neighbouring zoom levels reuse
 *   the same images.
 * - A memory LRU sized to an eighth of the heap serves repeat draws; JPEGs in the cache
 *   directory survive restarts and can be evicted by the system at any time.
 * - Decoding runs on at most two background threads, and only one [MediaMetadataRetriever]
 *   is kept open per file, so scrolling a long timeline never decodes many files at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailRepository(context: Context) {

    private val appContext = context.applicationContext
    private val diskDir = File(appContext.cacheDir, "thumbnails").apply { mkdirs() }
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val retrieverLock = Mutex()
    private val retrievers = LinkedHashMap<String, MediaMetadataRetriever>(4, 0.75f, true)

    /** Returns a cached frame immediately, or null if it has not been decoded yet. */
    fun peek(asset: MediaAsset, sourceTimeUs: Long, heightPx: Int): Bitmap? =
        memory.get(key(asset, sourceTimeUs, heightPx))

    /** Returns a frame of [asset] near [sourceTimeUs], [heightPx] tall, decoding it if needed. */
    suspend fun frame(asset: MediaAsset, sourceTimeUs: Long, heightPx: Int): Bitmap? {
        if (asset.type == MediaType.AUDIO) return null
        val key = key(asset, sourceTimeUs, heightPx)
        memory.get(key)?.let { return it }
        return withContext(decodeDispatcher) {
            memory.get(key)?.let { return@withContext it }
            val file = File(diskDir, diskName(key))
            val bitmap = if (file.exists()) {
                BitmapFactory.decodeFile(file.path)
            } else {
                decode(asset, bucketFor(sourceTimeUs), heightPx)?.also { writeJpeg(it, file) }
            }
            bitmap?.also { memory.put(key, it) }
        }
    }

    /** Releases open decoders, e.g. when leaving the editor. */
    suspend fun releaseDecoders() = retrieverLock.withLock {
        retrievers.values.forEach { runCatching { it.release() } }
        retrievers.clear()
    }

    fun trimMemory() = memory.evictAll()

    private suspend fun decode(asset: MediaAsset, timeUs: Long, heightPx: Int): Bitmap? = when (asset.type) {
        MediaType.IMAGE -> decodeImage(asset, heightPx)
        MediaType.VIDEO -> decodeVideoFrame(asset, timeUs, heightPx)
        MediaType.AUDIO -> null
    }

    private fun decodeImage(asset: MediaAsset, heightPx: Int): Bitmap? = try {
        val uri = Uri.parse(asset.uri)
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val shortSide = minOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (shortSide / (sample * 2) >= heightPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val rotation = try {
            resolver.openInputStream(uri)?.use { MediaProbe.exifRotation(ExifInterface(it)) } ?: 0
        } catch (e: Exception) {
            0
        }
        raw?.let { scaleToHeight(rotate(it, rotation), heightPx) }
    } catch (e: Exception) {
        null
    }

    private suspend fun decodeVideoFrame(asset: MediaAsset, timeUs: Long, heightPx: Int): Bitmap? =
        retrieverLock.withLock {
            try {
                val retriever = retrievers.getOrPut(asset.uri) {
                    MediaMetadataRetriever().apply { setDataSource(appContext, Uri.parse(asset.uri)) }
                }
                while (retrievers.size > MAX_OPEN_RETRIEVERS) {
                    val eldest = retrievers.entries.first()
                    runCatching { eldest.value.release() }
                    retrievers.remove(eldest.key)
                }
                val w = asset.displayWidth.coerceAtLeast(1)
                val h = asset.displayHeight.coerceAtLeast(1)
                val targetH = heightPx.coerceAtMost(h)
                val targetW = max(1, (w.toFloat() * targetH / h).roundToInt())
                val t = timeUs.coerceIn(0, (asset.durationUs - 1).coerceAtLeast(0))
                // getScaledFrameAtTime takes dimensions before rotation is applied.
                val (reqW, reqH) = if (asset.rotationDegrees % 180 != 0) targetH to targetW else targetW to targetH
                retriever.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, reqW, reqH)
                    ?: retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleToHeight(it, targetH) }
            } catch (e: Exception) {
                retrievers.remove(asset.uri)?.let { runCatching { it.release() } }
                null
            }
        }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun scaleToHeight(src: Bitmap, heightPx: Int): Bitmap {
        if (src.height <= heightPx) return src
        val w = max(1, (src.width.toFloat() * heightPx / src.height).roundToInt())
        return Bitmap.createScaledBitmap(src, w, heightPx, true)
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (e: Exception) {
            file.delete()
        }
    }

    private fun key(asset: MediaAsset, sourceTimeUs: Long, heightPx: Int): String {
        val t = if (asset.type == MediaType.IMAGE) 0L else bucketFor(sourceTimeUs)
        return "${asset.uri}|$t|$heightPx"
    }

    private fun diskName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".jpg"
    }

    companion object {
        private const val MAX_OPEN_RETRIEVERS = 3

        /** Smallest time bucket for thumbnails. */
        const val BASE_BUCKET_US = 250_000L

        /** Quantizes a source time to the thumbnail grid. */
        fun bucketFor(timeUs: Long): Long = (timeUs.coerceAtLeast(0) / BASE_BUCKET_US) * BASE_BUCKET_US

        /**
         * Bucket size for tiles that each span [tileDurationUs]: the smallest power-of-two
         * multiple of [BASE_BUCKET_US] that is at least the tile duration.
         */
        fun bucketSizeForTile(tileDurationUs: Long): Long {
            var b = BASE_BUCKET_US
            while (b < tileDurationUs && b < Long.MAX_VALUE / 2) b *= 2
            return b
        }
    }
}
