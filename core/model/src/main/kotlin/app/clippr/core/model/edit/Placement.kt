package app.clippr.core.model.edit

import app.clippr.core.model.Clip

/**
 * Non-destructive placement rules shared by inserting and moving clips.
 *
 * Clippr never silently deletes or shortens existing clips when something is dropped onto a
 * track. Instead:
 *
 * 1. On tracks that allow overlaps (video), dropping a clip so it overlaps the end of the
 *    previous clip by up to half of either clip keeps that overlap: it becomes a transition, as in
 *    Vegas Pro. The same goes for the clip's end overlapping the start of the next clip.
 * 2. Otherwise, if the requested start falls strictly inside an existing clip, the new clip is
 *    placed at that clip's start (when dropped on its first half) or end (second half). Dropping
 *    a clip on the front half of another therefore reorders them.
 * 3. Clips at or after the insertion point are pushed right only as far as needed to make room,
 *    keeping their own transitions. Gaps further down the track absorb the push.
 */
object Placement {

    data class Result(val clips: List<Clip>, val placed: Clip)

    /**
     * Places [clip] on a track that currently holds [existing] (which must not contain [clip]),
     * as close as possible to [desiredStartUs].
     */
    fun place(existing: List<Clip>, clip: Clip, desiredStartUs: Long, allowOverlap: Boolean = false): Result {
        val others = existing.filter { it.id != clip.id }.sortedBy { it.timelineStartUs }
        var start = desiredStartUs.coerceAtLeast(0)

        val hostIndex = others.indexOfLast { it.timelineStartUs < start && start < it.timelineEndUs }
        if (hostIndex >= 0) {
            val host = others[hostIndex]
            val overlap = host.timelineEndUs - start
            val hostIncomingEnd = if (hostIndex > 0) others[hostIndex - 1].timelineEndUs else Long.MIN_VALUE
            val hostOutgoingStart = others.getOrNull(hostIndex + 1)?.timelineStartUs ?: Long.MAX_VALUE
            val keepsOverlap = allowOverlap &&
                overlap <= Overlaps.maxOverlap(host, clip) &&
                start >= hostIncomingEnd &&
                // The host must not already be handing over to another clip.
                hostOutgoingStart >= host.timelineEndUs
            if (!keepsOverlap) {
                start = if (start - host.timelineStartUs < host.timelineEndUs - start) host.timelineStartUs else host.timelineEndUs
            }
        }

        val placed = clip.copy(timelineStartUs = start)
        val result = ArrayList<Clip>(others.size + 1)
        var prevNew: Clip = placed
        var prevOriginal: Clip? = null
        var pushing = true
        for (other in others) {
            if (other.timelineStartUs < start) {
                result += other
                continue
            }
            if (!pushing) {
                result += other
                continue
            }
            val minStart = if (prevOriginal == null) {
                // The clip right after the placed one: keep a valid overlap, otherwise butt up.
                val overlap = placed.timelineEndUs - other.timelineStartUs
                if (allowOverlap && overlap > 0 && overlap <= Overlaps.maxOverlap(placed, other) && other.timelineStartUs > placed.timelineStartUs) {
                    other.timelineStartUs
                } else {
                    placed.timelineEndUs
                }
            } else {
                // Later clips keep the overlap they had with the clip before them.
                val kept = if (allowOverlap) (prevOriginal.timelineEndUs - other.timelineStartUs).coerceAtLeast(0) else 0L
                prevNew.timelineEndUs - kept
            }
            if (other.timelineStartUs < minStart) {
                val moved = other.copy(timelineStartUs = minStart)
                result += moved
                prevNew = moved
                prevOriginal = other
            } else {
                pushing = false
                result += other
            }
        }
        result += placed
        val normalized = Overlaps.normalize(result, allowOverlap)
        return Result(normalized, normalized.first { it.id == clip.id })
    }

    /** True if the clips are sorted and no two overlap. */
    fun isValid(clips: List<Clip>): Boolean = Overlaps.isValid(clips, allowOverlap = false)
}
