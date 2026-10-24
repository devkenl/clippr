package app.clippr.core.model.edit

import app.clippr.core.model.Clip

/**
 * Non-destructive placement rules shared by inserting and moving clips.
 *
 * Clippr never silently deletes or shortens existing clips when something is dropped onto a
 * track. Instead:
 *
 * 1. If the requested start falls strictly inside an existing clip, the new clip is placed at
 *    that clip's start (when dropped on its first half) or end (second half). Dropping a clip on
 *    the front half of another therefore reorders them.
 * 2. Clips at or after the insertion point are pushed right only as far as needed to make room.
 *    Gaps further down the track absorb the push, so distant clips do not move.
 */
object Placement {

    data class Result(val clips: List<Clip>, val placed: Clip)

    /**
     * Places [clip] on a track that currently holds [existing] (which must not contain [clip]),
     * as close as possible to [desiredStartUs].
     */
    fun place(existing: List<Clip>, clip: Clip, desiredStartUs: Long): Result {
        val others = existing.filter { it.id != clip.id }.sortedBy { it.timelineStartUs }
        var start = desiredStartUs.coerceAtLeast(0)

        val host = others.firstOrNull { it.timelineStartUs < start && start < it.timelineEndUs }
        if (host != null) {
            start = if (start - host.timelineStartUs < host.timelineEndUs - start) {
                host.timelineStartUs
            } else {
                host.timelineEndUs
            }
        }

        val placed = clip.copy(timelineStartUs = start)
        var cursor = placed.timelineEndUs
        val result = ArrayList<Clip>(others.size + 1)
        var pushing = true
        for (other in others) {
            if (other.timelineStartUs < start) {
                result += other
                continue
            }
            if (pushing && other.timelineStartUs < cursor) {
                val moved = other.copy(timelineStartUs = cursor)
                result += moved
                cursor = moved.timelineEndUs
            } else {
                pushing = false
                result += other
            }
        }
        result += placed
        result.sortBy { it.timelineStartUs }
        return Result(result, placed)
    }

    /** True if the clips are sorted and no two overlap. */
    fun isValid(clips: List<Clip>): Boolean {
        for (i in 1 until clips.size) {
            if (clips[i].timelineStartUs < clips[i - 1].timelineEndUs) return false
        }
        return true
    }
}
