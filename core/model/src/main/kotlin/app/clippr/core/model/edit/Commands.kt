package app.clippr.core.model.edit

import app.clippr.core.model.Clip
import app.clippr.core.model.ColorGrade
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import app.clippr.core.model.Project
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.TextStyle
import app.clippr.core.model.Track
import app.clippr.core.model.TrackKind
import app.clippr.core.model.time.US_PER_SECOND

/** Default timeline duration given to still images and new text clips. */
const val DEFAULT_STILL_DURATION_US: Long = 3L * US_PER_SECOND

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

private fun Project.withTrack(track: Track): Project = copy(sequence = sequence.replaceTrack(track))

/** Applies [block] to the unlocked track containing [clipId]; no-op otherwise. */
private inline fun Project.editClipTrack(clipId: String, block: (Track, Clip) -> Track?): Project {
    val track = sequence.trackOfClip(clipId) ?: return this
    if (track.locked) return this
    val clip = track.clips.first { it.id == clipId }
    val updated = block(track, clip) ?: return this
    return withTrack(updated)
}

private fun Project.isBounded(clip: Clip): Boolean {
    if (clip.isText) return false
    val asset = asset(clip.assetId) ?: return true
    return !asset.isUnbounded
}

private fun Project.sourceLimitUs(clip: Clip): Long =
    if (isBounded(clip)) asset(clip.assetId)?.durationUs ?: clip.sourceEndUs else Long.MAX_VALUE

// ---------------------------------------------------------------------------------------------
// Project-level commands
// ---------------------------------------------------------------------------------------------

data class RenameProject(val name: String) : EditCommand {
    override val label = "Rename project"
    override fun apply(project: Project): Project {
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) project else project.copy(name = trimmed)
    }
}

data class ChangeProjectSettings(val settings: ProjectSettings) : EditCommand {
    override val label = "Change project settings"
    override fun apply(project: Project): Project = project.copy(settings = settings)
}

data class ImportAssets(val assets: List<MediaAsset>) : EditCommand {
    override val label = if (assets.size == 1) "Import media" else "Import ${assets.size} files"
    override fun apply(project: Project): Project {
        val known = project.assets.map { it.id }.toSet()
        val fresh = assets.filter { it.id !in known }
        return if (fresh.isEmpty()) project else project.copy(assets = project.assets + fresh)
    }
}

/** Points an asset at a new file, e.g. after the original was moved. Timing is preserved. */
data class RelinkAsset(val assetId: String, val newUri: String, val displayName: String) : EditCommand {
    override val label = "Relink media"
    override fun apply(project: Project): Project {
        if (project.asset(assetId) == null) return project
        return project.copy(
            assets = project.assets.map {
                if (it.id == assetId) it.copy(uri = newUri, displayName = displayName) else it
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Track commands
// ---------------------------------------------------------------------------------------------

data class AddTrack(val kind: TrackKind, val trackId: String) : EditCommand {
    override val label = "Add ${kind.name.lowercase()} track"
    override fun apply(project: Project): Project {
        val tracks = project.sequence.tracks
        if (tracks.any { it.id == trackId }) return project
        val count = tracks.count { it.kind == kind } + 1
        val name = when (kind) {
            TrackKind.VIDEO -> "Video $count"
            TrackKind.AUDIO -> "Audio $count"
            TrackKind.TEXT -> "Text $count"
        }
        val newTrack = Track(trackId, kind, name)
        // Keep tracks grouped: text on top, then video, then audio.
        val order = listOf(TrackKind.TEXT, TrackKind.VIDEO, TrackKind.AUDIO)
        val insertAt = tracks.indexOfLast { order.indexOf(it.kind) <= order.indexOf(kind) } + 1
        val newTracks = tracks.toMutableList().apply { add(insertAt, newTrack) }
        return project.copy(sequence = project.sequence.copy(tracks = newTracks))
    }
}

data class RemoveTrack(val trackId: String) : EditCommand {
    override val label = "Remove track"
    override fun apply(project: Project): Project {
        val track = project.sequence.track(trackId) ?: return project
        if (track.locked) return project
        // The main video track is required by the renderer.
        if (track.kind == TrackKind.VIDEO && project.sequence.tracks.count { it.kind == TrackKind.VIDEO } == 1) {
            return project
        }
        return project.copy(
            sequence = project.sequence.copy(tracks = project.sequence.tracks.filter { it.id != trackId }),
        )
    }
}

data class SetTrackMuted(val trackId: String, val muted: Boolean) : EditCommand {
    override val label = if (muted) "Mute track" else "Unmute track"
    override fun apply(project: Project): Project {
        val track = project.sequence.track(trackId) ?: return project
        return project.withTrack(track.copy(muted = muted))
    }
}

data class SetTrackLocked(val trackId: String, val locked: Boolean) : EditCommand {
    override val label = if (locked) "Lock track" else "Unlock track"
    override fun apply(project: Project): Project {
        val track = project.sequence.track(trackId) ?: return project
        return project.withTrack(track.copy(locked = locked))
    }
}

// ---------------------------------------------------------------------------------------------
// Clip creation
// ---------------------------------------------------------------------------------------------

/**
 * Adds a clip for [assetId]. With a null [trackId] the first compatible track is used; with a
 * null [startUs] the clip is appended after the last clip of that track.
 */
data class AddAssetClip(
    val assetId: String,
    val clipId: String,
    val trackId: String? = null,
    val startUs: Long? = null,
    val stillDurationUs: Long = DEFAULT_STILL_DURATION_US,
) : EditCommand {
    override val label = "Add clip"
    override fun apply(project: Project): Project {
        val asset = project.asset(assetId) ?: return project
        if (project.sequence.clip(clipId) != null) return project
        val track = (if (trackId != null) project.sequence.track(trackId) else null)
            ?: project.sequence.tracks.firstOrNull { it.accepts(asset.type) && !it.locked }
            ?: return project
        if (track.locked || !track.accepts(asset.type)) return project
        val sourceEnd = when {
            asset.isUnbounded -> stillDurationUs.coerceAtLeast(MIN_CLIP_DURATION_US)
            asset.durationUs > 0 -> asset.durationUs
            else -> return project
        }
        val clip = Clip(
            id = clipId,
            assetId = asset.id,
            timelineStartUs = 0,
            sourceStartUs = 0,
            sourceEndUs = sourceEnd,
        )
        val placed = Placement.place(track.clips, clip, startUs ?: track.endUs)
        return project.withTrack(track.withClips(placed.clips))
    }
}

data class AddTextClip(
    val clipId: String,
    val style: TextStyle,
    val trackId: String? = null,
    val startUs: Long = 0,
    val durationUs: Long = DEFAULT_STILL_DURATION_US,
) : EditCommand {
    override val label = "Add text"
    override fun apply(project: Project): Project {
        if (project.sequence.clip(clipId) != null) return project
        val track = (if (trackId != null) project.sequence.track(trackId) else null)
            ?: project.sequence.tracks.firstOrNull { it.kind == TrackKind.TEXT && !it.locked }
            ?: return project
        if (track.locked || track.kind != TrackKind.TEXT) return project
        val clip = Clip(
            id = clipId,
            assetId = null,
            timelineStartUs = 0,
            sourceStartUs = 0,
            sourceEndUs = durationUs.coerceAtLeast(MIN_CLIP_DURATION_US),
            text = style.sanitized(),
        )
        val placed = Placement.place(track.clips, clip, startUs)
        return project.withTrack(track.withClips(placed.clips))
    }
}

// ---------------------------------------------------------------------------------------------
// Clip editing
// ---------------------------------------------------------------------------------------------

/** Moves a clip to [targetTrackId] at (or near, see [Placement]) [startUs]. */
data class MoveClip(val clipId: String, val targetTrackId: String, val startUs: Long) : EditCommand {
    override val label = "Move clip"
    override fun apply(project: Project): Project {
        val source = project.sequence.trackOfClip(clipId) ?: return project
        val target = project.sequence.track(targetTrackId) ?: return project
        if (source.locked || target.locked) return project
        val clip = source.clips.first { it.id == clipId }
        val type = project.asset(clip.assetId)?.type
        if (clip.isText) {
            if (target.kind != TrackKind.TEXT) return project
        } else if (!target.accepts(type)) {
            return project
        }
        return if (source.id == target.id) {
            val placed = Placement.place(source.clips.filter { it.id != clipId }, clip, startUs)
            project.withTrack(source.withClips(placed.clips))
        } else {
            val newSource = source.withClips(source.clips.filter { it.id != clipId })
            val placed = Placement.place(target.clips, clip, startUs)
            project.withTrack(newSource).withTrack(target.withClips(placed.clips))
        }
    }
}

/**
 * Moves the start (left edge) of a clip to [newStartUs] while keeping its end fixed. The value is
 * clamped so the clip never overlaps its neighbour, never reads before the start of its source,
 * and never becomes shorter than the minimum clip duration.
 */
data class TrimClipStart(val clipId: String, val newStartUs: Long) : EditCommand {
    override val label = "Trim clip"
    override fun apply(project: Project): Project = project.editClipTrack(clipId) { track, clip ->
        val index = track.clips.indexOf(clip)
        val prevEnd = if (index > 0) track.clips[index - 1].timelineEndUs else 0L
        val end = clip.timelineEndUs
        val minDuration = project.minClipDurationUs()
        val bounded = project.isBounded(clip)
        // Earliest start allowed by the source: the clip can extend left until source time 0.
        val earliestBySource = if (bounded) {
            end - Clip.timelineDurationFor(clip.sourceEndUs, clip.speed)
        } else {
            Long.MIN_VALUE
        }
        val latest = end - minDuration
        val lower = maxOf(prevEnd, earliestBySource, 0L)
        if (lower > latest) return@editClipTrack null
        val start = newStartUs.coerceIn(lower, latest)
        if (start == clip.timelineStartUs) return@editClipTrack null
        val newDuration = end - start
        val updated = if (bounded) {
            var newSourceStart = (clip.sourceEndUs - Clip.sourceDurationFor(newDuration, clip.speed))
                .coerceAtLeast(0)
            // Speed rounding can make the clip one microsecond too long; never move its end.
            while (newSourceStart < clip.sourceEndUs - 1 &&
                start + Clip.timelineDurationFor(clip.sourceEndUs - newSourceStart, clip.speed) > end
            ) {
                newSourceStart++
            }
            clip.copy(timelineStartUs = start, sourceStartUs = newSourceStart)
        } else {
            // Unbounded media (images, text): keep the source anchored at zero and resize.
            var sourceDuration = Clip.sourceDurationFor(newDuration, clip.speed).coerceAtLeast(1)
            while (sourceDuration > 1 && Clip.timelineDurationFor(sourceDuration, clip.speed) > newDuration) {
                sourceDuration--
            }
            clip.copy(timelineStartUs = start, sourceStartUs = 0, sourceEndUs = sourceDuration)
        }
        track.withClips(track.clips.map { if (it.id == clipId) updated else it })
    }
}

/** Moves the end (right edge) of a clip to [newEndUs], clamped like [TrimClipStart]. */
data class TrimClipEnd(val clipId: String, val newEndUs: Long) : EditCommand {
    override val label = "Trim clip"
    override fun apply(project: Project): Project = project.editClipTrack(clipId) { track, clip ->
        val index = track.clips.indexOf(clip)
        val nextStart = if (index < track.clips.lastIndex) track.clips[index + 1].timelineStartUs else Long.MAX_VALUE
        val minDuration = project.minClipDurationUs()
        val limit = project.sourceLimitUs(clip)
        val latestBySource = if (limit == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            clip.timelineStartUs + Clip.timelineDurationFor(limit - clip.sourceStartUs, clip.speed)
        }
        val earliest = clip.timelineStartUs + minDuration
        val upper = minOf(nextStart, latestBySource)
        if (earliest > upper) return@editClipTrack null
        val end = newEndUs.coerceIn(earliest, upper)
        if (end == clip.timelineEndUs) return@editClipTrack null
        var newSourceEnd = (clip.sourceStartUs + Clip.sourceDurationFor(end - clip.timelineStartUs, clip.speed))
            .coerceAtMost(limit)
        // Speed rounding can overshoot the clamped end by a microsecond; pull it back.
        while (newSourceEnd > clip.sourceStartUs + 1 &&
            clip.timelineStartUs + Clip.timelineDurationFor(newSourceEnd - clip.sourceStartUs, clip.speed) > upper
        ) {
            newSourceEnd--
        }
        if (newSourceEnd <= clip.sourceStartUs) return@editClipTrack null
        val updated = clip.copy(sourceEndUs = newSourceEnd)
        track.withClips(track.clips.map { if (it.id == clipId) updated else it })
    }
}

/**
 * Splits a clip at [atUs]. The left part keeps [clipId]; the right part gets [newClipId]. The
 * right part starts exactly where the left part ends, so no gap or overlap is introduced. Fade-in
 * stays with the left part and fade-out moves to the right part.
 */
data class SplitClip(val clipId: String, val atUs: Long, val newClipId: String) : EditCommand {
    override val label = "Split clip"
    override fun apply(project: Project): Project = project.editClipTrack(clipId) { track, clip ->
        if (project.sequence.clip(newClipId) != null) return@editClipTrack null
        val minDuration = project.minClipDurationUs()
        val leftDuration = atUs - clip.timelineStartUs
        if (leftDuration < minDuration || clip.timelineEndUs - atUs < minDuration) return@editClipTrack null
        val nominal = clip.sourceStartUs + Clip.sourceDurationFor(leftDuration, clip.speed)
        // With speed != 1 the two halves round independently; pick the nearest split point whose
        // halves end exactly where the original clip ended (or earlier), so neighbours never overlap.
        val parts = listOf(0L, 1L, -1L, 2L, -2L, 3L, -3L).asSequence()
            .map { nominal + it }
            .filter { it > clip.sourceStartUs && it < clip.sourceEndUs }
            .map { splitSource ->
                val left = clip.copy(sourceEndUs = splitSource, audio = clip.audio.copy(fadeOutUs = 0))
                val right = clip.copy(
                    id = newClipId,
                    timelineStartUs = left.timelineEndUs,
                    sourceStartUs = splitSource,
                    audio = clip.audio.copy(fadeInUs = 0),
                )
                left to right
            }
            .firstOrNull { (_, right) -> right.timelineEndUs <= clip.timelineEndUs }
            ?: return@editClipTrack null
        val (left, right) = parts
        track.withClips(track.clips.flatMap { if (it.id == clipId) listOf(left, right) else listOf(it) })
    }
}

/**
 * Deletes clips. With [ripple], each later clip on the same track moves left by the total
 * duration of deleted clips before it, closing the gaps.
 */
data class DeleteClips(val clipIds: Set<String>, val ripple: Boolean = false) : EditCommand {
    override val label = when {
        ripple -> "Ripple delete"
        clipIds.size == 1 -> "Delete clip"
        else -> "Delete ${clipIds.size} clips"
    }

    override fun apply(project: Project): Project {
        var changed = false
        val tracks = project.sequence.tracks.map { track ->
            if (track.locked || track.clips.none { it.id in clipIds }) return@map track
            changed = true
            if (!ripple) return@map track.withClips(track.clips.filter { it.id !in clipIds })
            var shift = 0L
            val kept = ArrayList<Clip>()
            for (clip in track.clips.sortedBy { it.timelineStartUs }) {
                if (clip.id in clipIds) {
                    shift += clip.durationUs
                } else {
                    kept += if (shift == 0L) clip else clip.copy(timelineStartUs = (clip.timelineStartUs - shift).coerceAtLeast(0))
                }
            }
            track.withClips(kept)
        }
        return if (changed) project.copy(sequence = project.sequence.copy(tracks = tracks)) else project
    }
}

/**
 * Updates non-timing properties of a clip (transform, audio, text). Timing fields changed by
 * [update] are ignored; use [SetClipSpeed] or the trim/move commands for timing.
 */
class UpdateClip(
    val clipId: String,
    override val label: String,
    private val update: (Clip) -> Clip,
) : EditCommand {
    override fun apply(project: Project): Project = project.editClipTrack(clipId) { track, clip ->
        val proposed = update(clip)
        val updated = clip.copy(
            transform = proposed.transform.sanitized(),
            audio = proposed.audio.sanitized(),
            text = if (clip.isText) (proposed.text ?: clip.text)?.sanitized() else null,
            keyframes = proposed.keyframes.sortedBy { it.sourceUs },
            volumeKeyframes = proposed.volumeKeyframes.sortedBy { it.sourceUs },
            color = if (clip.isText) ColorGrade.NONE else proposed.color.sanitized(),
            effects = proposed.effects.sanitized(),
        )
        if (updated == clip) return@editClipTrack null
        track.withClips(track.clips.map { if (it.id == clipId) updated else it })
    }
}

/**
 * Changes playback speed while keeping the source range and the clip start. If the clip becomes
 * longer, later clips are pushed right using the same rules as [Placement].
 */
data class SetClipSpeed(val clipId: String, val speed: Float) : EditCommand {
    override val label = "Change speed"
    override fun apply(project: Project): Project = project.editClipTrack(clipId) { track, clip ->
        if (clip.isText) return@editClipTrack null
        val s = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        if (s == clip.speed) return@editClipTrack null
        val updated = clip.copy(speed = s)
        if (updated.durationUs < project.minClipDurationUs()) return@editClipTrack null
        val placed = Placement.place(track.clips.filter { it.id != clipId }, updated, clip.timelineStartUs)
        track.withClips(placed.clips)
    }

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
    }
}

/** Places a copy of a clip right after the original on the same track (later clips are pushed). */
data class DuplicateClip(val clipId: String, val newClipId: String) : EditCommand {
    override val label = "Duplicate clip"
    override fun apply(project: Project): Project = project.editClipTrack(clipId) { track, clip ->
        if (project.sequence.clip(newClipId) != null) return@editClipTrack null
        val copy = clip.copy(id = newClipId)
        track.withClips(Placement.place(track.clips, copy, clip.timelineEndUs).clips)
    }
}

/** Groups several commands into one undo step. */
class CompositeCommand(override val label: String, private val commands: List<EditCommand>) : EditCommand {
    override fun apply(project: Project): Project = commands.fold(project) { p, c -> c.apply(p) }
}

/** Whether [type] may be placed on a track of [kind]. */
fun TrackKind.accepts(type: MediaType): Boolean = Track("", this, "").accepts(type)
