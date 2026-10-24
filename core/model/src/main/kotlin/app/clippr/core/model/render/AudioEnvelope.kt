package app.clippr.core.model.render

import app.clippr.core.model.Animation
import app.clippr.core.model.Clip

/**
 * Gain curve for a clip of [durationUs] with [mix] applied: constant volume with linear fade-in
 * at the start and linear fade-out at the end. Positions are relative to the clip start.
 */
class AudioEnvelope(private val mix: AudioMix, private val durationUs: Long) {

    private val fadeOutStartUs: Long get() = durationUs - mix.fadeOutUs

    private val keyClip: Clip? =
        if (mix.volumeKeyframes.isEmpty()) {
            null
        } else {
            Clip(
                id = "envelope",
                assetId = null,
                timelineStartUs = 0,
                sourceStartUs = 0,
                sourceEndUs = Long.MAX_VALUE / 4,
                volumeKeyframes = mix.volumeKeyframes,
            )
        }

    /** Linear gain in `[0, 1]` at [positionUs] (time since the clip start, in timeline time). */
    fun gainAt(positionUs: Long): Float {
        var gain = keyClip?.let { Animation.volumeAt(it, mix.sourceStartUs + (positionUs * mix.speed.toDouble()).toLong()) } ?: mix.volume
        if (mix.fadeInUs > 0 && positionUs < mix.fadeInUs) {
            gain *= (positionUs.coerceAtLeast(0).toFloat() / mix.fadeInUs)
        }
        if (mix.fadeOutUs > 0 && positionUs >= fadeOutStartUs) {
            val remaining = (durationUs - positionUs).coerceAtLeast(0)
            gain *= (remaining.toFloat() / mix.fadeOutUs)
        }
        return gain.coerceIn(0f, 1f)
    }

    /**
     * If the gain is exactly 1 at [positionUs], returns the exclusive end of that unity region
     * ([UNITY_TO_END] if it lasts to the end of the stream); otherwise returns [NOT_UNITY].
     */
    fun unityUntilUs(positionUs: Long): Long {
        if (mix.volume != 1f || keyClip != null) return NOT_UNITY
        if (mix.fadeInUs > 0 && positionUs < mix.fadeInUs) return NOT_UNITY
        if (mix.fadeOutUs > 0) {
            return if (positionUs < fadeOutStartUs) fadeOutStartUs else NOT_UNITY
        }
        return UNITY_TO_END
    }

    companion object {
        const val NOT_UNITY = -1L
        const val UNITY_TO_END = Long.MAX_VALUE
    }
}
