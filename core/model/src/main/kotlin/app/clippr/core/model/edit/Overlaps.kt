package app.clippr.core.model.edit

import app.clippr.core.model.Clip

/**
 * Rules for clips overlapping on a video track (the overlap is the transition, as in Vegas Pro).
 *
 * - A clip may overlap only the clip right before it.
 * - An overlap is at most half of either clip, so a clip's incoming and outgoing transitions
 *   never meet and three clips never play at once on one track.
 *
 * Other track kinds never allow overlaps.
 */
object Overlaps {

    /** The longest transition allowed between [a] and the following clip [b]. */
    fun maxOverlap(a: Clip, b: Clip): Long = minOf(a.durationUs, b.durationUs) / 2

    /** How much the clip at [index] overlaps its predecessor in [clips] (sorted), or 0. */
    fun overlapIn(clips: List<Clip>, index: Int): Long =
        if (index <= 0) 0L else (clips[index - 1].timelineEndUs - clips[index].timelineStartUs).coerceAtLeast(0)

    /** How much the clip at [index] overlaps its successor, or 0. */
    fun overlapOut(clips: List<Clip>, index: Int): Long =
        if (index >= clips.lastIndex) 0L else (clips[index].timelineEndUs - clips[index + 1].timelineStartUs).coerceAtLeast(0)

    fun isValid(clips: List<Clip>, allowOverlap: Boolean): Boolean {
        for (i in 1 until clips.size) {
            val prev = clips[i - 1]
            val cur = clips[i]
            if (cur.timelineStartUs < prev.timelineStartUs) return false
            val overlap = prev.timelineEndUs - cur.timelineStartUs
            if (overlap > 0) {
                if (!allowOverlap) return false
                if (cur.timelineStartUs <= prev.timelineStartUs) return false
                if (overlap > maxOverlap(prev, cur)) return false
                if (i >= 2 && cur.timelineStartUs < clips[i - 2].timelineEndUs) return false
            }
        }
        return true
    }

    /**
     * Pushes clips right (never left, never shortening anything) until [clips] satisfy the rules.
     * Valid overlaps are kept; invalid ones are removed by moving the later clip to the end of the
     * earlier one.
     */
    fun normalize(clips: List<Clip>, allowOverlap: Boolean): List<Clip> {
        val sorted = clips.sortedBy { it.timelineStartUs }
        val out = ArrayList<Clip>(sorted.size)
        var prevOriginal: Clip? = null
        var prevShift = 0L
        for (clip in sorted) {
            var cur = clip
            // A clip overlapping one that was pushed moves with it, keeping its transition.
            if (prevOriginal != null && prevShift > 0 && clip.timelineStartUs < prevOriginal.timelineEndUs) {
                cur = cur.copy(timelineStartUs = clip.timelineStartUs + prevShift)
            }
            val prev = out.lastOrNull()
            if (prev != null) {
                val overlap = prev.timelineEndUs - cur.timelineStartUs
                if (overlap > 0) {
                    val beforePrevEnd = if (out.size >= 2) out[out.size - 2].timelineEndUs else Long.MIN_VALUE
                    val ok = allowOverlap &&
                        cur.timelineStartUs > prev.timelineStartUs &&
                        overlap <= maxOverlap(prev, cur) &&
                        cur.timelineStartUs >= beforePrevEnd
                    if (!ok) cur = cur.copy(timelineStartUs = prev.timelineEndUs)
                }
            }
            out += cur
            prevShift = cur.timelineStartUs - clip.timelineStartUs
            prevOriginal = clip
        }
        return out
    }
}
