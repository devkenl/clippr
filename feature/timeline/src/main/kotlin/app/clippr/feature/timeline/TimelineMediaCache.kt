package app.clippr.feature.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.clippr.core.media.ThumbnailRepository
import app.clippr.core.media.Waveform
import app.clippr.core.media.WaveformRepository
import app.clippr.core.model.MediaAsset
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges the media repositories to the timeline's draw phase.
 *
 * Drawing must not suspend or write snapshot state, so [thumbnail] and [waveform] only return
 * what is already cached and enqueue a background load for anything missing. When a load
 * finishes, [version] changes, which invalidates the canvas that read it.
 */
@Stable
class TimelineMediaCache(
    private val thumbnails: ThumbnailRepository,
    private val waveforms: WaveformRepository,
) {
    private sealed interface Request {
        data class Thumb(val asset: MediaAsset, val timeUs: Long, val heightPx: Int) : Request
        data class Wave(val asset: MediaAsset) : Request
    }

    private val requests = Channel<Request>(capacity = Channel.UNLIMITED)
    private val pending = ConcurrentHashMap.newKeySet<Request>()
    // Small LRU of Compose wrappers; the bitmaps themselves are owned by ThumbnailRepository's cache.
    private val images = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 96
    }
    private val failed = ConcurrentHashMap.newKeySet<Request>()

    /** Read this in draw code so new media triggers a redraw. */
    var version by mutableIntStateOf(0)
        private set

    fun thumbnail(asset: MediaAsset, sourceTimeUs: Long, heightPx: Int): ImageBitmap? {
        val bucket = ThumbnailRepository.bucketFor(sourceTimeUs)
        val key = "${asset.uri}|$bucket|$heightPx"
        images[key]?.let { return it }
        thumbnails.peek(asset, bucket, heightPx)?.let { bmp ->
            return bmp.asImageBitmap().also { images[key] = it }
        }
        // Draw code runs on the main thread only, so the LinkedHashMap needs no locking.
        val req = Request.Thumb(asset, bucket, heightPx)
        if (req !in failed && pending.add(req)) requests.trySend(req)
        return null
    }

    fun waveform(asset: MediaAsset): Waveform? {
        waveforms.peek(asset)?.let { return it }
        val req = Request.Wave(asset)
        if (req !in failed && pending.add(req)) requests.trySend(req)
        return null
    }

    /** Processes queued loads until cancelled. Thumbnails load with bounded parallelism. */
    suspend fun run() = coroutineScope {
        for (req in requests) {
            launch {
                try {
                    val result: Any? = when (req) {
                        is Request.Thumb -> thumbnails.frame(req.asset, req.timeUs, req.heightPx)
                        is Request.Wave -> waveforms.waveform(req.asset)
                    }
                    // Remember failures so undecodable media is not retried on every frame.
                    if (result == null) failed += req
                } finally {
                    pending.remove(req)
                }
                version++
            }
        }
    }
}

@Composable
fun rememberTimelineMediaCache(thumbnails: ThumbnailRepository, waveforms: WaveformRepository): TimelineMediaCache {
    val cache = remember(thumbnails, waveforms) { TimelineMediaCache(thumbnails, waveforms) }
    LaunchedEffect(cache) { cache.run() }
    return cache
}
