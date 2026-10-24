package app.clippr.core.model.render

import app.clippr.core.model.ColorGrade
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
    /** Contiguous segments starting at zero and covering exactly [durationUs]. */
    val video: List<VideoSegment>,
    /** Each lane is played in parallel and mixed. Lanes start at zero and may end early. */
    val audioLanes: List<List<AudioSegment>>,
    val texts: List<TextOverlayPlan>,
) {
    val isEmpty: Boolean get() = durationUs <= 0L
}

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
    ) : VideoSegment {
        val isImage: Boolean get() = asset.isUnbounded

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
