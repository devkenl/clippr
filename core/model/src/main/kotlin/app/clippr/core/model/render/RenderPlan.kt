package app.clippr.core.model.render

import app.clippr.core.model.Animation
import app.clippr.core.model.Clip
import app.clippr.core.model.ColorGrade
import app.clippr.core.model.LayerLook
import app.clippr.core.model.Transition
import app.clippr.core.model.TransitionMath
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.TextStyle
import app.clippr.core.model.Transform
import app.clippr.core.model.TransformKeyframe
import app.clippr.core.model.VisualEffects
import app.clippr.core.model.VolumeKeyframe
import app.clippr.core.model.time.FrameRate

/**
 * A flattened, renderer-agnostic description of what a project should look and sound like.
 *
 * The preview player and the exporter both build their pipelines from the same [RenderPlan],
 * produced by [RenderPlanner]. All decisions about gaps, muting, ordering and duration live in
 * the planner (pure Kotlin, unit-tested), so the Android rendering layer only translates the plan
 * one-to-one into Media3 objects. This is what keeps preview and export consistent.
 */
data class RenderPlan(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val frameRate: FrameRate,
    val durationUs: Long,
    /**
     * Video layers from the top down. Each covers exactly [durationUs] with contiguous segments.
     * A track with transitions contributes two layers (its clips alternate between them so the
     * overlapping clips can play together). Usually there is exactly one layer.
     */
    val layers: List<VideoLayer>,
    /** Each lane is played in parallel and mixed. Lanes start at zero and may end early. */
    val audioLanes: List<List<AudioSegment>>,
    val texts: List<TextOverlayPlan>,
) {
    val isEmpty: Boolean get() = durationUs <= 0L

    /** The bottom layer (the only one in single-layer projects). */
    val video: List<VideoSegment> get() = layers.lastOrNull()?.segments ?: emptyList()

    /** More than one layer: the renderer must composite (and use alpha for opacity). */
    val isMultiLayer: Boolean get() = layers.size > 1
}

/**
 * One composited video layer. [trackId] is the timeline track it comes from; [lane] is 0 or 1
 * when the track alternates clips for transitions.
 */
data class VideoLayer(val trackId: String, val lane: Int, val segments: List<VideoSegment>) {
    /** Start time of each segment, for lookups by time. */
    val startTimes: LongArray by lazy {
        val out = LongArray(segments.size)
        var t = 0L
        segments.forEachIndexed { i, s ->
            out[i] = t
            t += s.durationUs
        }
        out
    }

    /** The media segment visible at [timeUs], or null during a gap. */
    fun mediaAt(timeUs: Long): Media? {
        var lo = 0
        var hi = segments.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = startTimes[mid]
            val end = start + segments[mid].durationUs
            when {
                timeUs < start -> hi = mid - 1
                timeUs >= end -> lo = mid + 1
                else -> return segments[mid] as? Media
            }
        }
        return null
    }
}

private typealias Media = VideoSegment.Media

/**
 * A transition window on a clip: [startUs]..[endUs] on the timeline, the [transition] style (always
 * the incoming clip's), and whether the incoming clip is drawn on top of the outgoing one.
 */
data class TransitionWindow(
    val startUs: Long,
    val endUs: Long,
    val transition: Transition,
    val incomingOnTop: Boolean,
)

sealed interface VideoSegment {
    val durationUs: Long

    data class Gap(override val durationUs: Long) : VideoSegment

    data class Media(
        val clipId: String,
        val asset: MediaAsset,
        val sourceStartUs: Long,
        val sourceEndUs: Long,
        override val durationUs: Long,
        val speed: Float,
        val transform: Transform,
        /** Null when this segment contributes no audio (muted, silent or no audio track). */
        val audio: AudioMix?,
        /** Where the clip starts in the composition; renderers subtract it from frame times. */
        val timelineStartUs: Long = 0L,
        val keyframes: List<TransformKeyframe> = emptyList(),
        val color: ColorGrade = ColorGrade.NONE,
        val effects: VisualEffects = VisualEffects.NONE,
        /** This clip fades in over the previous one (it is the incoming clip). */
        val transitionIn: TransitionWindow? = null,
        /** The next clip fades in over this one (this is the outgoing clip). */
        val transitionOut: TransitionWindow? = null,
    ) : VideoSegment {
        val isImage: Boolean get() = asset.isUnbounded

        val timelineEndUs: Long get() = timelineStartUs + durationUs

        /**
         * Opacity, move and zoom of this clip at composition time [timeUs]: keyframed opacity,
         * fades and any transition, combined. Used when compositing layers.
         */
        fun lookAt(timeUs: Long): LayerLook {
            val local = timeUs - timelineStartUs
            val base = Animation.transformAt(
                Clip("look", null, 0, 0, Long.MAX_VALUE / 4, transform = transform, keyframes = keyframes),
                sourceTimeAt(timeUs),
            ).opacity * Animation.fadeFactor(effects, local, durationUs)
            var look = LayerLook(alpha = base)
            transitionIn?.let { w ->
                if (timeUs in w.startUs until w.endUs) {
                    val p = TransitionMath.progress(w.transition, w.startUs, w.endUs, timeUs)
                    look = look.combine(TransitionMath.incoming(w.transition.type, p, w.incomingOnTop))
                }
            }
            transitionOut?.let { w ->
                if (timeUs in w.startUs until w.endUs) {
                    val p = TransitionMath.progress(w.transition, w.startUs, w.endUs, timeUs)
                    look = look.combine(TransitionMath.outgoing(w.transition.type, p, w.incomingOnTop))
                }
            }
            return look
        }

        /** Source time shown at composition time [compositionUs]. */
        fun sourceTimeAt(compositionUs: Long): Long =
            sourceStartUs + ((compositionUs - timelineStartUs).coerceIn(0, durationUs) * speed.toDouble()).toLong()
    }
}

sealed interface AudioSegment {
    val durationUs: Long

    data class Gap(override val durationUs: Long) : AudioSegment

    data class Media(
        val clipId: String,
        val asset: MediaAsset,
        val sourceStartUs: Long,
        val sourceEndUs: Long,
        override val durationUs: Long,
        val speed: Float,
        val mix: AudioMix,
    ) : AudioSegment
}

/**
 * Gain applied to one clip's audio. Fades are linear and measured in timeline time. With
 * [volumeKeyframes], the volume follows them (keyed by source time, mapped through
 * [sourceStartUs] and [speed]) instead of [volume].
 */
data class AudioMix(
    val volume: Float,
    val fadeInUs: Long,
    val fadeOutUs: Long,
    val volumeKeyframes: List<VolumeKeyframe> = emptyList(),
    val sourceStartUs: Long = 0L,
    val speed: Float = 1f,
) {
    val isUnity: Boolean get() = volume == 1f && fadeInUs == 0L && fadeOutUs == 0L && volumeKeyframes.isEmpty()
}

/** A text overlay visible on the half-open interval `[startUs, endUs)`. */
data class TextOverlayPlan(
    val clipId: String,
    val startUs: Long,
    val endUs: Long,
    val style: TextStyle,
    val transform: Transform,
    val keyframes: List<TransformKeyframe> = emptyList(),
    val effects: VisualEffects = VisualEffects.NONE,
    val sourceStartUs: Long = 0L,
) {
    val isAnimated: Boolean get() = keyframes.isNotEmpty() || effects.hasFades
}

private fun LayerLook.combine(other: LayerLook) = LayerLook(
    alpha = alpha * other.alpha,
    offsetX = offsetX + other.offsetX,
    offsetY = offsetY + other.offsetY,
    scale = scale * other.scale,
)
