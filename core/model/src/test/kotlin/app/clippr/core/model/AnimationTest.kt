package app.clippr.core.model

import app.clippr.core.model.Fixtures.V1
import app.clippr.core.model.edit.AddAssetClip
import app.clippr.core.model.edit.DuplicateClip
import app.clippr.core.model.edit.SplitClip
import app.clippr.core.model.edit.TrimClipStart
import app.clippr.core.model.edit.UpdateClip
import app.clippr.core.model.render.AudioEnvelope
import app.clippr.core.model.render.RenderPlanner
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimationTest {

    private fun near(expected: Float, actual: Float, eps: Float = 1e-3f) =
        assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

    private val tol = 20_000L

    private fun animatedProject(): Project {
        var p = AddAssetClip(Fixtures.video10s.id, "c1").apply(Fixtures.emptyProject())
        p = UpdateClip("c1", "kf") { c ->
            c.copy(
                keyframes = listOf(
                    TransformKeyframe(0, 0f, 0f, 1f, 0f, 1f, Easing.LINEAR),
                    TransformKeyframe(4 * SEC, 1f, -1f, 2f, 90f, 0f, Easing.LINEAR),
                ),
            )
        }.apply(p)
        return p
    }

    @Test
    fun easingCurvesHitTheirEndpoints() {
        for (e in Easing.entries.filter { it != Easing.HOLD }) {
            near(0f, Animation.ease(e, 0f))
            near(1f, Animation.ease(e, 1f))
        }
        near(0.5f, Animation.ease(Easing.EASE_IN_OUT, 0.5f))
        assertTrue(Animation.ease(Easing.EASE_IN, 0.25f) < 0.25f)
        assertTrue(Animation.ease(Easing.EASE_OUT, 0.25f) > 0.25f)
        near(0f, Animation.ease(Easing.HOLD, 0.9f))
    }

    @Test
    fun interpolatesBetweenKeyframesAndHoldsOutside() {
        val clip = animatedProject().clip("c1")
        val mid = Animation.transformAt(clip, 2 * SEC)
        near(0.5f, mid.positionX)
        near(-0.5f, mid.positionY)
        near(1.5f, mid.scale)
        near(45f, mid.rotationDegrees)
        near(0.5f, mid.opacity)
        near(2f, Animation.transformAt(clip, 9 * SEC).scale)
        near(1f, Animation.transformAt(clip, 0).scale)
    }

    @Test
    fun keyframesStayAttachedToContentWhenTrimmingSplittingAndDuplicating() {
        var p = animatedProject()
        val before = Animation.transformAtTimeline(p.clip("c1"), 2 * SEC)
        p = TrimClipStart("c1", SEC).apply(p) // timeline 2s still shows source 2s
        near(before.positionX, Animation.transformAtTimeline(p.clip("c1"), 2 * SEC).positionX)
        p = SplitClip("c1", 3 * SEC, "c2").apply(p)
        val right = p.clip("c2")
        near(Animation.transformAt(right, 3 * SEC).scale, Animation.transformAtTimeline(right, 3 * SEC).scale)
        near(1.75f, Animation.transformAtTimeline(right, 3 * SEC).scale)
        p = DuplicateClip("c2", "c3").apply(p)
        assertEquals(p.clip("c2").keyframes, p.clip("c3").keyframes)
        assertEquals(p.clip("c2").timelineEndUs, p.clip("c3").timelineStartUs)
        assertTrue(ProjectIntegrity.isValid(p))
    }

    @Test
    fun editingWithoutKeyframesChangesStaticTransform() {
        val clip = AddAssetClip(Fixtures.video10s.id, "c1").apply(Fixtures.emptyProject()).clip("c1")
        val edited = Animation.editTransformAt(clip, 3 * SEC, tol) { it.copy(scale = 2f) }
        near(2f, edited.transform.scale)
        assertTrue(edited.keyframes.isEmpty())
    }

    @Test
    fun editingWithKeyframesUpdatesOrCreatesKeyframeAtPlayhead() {
        val clip = animatedProject().clip("c1")
        val onKey = Animation.editTransformAt(clip, 4 * SEC + 5_000, tol) { it.copy(scale = 3f) }
        assertEquals(2, onKey.keyframes.size)
        near(3f, onKey.keyframes[1].scale)
        val between = Animation.editTransformAt(clip, 2 * SEC, tol) { it.copy(positionX = -1f) }
        assertEquals(listOf(0L, 2 * SEC, 4 * SEC), between.keyframes.map { it.sourceUs })
        near(-1f, between.keyframes[1].positionX)
        near(1.5f, between.keyframes[1].scale) // other values captured from the animation
    }

    @Test
    fun addRemoveKeyframesAndEasing() {
        val base = AddAssetClip(Fixtures.video10s.id, "c1").apply(Fixtures.emptyProject()).clip("c1")
            .copy(transform = Transform(scale = 1.3f))
        val one = Animation.addTransformKeyframe(base, SEC, tol)
        assertEquals(1, one.keyframes.size)
        near(1.3f, one.keyframes[0].scale)
        assertEquals(one, Animation.addTransformKeyframe(one, SEC + 1_000, tol))
        val eased = Animation.setTransformEasing(one, SEC, tol, Easing.HOLD)
        assertEquals(Easing.HOLD, eased.keyframes[0].easing)
        val moved = Animation.editTransformAt(eased, SEC, tol) { it.copy(scale = 2.5f) }
        val removed = Animation.removeTransformKeyframe(moved, SEC, tol)
        assertTrue(removed.keyframes.isEmpty())
        near(2.5f, removed.transform.scale) // last keyframe's values are kept
    }

    @Test
    fun volumeKeyframesDriveTheAudioEnvelope() {
        var p = AddAssetClip(Fixtures.music.id, "m").apply(Fixtures.emptyProject())
        p = UpdateClip("m", "v") { c ->
            Animation.editVolumeAt(
                Animation.addVolumeKeyframe(
                    Animation.editVolumeAt(Animation.addVolumeKeyframe(c, 0, tol), 0, tol, 0f),
                    2 * SEC,
                    tol,
                ),
                2 * SEC,
                tol,
                1f,
            )
        }.apply(p)
        val clip = p.clip("m")
        assertEquals(2, clip.volumeKeyframes.size)
        val mix = RenderPlanner.mixFor(clip)!!
        val env = AudioEnvelope(mix, clip.durationUs)
        near(0f, env.gainAt(0))
        near(0.5f, env.gainAt(SEC))
        near(1f, env.gainAt(5 * SEC))
        assertEquals(AudioEnvelope.NOT_UNITY, env.unityUntilUs(5 * SEC))
    }

    @Test
    fun fadeFactorAndKeyframeTimelinePositions() {
        val fx = VisualEffects(fadeInUs = SEC, fadeOutUs = 2 * SEC)
        near(0f, Animation.fadeFactor(fx, 0, 10 * SEC))
        near(0.5f, Animation.fadeFactor(fx, SEC / 2, 10 * SEC))
        near(1f, Animation.fadeFactor(fx, 5 * SEC, 10 * SEC))
        near(0.5f, Animation.fadeFactor(fx, 9 * SEC, 10 * SEC))

        val p = TrimClipStart("c1", 2 * SEC).apply(animatedProject())
        val clip = p.clip("c1")
        assertNull(Animation.timelineTimeOf(clip, 0)) // trimmed away
        assertEquals(4 * SEC, Animation.timelineTimeOf(clip, 4 * SEC))
        assertEquals(V1, p.sequence.trackOfClip("c1")?.id)
    }
}
