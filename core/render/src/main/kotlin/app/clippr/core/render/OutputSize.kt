package app.clippr.core.render

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import app.clippr.core.model.ProjectSettings
import kotlin.math.roundToInt

/** Preview render resolution. Lower settings keep playback smooth on complex timelines. */
enum class PreviewQuality(val maxLongSide: Int, val label: String) {
    HIGH(1920, "High"),
    BALANCED(1280, "Balanced"),
    FAST(854, "Fast"),
}

object OutputSize {

    /** Scales the project canvas so its long side is at most [maxLongSide], keeping even sizes. */
    fun forPreview(settings: ProjectSettings, quality: PreviewQuality): Pair<Int, Int> {
        val long = maxOf(settings.width, settings.height)
        if (long <= quality.maxLongSide) return settings.width to settings.height
        val k = quality.maxLongSide.toFloat() / long
        return even(settings.width * k) to even(settings.height * k)
    }

    /** Export size with the requested short side and the project's aspect ratio. */
    fun forExport(settings: ProjectSettings, shortSide: Int): Pair<Int, Int> {
        val short = minOf(settings.width, settings.height).toFloat()
        val k = shortSide / short
        return even(settings.width * k) to even(settings.height * k)
    }

    private fun even(v: Float): Int = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
}

/** Queries the device's H.264 encoders so the export screen only offers what will work. */
object EncoderSupport {

    private val avcEncoders: List<MediaCodecInfo.VideoCapabilities> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
    }

    /**
     * Whether some H.264 encoder supports [width] x [height] at [fps]. Portrait sizes are also
     * checked in landscape orientation, because Media3 encodes portrait video rotated by default.
     */
    fun isSupported(width: Int, height: Int, fps: Int): Boolean = avcEncoders.any { caps ->
        runCatching {
            caps.areSizeAndRateSupported(width, height, fps.toDouble()) ||
                caps.areSizeAndRateSupported(height, width, fps.toDouble())
        }.getOrDefault(false)
    }
}
