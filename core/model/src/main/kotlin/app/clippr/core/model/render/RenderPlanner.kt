package app.clippr.core.model.render

import app.clippr.core.model.Clip
import app.clippr.core.model.ClipAudio
import app.clippr.core.model.Project
import app.clippr.core.model.TrackKind

/**
 * Converts a [Project] into a [RenderPlan].
 *
 * Current renderer scope (documented in docs/ARCHITECTURE.md):
 * - One composited video lane: the main (first) video track. Additional video tracks are not
 *   created by the UI yet because multi-layer compositing is a later milestone.
 * - Any number of audio tracks, each mixed as its own lane.
 * - Text clips from all text tracks, drawn over the video.
 *
 * Clips whose asset is missing from the project are rendered as gaps, so a broken reference can
 * never shift the timing of anything else.
 */
object RenderPlanner {

    fun plan(project: Project, canvasWidth: Int = project.settings.width, canvasHeight: Int = project.settings.height): RenderPlan {
        val sequence = project.sequence
        val duration = sequence.durationUs

        val videoTrack = sequence.mainVideoTrack
        val video = ArrayList<VideoSegment>()
        var cursor = 0L
        if (videoTrack != null) {
            for (clip in videoTrack.clips.sortedBy { it.timelineStartUs }) {
                if (clip.timelineStartUs > cursor) video += VideoSegment.Gap(clip.timelineStartUs - cursor)
                val asset = project.asset(clip.assetId)
                video += if (asset == null) {
                    VideoSegment.Gap(clip.durationUs)
                } else {
                    VideoSegment.Media(
                        clipId = clip.id,
                        asset = asset,
                        sourceStartUs = clip.sourceStartUs,
                        sourceEndUs = clip.sourceEndUs,
                        durationUs = clip.durationUs,
                        speed = clip.speed,
                        transform = clip.transform,
                        audio = if (videoTrack.muted || !asset.hasAudio) null else mixFor(clip),
                    )
                }
                cursor = clip.timelineEndUs
            }
        }
        if (cursor < duration) video += VideoSegment.Gap(duration - cursor)

        val audioLanes = sequence.tracks
            .filter { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }
            .mapNotNull { track ->
                val lane = ArrayList<AudioSegment>()
                var laneCursor = 0L
                var hasSound = false
                for (clip in track.clips.sortedBy { it.timelineStartUs }) {
                    if (clip.timelineStartUs > laneCursor) lane += AudioSegment.Gap(clip.timelineStartUs - laneCursor)
                    val asset = project.asset(clip.assetId)
                    val mix = mixFor(clip)
                    if (asset == null || mix == null || !asset.hasAudio) {
                        lane += AudioSegment.Gap(clip.durationUs)
                    } else {
                        hasSound = true
                        lane += AudioSegment.Media(
                            clipId = clip.id,
                            asset = asset,
                            sourceStartUs = clip.sourceStartUs,
                            sourceEndUs = clip.sourceEndUs,
                            durationUs = clip.durationUs,
                            speed = clip.speed,
                            mix = mix,
                        )
                    }
                    laneCursor = clip.timelineEndUs
                }
                if (hasSound) lane else null
            }

        val texts = sequence.tracks
            .filter { it.kind == TrackKind.TEXT }
            .flatMap { track -> track.clips }
            .mapNotNull { clip ->
                val style = clip.text ?: return@mapNotNull null
                if (style.text.isBlank()) return@mapNotNull null
                TextOverlayPlan(clip.id, clip.timelineStartUs, clip.timelineEndUs, style, clip.transform)
            }
            .sortedBy { it.startUs }

        return RenderPlan(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            frameRate = project.settings.frameRate,
            durationUs = duration,
            video = if (duration > 0) video else emptyList(),
            audioLanes = if (duration > 0) audioLanes else emptyList(),
            texts = texts,
        )
    }

    /** The clip's gain, or null if it is silent. Fades are shrunk to fit the clip. */
    fun mixFor(clip: Clip): AudioMix? {
        val audio: ClipAudio = clip.audio
        if (audio.isSilent) return null
        val duration = clip.durationUs
        var fadeIn = audio.fadeInUs.coerceIn(0, duration)
        var fadeOut = audio.fadeOutUs.coerceIn(0, duration)
        if (fadeIn + fadeOut > duration && fadeIn + fadeOut > 0) {
            val k = duration.toDouble() / (fadeIn + fadeOut)
            fadeIn = (fadeIn * k).toLong()
            fadeOut = (fadeOut * k).toLong()
        }
        return AudioMix(audio.volume.coerceIn(0f, 1f), fadeIn, fadeOut)
    }
}
