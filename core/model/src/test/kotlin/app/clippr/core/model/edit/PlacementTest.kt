package app.clippr.core.model.edit

import app.clippr.core.model.Clip
import app.clippr.core.model.SEC
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlacementTest {

    private fun clip(id: String, start: Long, duration: Long) =
        Clip(id = id, assetId = "a", timelineStartUs = start, sourceStartUs = 0, sourceEndUs = duration)

    private fun starts(clips: List<Clip>) = clips.associate { it.id to it.timelineStartUs }

    @Test
    fun placesIntoFreeSpaceUnchanged() {
        val existing = listOf(clip("a", 0, 2 * SEC))
        val r = Placement.place(existing, clip("b", 0, SEC), 5 * SEC)
        assertEquals(mapOf("a" to 0L, "b" to 5 * SEC), starts(r.clips))
    }

    @Test
    fun negativeStartIsClampedToZero() {
        val r = Placement.place(emptyList(), clip("b", 0, SEC), -3 * SEC)
        assertEquals(0L, r.placed.timelineStartUs)
    }

    @Test
    fun droppingOnFirstHalfInsertsBeforeAndPushes() {
        val existing = listOf(clip("a", 0, 4 * SEC), clip("c", 4 * SEC, 2 * SEC))
        val r = Placement.place(existing, clip("b", 0, SEC), SEC) // inside a, first half
        assertEquals(mapOf("b" to 0L, "a" to SEC, "c" to 5 * SEC), starts(r.clips))
        assertTrue(Placement.isValid(r.clips))
    }

    @Test
    fun droppingOnSecondHalfInsertsAfter() {
        val existing = listOf(clip("a", 0, 4 * SEC), clip("c", 4 * SEC, 2 * SEC))
        val r = Placement.place(existing, clip("b", 0, SEC), 3 * SEC) // inside a, second half
        assertEquals(mapOf("a" to 0L, "b" to 4 * SEC, "c" to 5 * SEC), starts(r.clips))
    }

    @Test
    fun gapsAbsorbPushes() {
        // a [0,2) gap [2,10) c [10,12)
        val existing = listOf(clip("a", 0, 2 * SEC), clip("c", 10 * SEC, 2 * SEC))
        val r = Placement.place(existing, clip("b", 0, 3 * SEC), 0) // before a
        // b [0,3) pushes a to [3,5); the gap absorbs it so c does not move.
        assertEquals(mapOf("b" to 0L, "a" to 3 * SEC, "c" to 10 * SEC), starts(r.clips))
    }

    @Test
    fun partialOverlapIntoNextClipPushesIt() {
        val existing = listOf(clip("a", 0, 2 * SEC), clip("c", 3 * SEC, 2 * SEC))
        val r = Placement.place(existing, clip("b", 0, 2 * SEC), 2 * SEC)
        assertEquals(mapOf("a" to 0L, "b" to 2 * SEC, "c" to 4 * SEC), starts(r.clips))
        assertTrue(Placement.isValid(r.clips))
    }
}
