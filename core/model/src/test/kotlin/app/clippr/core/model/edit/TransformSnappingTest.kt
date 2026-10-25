package app.clippr.core.model.edit

import app.clippr.core.model.Transform
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransformSnappingTest {

    @Test
    fun rotationSnapsToStraightAndQuarterTurns() {
        assertEquals(0f, TransformSnapping.snap(Transform(rotationDegrees = 3.5f)).transform.rotationDegrees)
        assertEquals(90f, TransformSnapping.snap(Transform(rotationDegrees = 87f)).transform.rotationDegrees)
        assertEquals(-90f, TransformSnapping.snap(Transform(rotationDegrees = -94f)).transform.rotationDegrees)
        assertEquals(180f, TransformSnapping.snap(Transform(rotationDegrees = 176f)).transform.rotationDegrees)
        val free = TransformSnapping.snap(Transform(rotationDegrees = 30f))
        assertEquals(30f, free.transform.rotationDegrees)
        assertFalse(free.rotation)
    }

    @Test
    fun scaleAndCentreSnap() {
        val r = TransformSnapping.snap(Transform(scale = 1.03f, positionX = 0.02f, positionY = -0.2f))
        assertEquals(1f, r.transform.scale)
        assertEquals(0f, r.transform.positionX)
        assertEquals(-0.2f, r.transform.positionY)
        assertTrue(r.scale && r.centerX && !r.centerY)
        assertEquals(0.5f, TransformSnapping.snap(Transform(scale = 0.5f)).transform.scale)
    }
}
