package app.clippr.core.model

/**
 * Repairs values that should never be in a project but could be saved by a bug or by an older
 * version: NaN or infinite numbers, out-of-range placement, opacity or volume, and unsorted or
 * duplicate keyframes. Any of these can make the renderer fail for the whole project, so the
 * editor runs [repair] whenever a project is opened.
 *
 * Only values are changed; clips, timing and media references are left alone.
 */
object ProjectRepair {

    data class Result(val project: Project, val repairedClips: Int) {
        val changed: Boolean get() = repairedClips > 0
    }

    fun repair(project: Project): Result {
        var count = 0
        val tracks = project.sequence.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    val fixed = repairClip(clip)
                    if (fixed != clip) count++
                    fixed
                },
            )
        }
        if (count == 0) return Result(project, 0)
        return Result(project.copy(sequence = project.sequence.copy(tracks = tracks)), count)
    }

    fun repairClip(clip: Clip): Clip = clip.copy(
        transform = clip.transform.sanitized(),
        audio = clip.audio.sanitized(),
        text = clip.text?.sanitized(),
        keyframes = clip.keyframes
            .map(::repairKeyframe)
            .sortedBy { it.sourceUs }
            .distinctBy { it.sourceUs },
        volumeKeyframes = clip.volumeKeyframes
            .map { it.copy(volume = it.volume.finiteOr(1f).coerceIn(0f, 1f)) }
            .sortedBy { it.sourceUs }
            .distinctBy { it.sourceUs },
        color = clip.color.sanitized(),
        effects = clip.effects.sanitized(),
    )

    fun repairKeyframe(k: TransformKeyframe): TransformKeyframe = k.copy(
        positionX = k.positionX.finiteOr(0f).coerceIn(-Transform.MAX_OFFSET, Transform.MAX_OFFSET),
        positionY = k.positionY.finiteOr(0f).coerceIn(-Transform.MAX_OFFSET, Transform.MAX_OFFSET),
        scale = k.scale.finiteOr(1f).coerceIn(Transform.MIN_SCALE, Transform.MAX_SCALE),
        rotationDegrees = k.rotationDegrees.finiteOr(0f).coerceIn(-MAX_KEYFRAME_ROTATION, MAX_KEYFRAME_ROTATION),
        opacity = k.opacity.finiteOr(1f).coerceIn(0f, 1f),
    )

    /** Keyframed rotation may exceed one turn (spins), but not without bound. */
    const val MAX_KEYFRAME_ROTATION = 3600f
}
