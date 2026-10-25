package app.clippr.core.render

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.render.RenderPlan
import app.clippr.core.model.render.VideoSegment
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Estimates whether the device's hardware video decoders can play several clips at once in real
 * time, as the live preview must during a transition or with overlay layers.
 *
 * Decoders advertise their throughput as "performance points" (frame size × frame rate). Two
 * 4K 60 fps clips need roughly the throughput of one 4K 120 fps stream, which many tablets don't
 * have. When the preview would need more than the decoders can deliver, playback stalls or
 * freezes, and the stuck player can keep its decoders until the app restarts, so it is better
 * to know beforehand and simplify the preview instead.
 */
class DecoderBudget(private val context: Context) {

    private data class Stream(val mime: String, val width: Int, val height: Int, val fps: Float)

    private class Interval(val start: Long, val end: Long, val asset: MediaAsset, val speed: Float)

    private val codecByUri = ConcurrentHashMap<String, String>()
    private val verdicts = ConcurrentHashMap<List<Stream>, Boolean>()
    private val decodersByMime = ConcurrentHashMap<String, List<MediaCodecInfo.VideoCapabilities>>()

    /** True if every moment of [plan] can be decoded in real time. */
    fun canPreview(plan: RenderPlan): Boolean {
        if (!plan.isMultiLayer) return true
        val peak = peakConcurrentStreams(plan)
        if (peak.size < 2) return true
        val key = peak.sortedWith(compareBy({ it.mime }, { it.width }, { it.height }, { it.fps }))
        return verdicts.getOrPut(key) {
            runCatching { key.groupBy { it.mime }.all { (mime, streams) -> supports(mime, streams) } }.getOrDefault(true)
        }
    }

    /** The largest set of video clips that play at the same moment, across all layers. */
    private fun peakConcurrentStreams(plan: RenderPlan): List<Stream> {
        val intervals = plan.layers.flatMap { layer ->
            var t = 0L
            layer.segments.mapNotNull { seg ->
                val start = t
                t += seg.durationUs
                (seg as? VideoSegment.Media)?.takeIf { !it.isImage }?.let { Interval(start, start + seg.durationUs, it.asset, it.speed) }
            }
        }
        var best: List<Interval> = emptyList()
        var bestLoad = 0.0
        // Every maximal overlap set starts at some interval's start.
        for (probe in intervals) {
            val active = intervals.filter { it.start <= probe.start && probe.start < it.end }
            val load = active.sumOf { it.asset.width.toDouble() * it.asset.height * it.decodeFps() }
            if (load > bestLoad) {
                bestLoad = load
                best = active
            }
        }
        return best.map { Stream(codecOf(it.asset), it.asset.width.coerceAtLeast(2), it.asset.height.coerceAtLeast(2), it.decodeFps()) }
    }

    /** Frames per second the decoder must produce: a clip at 2× speed decodes twice as fast. */
    private fun Interval.decodeFps(): Float = (asset.frameRate?.takeIf { it > 0f } ?: 30f) * speed.coerceAtLeast(0.1f)

    private fun supports(mime: String, streams: List<Stream>): Boolean {
        val largest = streams.maxBy { it.width.toLong() * it.height }
        val mbPerFrame = macroblocks(largest.width, largest.height)
        val totalMbPerSecond = streams.sumOf { macroblocks(it.width, it.height) * it.fps.toDouble() }
        val combinedFps = (totalMbPerSecond / mbPerFrame).roundToInt().coerceAtLeast(1)
        val decoders = hardwareDecoders(mime)
        if (decoders.isEmpty()) return true // Unknown codec: don't block the preview on a guess.
        return decoders.any { caps ->
            val points = runCatching { caps.supportedPerformancePoints }.getOrNull()
            if (!points.isNullOrEmpty()) {
                val needed = MediaCodecInfo.VideoCapabilities.PerformancePoint(largest.width, largest.height, combinedFps)
                points.any { it.covers(needed) }
            } else {
                runCatching {
                    caps.areSizeAndRateSupported(largest.width, largest.height, combinedFps.toDouble()) ||
                        caps.areSizeAndRateSupported(largest.height, largest.width, combinedFps.toDouble())
                }.getOrDefault(true)
            }
        }
    }

    private fun macroblocks(w: Int, h: Int): Long = ((w + 15) / 16).toLong() * ((h + 15) / 16)

    private fun hardwareDecoders(mime: String): List<MediaCodecInfo.VideoCapabilities> = decodersByMime.getOrPut(mime) {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() }
    }

    /** The video codec of [asset] (e.g. video/hevc), read once from the file and cached. */
    private fun codecOf(asset: MediaAsset): String = codecByUri.getOrPut(asset.uri) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(asset.uri), null)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                .firstOrNull { it.startsWith("video/") } ?: MediaFormat.MIMETYPE_VIDEO_AVC
        } catch (e: Exception) {
            MediaFormat.MIMETYPE_VIDEO_AVC
        } finally {
            extractor.release()
        }
    }
}
