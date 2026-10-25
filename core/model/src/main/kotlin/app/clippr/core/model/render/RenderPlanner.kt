package app.clippr.core.model.render

import app.clippr.core.model.Clip
import app.clippr.core.model.ClipAudio
import app.clippr.core.model.Project
import app.clippr.core.model.TrackKind

/**
 * Converts a [Project] into a [RenderPlan].
 *
 * Renderer scope (documented in docs/ARCHITECTURE.md):
 * - Every visible video track becomes a layer, top track on top. A track whose clips overlap
 *   (transitions) is split into two alternating lanes so both clips can play during the overlap.
 * - Any number of audio tracks, each mixed as its own lane. Video clips' sound crossfades during
 *   transitions; hidden video tracks still contribute their sound.
 * - Text clips from all text tracks, drawn over the video.
 *
 * Clips whose asset is missing from the project are rendered as gaps, so a broken reference can
 * never shift the timing of anything else.
 *
 * With `cutTransitions`, overlapping clips on the same track are shown as a plain cut where the
 * incoming clip starts, so only one clip per track is decoded at a time. The live preview uses
 * this on devices that can't decode two clips at once fast enough (e.g. two 4K 60 fps clips);
 * exports always render the real transition.
 */
object RenderPlanner {

    fun plan(
        project: Project,
        canvasWidth: Int = project.settings.width,
        canvasHeight: Int = project.settings.height,
        cutTransitions: Boolean = false,
    ): RenderPlan {
        val sequence = project.sequence
        val duration = sequence.durationUs

        val layers = ArrayList<VideoLayer>()
        val hiddenAudio = ArrayList<List<AudioSegment>>()
        for (track in sequence.videoTracks) {
            val sorted = track.clips.sortedBy { it.timelineStartUs }
            if (sorted.isEmpty()) continue
            val clips = if (cutTransitions) cutOverlaps(sorted) else sorted
            // Clips alternate between two lanes only where they overlap (transitions).
            val lanes = IntArray(clips.size)
            if (!cutTransitions) {
                for (i in 1 until clips.size) {
                    lanes[i] = if (clips[i].timelineStartUs < clips[i - 1].timelineEndUs) 1 - lanes[i - 1] else 0
                }
            }
            if (track.hidden) {
                // Hidden layers aren't drawn, but their clips' sound still plays.
                for (lane in lanes.distinct()) {
                    audioLane(project, clips.filterIndexed { i, _ -> lanes[i] == lane }, track.muted)?.let(hiddenAudio::add)
                }
                continue
            }
            val usedLanes = lanes.distinct().sortedDescending() // lane 1 is drawn above lane 0
            for (lane in usedLanes) {
                val segments = ArrayList<VideoSegment>()
                var cursor = 0L
                var hasMedia = false
                clips.forEachIndexed { i, clip ->
                    if (lanes[i] != lane) return@forEachIndexed
                    if (clip.timelineStartUs > cursor) segments += VideoSegment.Gap(clip.timelineStartUs - cursor)
                    val asset = project.asset(clip.assetId)
                    val prev = clips.getOrNull(i - 1)
                    val next = clips.getOrNull(i + 1)
                    val overlapIn = if (prev != null && !cutTransitions) (prev.timelineEndUs - clip.timelineStartUs).coerceAtLeast(0) else 0L
                    val overlapOut = if (next != null && !cutTransitions) (clip.timelineEndUs - next.timelineStartUs).coerceAtLeast(0) else 0L
                    segments += if (asset == null) {
                        VideoSegment.Gap(clip.durationUs)
                    } else {
                        hasMedia = true
                        VideoSegment.Media(
                            clipId = clip.id,
                            asset = asset,
                            sourceStartUs = clip.sourceStartUs,
                            sourceEndUs = clip.sourceEndUs,
                            durationUs = clip.durationUs,
                            speed = clip.speed,
                            transform = clip.transform,
                            audio = if (track.muted || !asset.hasAudio) null else mixFor(clip, overlapIn, overlapOut),
                            timelineStartUs = clip.timelineStartUs,
                            keyframes = clip.keyframes,
                            color = clip.color,
                            effects = clip.effects,
                            transitionIn = if (overlapIn > 0) {
                                TransitionWindow(clip.timelineStartUs, clip.timelineStartUs + overlapIn, clip.transition, incomingOnTop = lane == 1)
                            } else {
                                null
                            },
                            transitionOut = if (overlapOut > 0 && next != null) {
                                TransitionWindow(next.timelineStartUs, clip.timelineEndUs, next.transition, incomingOnTop = lanes[i + 1] == 1)
                            } else {
                                null
                            },
                        )
                    }
                    cursor = clip.timelineEndUs
                }
                if (cursor < duration) segments += VideoSegment.Gap(duration - cursor)
                if (hasMedia) layers += VideoLayer(track.id, lane, segments)
            }
        }
        // There is always at least the base layer, even if it is all gap (e.g. titles only).
        if (layers.isEmpty() && duration > 0) {
            layers += VideoLayer(sequence.mainVideoTrack?.id ?: "base", 0, listOf(VideoSegment.Gap(duration)))
        }

        val audioLanes = sequence.tracks
            .filter { it.kind == TrackKind.AUDIO && !it.muted && it.clips.isNotEmpty() }
            .mapNotNull { track -> audioLane(project, track.clips.sortedBy { it.timelineStartUs }, muted = false) } + hiddenAudio

        val texts = sequence.tracks
            .filter { it.kind == TrackKind.TEXT }
            .flatMap { track -> track.clips }
            .mapNotNull { clip ->
                val style = clip.text ?: return@mapNotNull null
                if (style.text.isBlank()) return@mapNotNull null
                TextOverlayPlan(
                    clipId = clip.id,
                    startUs = clip.timelineStartUs,
                    endUs = clip.timelineEndUs,
                    style = style,
                    transform = clip.transform,
                    keyframes = clip.keyframes,
                    effects = clip.effects,
                    sourceStartUs = clip.sourceStartUs,
                )
            }
            .sortedBy { it.startUs }

        return RenderPlan(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            frameRate = project.settings.frameRate,
            durationUs = duration,
            layers = if (duration > 0) layers else emptyList(),
            audioLanes = if (duration > 0) audioLanes else emptyList(),
            texts = texts,
        )
    }

    /** Trims each clip that runs into the next one so it ends where the next one starts. */
    private fun cutOverlaps(clips: List<Clip>): List<Clip> = clips.mapIndexed { i, clip ->
        val next = clips.getOrNull(i + 1)
        if (next == null || next.timelineStartUs >= clip.timelineEndUs || next.timelineStartUs <= clip.timelineStartUs) {
            clip
        } else {
            val keepSource = Clip.sourceDurationFor(next.timelineStartUs - clip.timelineStartUs, clip.speed)
            clip.copy(sourceEndUs = (clip.sourceStartUs + keepSource).coerceIn(clip.sourceStartUs + 1, clip.sourceEndUs))
        }
    }

    /** One audio lane from non-overlapping [clips], or null if none of them makes a sound. */
    private fun audioLane(project: Project, clips: List<Clip>, muted: Boolean): List<AudioSegment>? {
        if (muted) return null
        val lane = ArrayList<AudioSegment>()
        var laneCursor = 0L
        var hasSound = false
        for (clip in clips) {
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
        return if (hasSound) lane else null
    }

    /**
     * The clip's gain, or null if it is silent. Fades are shrunk to fit the clip. During a
     * transition ([transitionInUs], [transitionOutUs]) the sound crossfades too: the fade is at
     * least as long as the overlap.
     */
    fun mixFor(clip: Clip, transitionInUs: Long = 0L, transitionOutUs: Long = 0L): AudioMix? {
        val audio: ClipAudio = clip.audio
        if (audio.muted) return null
        if (clip.volumeKeyframes.isEmpty() && audio.volume <= 0f) return null
        if (clip.volumeKeyframes.isNotEmpty() && clip.volumeKeyframes.all { it.volume <= 0f }) return null
        val duration = clip.durationUs
        var fadeIn = maxOf(audio.fadeInUs, transitionInUs).coerceIn(0, duration)
        var fadeOut = maxOf(audio.fadeOutUs, transitionOutUs).coerceIn(0, duration)
        if (fadeIn + fadeOut > duration && fadeIn + fadeOut > 0) {
            val k = duration.toDouble() / (fadeIn + fadeOut)
            fadeIn = (fadeIn * k).toLong()
            fadeOut = (fadeOut * k).toLong()
        }
        return AudioMix(
            volume = audio.volume.coerceIn(0f, 1f),
            fadeInUs = fadeIn,
            fadeOutUs = fadeOut,
            volumeKeyframes = clip.volumeKeyframes,
            sourceStartUs = clip.sourceStartUs,
            speed = clip.speed,
        )
    }
}
