package app.clippr.core.model

import kotlin.math.abs

/** How a value travels from one keyframe to the next. */
enum class Easing { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, HOLD }

/**
 * A snapshot of a clip's placement and opacity at one moment (CapCut-style keyframes).
 *
 * Keyframes are keyed by **source time** ([sourceUs]), not timeline time. They therefore stay
 * attached to the content: trimming, moving, splitting or duplicating a clip never shifts an
 * animation relative to the frames it was made for. [easing] shapes the segment that starts at
 * this keyframe.
 */
data class TransformKeyframe(
    val sourceUs: Long,
    val positionX: Float,
    val positionY: Float,
    val scale: Float,
    val rotationDegrees: Float,
    val opacity: Float,
    val easing: Easing = Easing.EASE_IN_OUT,
) {
    fun applyTo(base: Transform): Transform = base.copy(
        positionX = positionX,
        positionY = positionY,
        scale = scale,
        rotationDegrees = rotationDegrees,
        opacity = opacity,
    )

    companion object {
        fun from(sourceUs: Long, t: Transform, easing: Easing = Easing.EASE_IN_OUT) =
            TransformKeyframe(sourceUs, t.positionX, t.positionY, t.scale, t.rotationDegrees, t.opacity, easing)
    }
}

/** Clip volume at one moment, keyed by source time like [TransformKeyframe]. */
data class VolumeKeyframe(val sourceUs: Long, val volume: Float, val easing: Easing = Easing.LINEAR)

/**
 * Per-clip visual effects. Each strength is `0..1`; `0` means off. Fades go from/to black for
 * video and from/to transparent for text, and are measured in timeline time.
 */
data class VisualEffects(
    val blur: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    /** Stylized shader effects, applied in order (see [StyleEffectType]). */
    val styles: List<StyleEffect> = emptyList(),
    /** Green-screen keying; null when off. */
    val chromaKey: ChromaKey? = null,
) {
    val hasFades: Boolean get() = fadeInUs > 0 || fadeOutUs > 0

    fun sanitized() = copy(
        blur = blur.finiteOr(0f).coerceIn(0f, 1f),
        vignette = vignette.finiteOr(0f).coerceIn(0f, 1f),
        grain = grain.finiteOr(0f).coerceIn(0f, 1f),
        fadeInUs = fadeInUs.coerceAtLeast(0),
        fadeOutUs = fadeOutUs.coerceAtLeast(0),
        // One instance per type; later duplicates are dropped.
        styles = styles.distinctBy { it.type }.map { it.sanitized() },
        chromaKey = chromaKey?.sanitized(),
    )

    companion object {
        val NONE = VisualEffects()
    }
}

/** Keyframe evaluation and editing. Pure functions shared by the editor, preview and export. */
object Animation {

    fun ease(easing: Easing, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (easing) {
            Easing.LINEAR -> x
            Easing.EASE_IN -> x * x * x
            Easing.EASE_OUT -> 1f - (1f - x) * (1f - x) * (1f - x)
            Easing.EASE_IN_OUT -> if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
            Easing.HOLD -> 0f
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Placement and opacity of [clip] when its source is at [sourceUs]. */
    fun transformAt(clip: Clip, sourceUs: Long): Transform {
        val keys = clip.keyframes
        if (keys.isEmpty()) return clip.transform
        if (sourceUs <= keys.first().sourceUs) return keys.first().applyTo(clip.transform)
        if (sourceUs >= keys.last().sourceUs) return keys.last().applyTo(clip.transform)
        val i = keys.indexOfLast { it.sourceUs <= sourceUs }
        val a = keys[i]
        val b = keys[i + 1]
        val u = ease(a.easing, (sourceUs - a.sourceUs).toFloat() / (b.sourceUs - a.sourceUs))
        return clip.transform.copy(
            positionX = lerp(a.positionX, b.positionX, u),
            positionY = lerp(a.positionY, b.positionY, u),
            scale = lerp(a.scale, b.scale, u),
            rotationDegrees = lerp(a.rotationDegrees, b.rotationDegrees, u),
            opacity = lerp(a.opacity, b.opacity, u),
        )
    }

    /** Transform of [clip] at timeline time [timelineUs]. */
    fun transformAtTimeline(clip: Clip, timelineUs: Long): Transform = transformAt(clip, clip.sourceTimeAt(timelineUs))

    /** Volume of [clip] when its source is at [sourceUs]. */
    fun volumeAt(clip: Clip, sourceUs: Long): Float {
        val keys = clip.volumeKeyframes
        if (keys.isEmpty()) return clip.audio.volume
        if (sourceUs <= keys.first().sourceUs) return keys.first().volume
        if (sourceUs >= keys.last().sourceUs) return keys.last().volume
        val i = keys.indexOfLast { it.sourceUs <= sourceUs }
        val a = keys[i]
        val b = keys[i + 1]
        return lerp(a.volume, b.volume, ease(a.easing, (sourceUs - a.sourceUs).toFloat() / (b.sourceUs - a.sourceUs)))
    }

    /** Fade multiplier (0..1) at [localUs] from the clip start, for a clip lasting [durationUs]. */
    fun fadeFactor(effects: VisualEffects, localUs: Long, durationUs: Long): Float {
        var f = 1f
        val fadeIn = effects.fadeInUs.coerceAtMost(durationUs)
        val fadeOut = effects.fadeOutUs.coerceAtMost(durationUs)
        if (fadeIn > 0 && localUs < fadeIn) f *= (localUs.coerceAtLeast(0).toFloat() / fadeIn)
        if (fadeOut > 0 && localUs > durationUs - fadeOut) f *= ((durationUs - localUs).coerceAtLeast(0).toFloat() / fadeOut)
        return f.coerceIn(0f, 1f)
    }

    /** Index of the keyframe within [toleranceUs] of [sourceUs], or -1. */
    fun <K> indexNear(keys: List<K>, sourceUs: Long, toleranceUs: Long, time: (K) -> Long): Int {
        var best = -1
        var bestD = Long.MAX_VALUE
        keys.forEachIndexed { i, k ->
            val d = abs(time(k) - sourceUs)
            if (d <= toleranceUs && d < bestD) {
                best = i
                bestD = d
            }
        }
        return best
    }

    /**
     * Applies [edit] to the clip's placement as seen at [sourceUs]. Without keyframes this edits
     * the static transform; with keyframes it updates (or creates) the keyframe at that moment,
     * so edits made while animating are never lost.
     */
    fun editTransformAt(clip: Clip, sourceUs: Long, toleranceUs: Long, edit: (Transform) -> Transform): Clip {
        if (clip.keyframes.isEmpty()) return clip.copy(transform = edit(clip.transform).sanitized())
        val current = transformAt(clip, sourceUs)
        val edited = edit(current).sanitized()
        // Crop and framing are not animated: apply them to the base transform.
        val base = clip.transform.copy(crop = edited.crop, fit = edited.fit)
        val index = indexNear(clip.keyframes, sourceUs, toleranceUs) { it.sourceUs }
        val keys = clip.keyframes.toMutableList()
        if (index >= 0) {
            keys[index] = TransformKeyframe.from(keys[index].sourceUs, edited, keys[index].easing)
        } else {
            keys += TransformKeyframe.from(sourceUs, edited)
        }
        return clip.copy(transform = base, keyframes = keys.sortedBy { it.sourceUs })
    }

    /** Adds a keyframe holding the current animated values at [sourceUs] (no-op if one exists). */
    fun addTransformKeyframe(clip: Clip, sourceUs: Long, toleranceUs: Long): Clip {
        if (indexNear(clip.keyframes, sourceUs, toleranceUs) { it.sourceUs } >= 0) return clip
        val k = TransformKeyframe.from(sourceUs, transformAt(clip, sourceUs))
        return clip.copy(keyframes = (clip.keyframes + k).sortedBy { it.sourceUs })
    }

    fun removeTransformKeyframe(clip: Clip, sourceUs: Long, toleranceUs: Long): Clip {
        val index = indexNear(clip.keyframes, sourceUs, toleranceUs) { it.sourceUs }
        if (index < 0) return clip
        val remaining = clip.keyframes.filterIndexed { i, _ -> i != index }
        // Removing the last keyframe keeps its values as the static placement.
        val base = if (remaining.isEmpty()) clip.keyframes[index].applyTo(clip.transform) else clip.transform
        return clip.copy(keyframes = remaining, transform = base)
    }

    fun setTransformEasing(clip: Clip, sourceUs: Long, toleranceUs: Long, easing: Easing): Clip {
        val index = indexNear(clip.keyframes, sourceUs, toleranceUs) { it.sourceUs }
        if (index < 0) return clip
        return clip.copy(keyframes = clip.keyframes.mapIndexed { i, k -> if (i == index) k.copy(easing = easing) else k })
    }

    fun editVolumeAt(clip: Clip, sourceUs: Long, toleranceUs: Long, volume: Float): Clip {
        val v = volume.coerceIn(0f, 1f)
        if (clip.volumeKeyframes.isEmpty()) return clip.copy(audio = clip.audio.copy(volume = v))
        val index = indexNear(clip.volumeKeyframes, sourceUs, toleranceUs) { it.sourceUs }
        val keys = clip.volumeKeyframes.toMutableList()
        if (index >= 0) keys[index] = keys[index].copy(volume = v) else keys += VolumeKeyframe(sourceUs, v)
        return clip.copy(volumeKeyframes = keys.sortedBy { it.sourceUs })
    }

    fun addVolumeKeyframe(clip: Clip, sourceUs: Long, toleranceUs: Long): Clip {
        if (indexNear(clip.volumeKeyframes, sourceUs, toleranceUs) { it.sourceUs } >= 0) return clip
        val k = VolumeKeyframe(sourceUs, volumeAt(clip, sourceUs))
        return clip.copy(volumeKeyframes = (clip.volumeKeyframes + k).sortedBy { it.sourceUs })
    }

    fun removeVolumeKeyframe(clip: Clip, sourceUs: Long, toleranceUs: Long): Clip {
        val index = indexNear(clip.volumeKeyframes, sourceUs, toleranceUs) { it.sourceUs }
        if (index < 0) return clip
        val removed = clip.volumeKeyframes[index]
        val remaining = clip.volumeKeyframes.filterIndexed { i, _ -> i != index }
        val audio = if (remaining.isEmpty()) clip.audio.copy(volume = removed.volume) else clip.audio
        return clip.copy(volumeKeyframes = remaining, audio = audio)
    }

    /** Timeline time at which a keyframe at [sourceUs] appears, or null if it is trimmed away. */
    fun timelineTimeOf(clip: Clip, sourceUs: Long): Long? {
        if (sourceUs < clip.sourceStartUs || sourceUs > clip.sourceEndUs) return null
        return clip.timelineStartUs + Clip.timelineDurationFor(sourceUs - clip.sourceStartUs, clip.speed)
    }
}
