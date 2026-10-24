package app.clippr.core.model.edit

import app.clippr.core.model.TimelineSequence
import kotlin.math.abs

/** Magnetic snapping of timeline times to the playhead and clip edges. */
object Snapping {

    /** [timeUs] is the snapped value; [snappedTo] is the target it snapped to, if any. */
    data class Result(val timeUs: Long, val snappedTo: Long?)

    /** Snap points: zero, the playhead, and every clip edge except those of [excludeClipIds]. */
    fun targets(sequence: TimelineSequence, playheadUs: Long, excludeClipIds: Set<String> = emptySet()): List<Long> {
        val points = HashSet<Long>()
        points += 0L
        points += playheadUs
        for (track in sequence.tracks) {
            for (clip in track.clips) {
                if (clip.id in excludeClipIds) continue
                points += clip.timelineStartUs
                points += clip.timelineEndUs
            }
        }
        return points.sorted()
    }

    /** Snaps a single time to the nearest target within [thresholdUs]. */
    fun snap(timeUs: Long, targets: List<Long>, thresholdUs: Long): Result {
        var best: Long? = null
        var bestDistance = Long.MAX_VALUE
        for (t in targets) {
            val d = abs(t - timeUs)
            if (d <= thresholdUs && d < bestDistance) {
                best = t
                bestDistance = d
            }
        }
        return if (best == null) Result(timeUs, null) else Result(best, best)
    }

    /**
     * Snaps a range of [durationUs] starting at [startUs] by whichever edge is closer to a
     * target. Returns the adjusted start and the target line to display.
     */
    fun snapRange(startUs: Long, durationUs: Long, targets: List<Long>, thresholdUs: Long): Result {
        val byStart = snap(startUs, targets, thresholdUs)
        val byEnd = snap(startUs + durationUs, targets, thresholdUs)
        val startDistance = if (byStart.snappedTo != null) abs(byStart.timeUs - startUs) else Long.MAX_VALUE
        val endDistance = if (byEnd.snappedTo != null) abs(byEnd.timeUs - (startUs + durationUs)) else Long.MAX_VALUE
        return when {
            startDistance == Long.MAX_VALUE && endDistance == Long.MAX_VALUE -> Result(startUs, null)
            startDistance <= endDistance -> byStart
            else -> Result(byEnd.timeUs - durationUs, byEnd.snappedTo)
        }
    }
}
