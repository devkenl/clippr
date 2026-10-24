package app.clippr.core.model.edit

import app.clippr.core.model.Fixtures
import app.clippr.core.model.SEC
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnappingTest {

    @Test
    fun targetsIncludeEdgesAndPlayheadButNotExcludedClip() {
        var p = AddAssetClip(Fixtures.video10s.id, "c1").apply(Fixtures.emptyProject())
        p = AddAssetClip(Fixtures.video6s.id, "c2").apply(p)
        val targets = Snapping.targets(p.sequence, playheadUs = 3 * SEC, excludeClipIds = setOf("c2"))
        assertEquals(listOf(0L, 3 * SEC, 10 * SEC), targets)
    }

    @Test
    fun snapsWithinThresholdOnly() {
        val targets = listOf(0L, 5 * SEC)
        assertEquals(5 * SEC, Snapping.snap(5 * SEC + 40_000, targets, 50_000).timeUs)
        val far = Snapping.snap(5 * SEC + 60_000, targets, 50_000)
        assertEquals(5 * SEC + 60_000, far.timeUs)
        assertNull(far.snappedTo)
    }

    @Test
    fun rangeSnapsByClosestEdge() {
        val targets = listOf(10 * SEC)
        // End edge (8s + 2s = 10s - 30ms) is closer than the start edge.
        val r = Snapping.snapRange(8 * SEC - 30_000, 2 * SEC, targets, 100_000)
        assertEquals(8 * SEC, r.timeUs)
        assertEquals(10 * SEC, r.snappedTo)
    }
}
