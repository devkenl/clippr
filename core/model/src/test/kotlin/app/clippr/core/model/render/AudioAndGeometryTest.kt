package app.clippr.core.model.render

import app.clippr.core.model.Crop
import app.clippr.core.model.FitMode
import app.clippr.core.model.SEC
import app.clippr.core.model.Transform
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioAndGeometryTest {

    private fun near(expected: Float, actual: Float, eps: Float = 1e-3f) =
        assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

    @Test
    fun envelopeAppliesVolumeAndLinearFades() {
        val env = AudioEnvelope(AudioMix(volume = 0.5f, fadeInUs = SEC, fadeOutUs = 2 * SEC), durationUs = 10 * SEC)
        near(0f, env.gainAt(0))
        near(0.25f, env.gainAt(SEC / 2))
        near(0.5f, env.gainAt(5 * SEC))
        near(0.25f, env.gainAt(9 * SEC))
        near(0f, env.gainAt(10 * SEC))
        assertEquals(AudioEnvelope.NOT_UNITY, env.unityUntilUs(5 * SEC))
    }

    @Test
    fun envelopeReportsUnityRegions() {
        val env = AudioEnvelope(AudioMix(1f, SEC, SEC), 10 * SEC)
        assertEquals(AudioEnvelope.NOT_UNITY, env.unityUntilUs(0))
        assertEquals(9 * SEC, env.unityUntilUs(2 * SEC))
        assertEquals(AudioEnvelope.NOT_UNITY, env.unityUntilUs(9 * SEC))
        assertEquals(AudioEnvelope.UNITY_TO_END, AudioEnvelope(AudioMix(1f, 0, 0), 10 * SEC).unityUntilUs(3 * SEC))
    }

    @Test
    fun identityTransformGivesIdentityMatrix() {
        assertTrue(FrameGeometry.ndcMatrix(Transform.DEFAULT, 16f / 9f).isIdentity)
    }

    @Test
    fun translationMovesCentreToNormalizedPosition() {
        val m = FrameGeometry.ndcMatrix(Transform(positionX = 0.5f, positionY = -0.25f, scale = 2f), 16f / 9f)
        near(0.5f, m.mapX(0f, 0f))
        near(-0.25f, m.mapY(0f, 0f))
        // Scale doubles distances from the centre.
        near(0.5f + 0.2f, m.mapX(0.1f, 0f))
    }

    @Test
    fun rotationIsRigidInPixelSpaceAndClockwise() {
        val w = 1920f
        val h = 1080f
        val m = FrameGeometry.ndcMatrix(Transform(rotationDegrees = 90f), w / h)
        // NDC point on the right edge at the centre line: pixel offset (+960, 0).
        val x = m.mapX(1f, 0f)
        val y = m.mapY(1f, 0f)
        // Clockwise 90° in a y-up system sends +x to -y; length in pixels must be preserved.
        val px = x * w / 2
        val py = y * h / 2
        near(0f, px, 0.5f)
        near(-960f, py, 0.5f)
        near(960f, hypot(px, py), 0.5f)
    }

    @Test
    fun fitAndFillFramesMatchCanvas() {
        // 4:3 source in a 16:9 canvas: fit is pillarboxed to full height.
        val fit = FrameGeometry.clipFrame(4000, 3000, Transform.DEFAULT, 1920f, 1080f)
        near(1440f, fit.width)
        near(1080f, fit.height)
        val fill = FrameGeometry.clipFrame(4000, 3000, Transform(fit = FitMode.FILL, scale = 0.5f), 1920f, 1080f)
        near(960f, fill.width)
        near(540f, fill.height)
        // Cropping 25% from left and right turns 4:3 into 2:3.
        val cropped = FrameGeometry.clipFrame(4000, 3000, Transform(crop = Crop(left = 0.25f, right = 0.25f)), 1920f, 1080f)
        near(720f, cropped.width)
    }

    @Test
    fun clipFrameHitTestingHonoursRotationAndPosition() {
        val frame = FrameGeometry.clipFrame(
            1000, 100, Transform(positionX = 0.5f, rotationDegrees = 90f, fit = FitMode.FIT),
            1000f, 1000f,
        )
        // Centre at x = 750; rotated 90° the long side is vertical.
        assertTrue(frame.contains(750f, 500f + 400f))
        assertFalse(frame.contains(750f + 400f, 500f))
        val (px, py) = FrameGeometry.positionForCenter(750f, 500f, 1000f, 1000f)
        near(0.5f, px)
        near(0f, py)
    }

    @Test
    fun cropNdcUsesMedia3Convention() {
        val ndc = FrameGeometry.cropNdc(Crop(left = 0.1f, top = 0.2f, right = 0f, bottom = 0f))
        near(-0.8f, ndc[0]) // left
        near(1f, ndc[1]) // right
        near(-1f, ndc[2]) // bottom
        near(0.6f, ndc[3]) // top
    }
}
