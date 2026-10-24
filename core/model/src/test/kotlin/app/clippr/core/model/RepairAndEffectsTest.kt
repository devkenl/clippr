package app.clippr.core.model

import app.clippr.core.model.edit.AddAssetClip
import app.clippr.core.model.edit.UpdateClip
import app.clippr.core.model.render.FrameGeometry
import app.clippr.core.model.serialization.ProjectCodec
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepairAndEffectsTest {

    /** Builds a project whose clip "c1" holds exactly what [update] returns, bypassing commands. */
    private fun clipProject(update: (Clip) -> Clip): Project {
        val p = AddAssetClip(Fixtures.video10s.id, "c1").apply(Fixtures.emptyProject())
        val track = p.track(Fixtures.V1)
        return p.copy(sequence = p.sequence.replaceTrack(track.copy(clips = track.clips.map(update))))
    }

    @Test
    fun nanAndInfiniteKeyframesAreRepaired() {
        val broken = clipProject {
            it.copy(
                keyframes = listOf(
                    TransformKeyframe(2 * SEC, Float.NaN, 0f, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN),
                    TransformKeyframe(0, 0f, 0f, 0f, 0f, 1f),
                    TransformKeyframe(2 * SEC, 0.5f, 0f, 1f, 0f, 1f),
                ),
                volumeKeyframes = listOf(VolumeKeyframe(SEC, Float.NaN)),
            )
        }
        val result = ProjectRepair.repair(broken)
        assertTrue(result.changed)
        val keys = result.project.clip("c1").keyframes
        assertEquals(listOf(0L, 2 * SEC), keys.map { it.sourceUs }, "sorted, one keyframe per time")
        for (k in keys) {
            assertTrue(k.positionX.isFinite() && k.scale.isFinite() && k.rotationDegrees.isFinite() && k.opacity.isFinite())
            assertTrue(k.scale >= Transform.MIN_SCALE, "zero scale would collapse the frame")
        }
        assertEquals(1f, result.project.clip("c1").volumeKeyframes.single().volume)
        // Evaluating the animation now gives finite values everywhere.
        for (t in 0..10) {
            val tr = Animation.transformAt(result.project.clip("c1"), t * SEC / 2)
            assertTrue(tr.scale.isFinite() && tr.positionX.isFinite() && tr.opacity.isFinite())
        }
    }

    @Test
    fun healthyProjectIsUntouched() {
        val p = clipProject { it.copy(keyframes = listOf(TransformKeyframe(0, 0.1f, 0.2f, 1.2f, 10f, 0.9f))) }
        val result = ProjectRepair.repair(p)
        assertFalse(result.changed)
        assertTrue(result.project === p)
    }

    @Test
    fun nanTransformAndColourFallBackToDefaults() {
        val p = clipProject {
            it.copy(
                transform = Transform(positionX = Float.NaN, scale = Float.NaN, opacity = Float.NEGATIVE_INFINITY),
                color = ColorGrade(exposure = Float.NaN, saturation = 5f),
                effects = VisualEffects(blur = Float.NaN),
            )
        }
        val c = ProjectRepair.repair(p).project.clip("c1")
        assertEquals(0f, c.transform.positionX)
        assertEquals(1f, c.transform.scale)
        assertEquals(1f, c.transform.opacity)
        assertEquals(0f, c.color.exposure)
        assertEquals(1f, c.color.saturation)
        assertEquals(0f, c.effects.blur)
    }

    @Test
    fun brokenDocumentStillOpensAfterSaving() {
        val p = clipProject {
            it.copy(keyframes = listOf(TransformKeyframe(0, Float.NaN, 0f, Float.NaN, 0f, 1f), TransformKeyframe(SEC, 0f, 0f, 1f, 0f, 1f)))
        }
        val decoded = ProjectCodec.decode(ProjectCodec.encode(p))
        val k = decoded.clip("c1").keyframes.first()
        assertTrue(k.scale >= Transform.MIN_SCALE)
    }

    @Test
    fun undecodableClipIsDroppedInsteadOfFailingTheProject() {
        val p = AddAssetClip(Fixtures.video6s.id, "c2").apply(AddAssetClip(Fixtures.video10s.id, "c1").apply(Fixtures.emptyProject()))
        // Corrupt one clip so its source range is empty.
        val json = ProjectCodec.encode(p).replace("\"in\":0,\"out\":6000000", "\"in\":6000000,\"out\":6000000")
        assertTrue(json.contains("\"in\":6000000,\"out\":6000000"), "test setup must corrupt the clip")
        val decoded = ProjectCodec.decode(json)
        assertEquals(listOf("c1"), decoded.sequence.allClips.map { it.id })
    }

    @Test
    fun styleEffectsAndFlipsRoundTrip() {
        val p = clipProject {
            it.copy(
                transform = it.transform.copy(flipHorizontal = true),
                effects = VisualEffects(styles = listOf(StyleEffect(StyleEffectType.VHS, 0.3f), StyleEffect(StyleEffectType.SHARPEN))),
            )
        }
        assertEquals(p, ProjectCodec.decode(ProjectCodec.encode(p)))
    }

    @Test
    fun unknownStyleEffectFromNewerVersionIsSkipped() {
        val p = clipProject { it.copy(effects = VisualEffects(styles = listOf(StyleEffect(StyleEffectType.GLOW, 0.5f)))) }
        val json = ProjectCodec.encode(p).replace("\"glow\"", "\"hologram\"")
        assertTrue(ProjectCodec.decode(json).clip("c1").effects.styles.isEmpty())
    }

    @Test
    fun toggleAddsOnceAndRemoves() {
        var fx = VisualEffects.NONE
        fx = StyleEffects.toggle(fx, StyleEffectType.GLITCH)
        assertEquals(listOf(StyleEffectType.GLITCH), fx.styles.map { it.type })
        assertEquals(StyleEffectType.GLITCH.defaultAmount, fx.styles.single().amount)
        fx = StyleEffects.setAmount(fx, StyleEffectType.GLITCH, 3f)
        assertEquals(1f, fx.styles.single().amount)
        fx = StyleEffects.toggle(fx, StyleEffectType.GLITCH)
        assertTrue(fx.styles.isEmpty())
        val dup = VisualEffects(styles = listOf(StyleEffect(StyleEffectType.VHS), StyleEffect(StyleEffectType.VHS, 0.1f))).sanitized()
        assertEquals(1, dup.styles.size)
    }

    @Test
    fun styleEffectIdsAreUnique() {
        assertEquals(StyleEffectType.entries.size, StyleEffectType.entries.map { it.id }.toSet().size)
    }

    @Test
    fun horizontalFlipMirrorsAboutTheClipCentre() {
        val t = Transform(positionX = 0.5f, flipHorizontal = true)
        val m = FrameGeometry.ndcMatrix(t, 16f / 9f)
        // The clip's centre stays at its position; its right edge lands on the left.
        assertTrue(abs(m.mapX(0f, 0f) - 0.5f) < 1e-5f)
        assertTrue(abs(m.mapX(1f, 0f) - (-0.5f)) < 1e-5f)
        assertFalse(t.isIdentityPlacement)
        val v = FrameGeometry.ndcMatrix(Transform(flipVertical = true), 1f)
        assertTrue(abs(v.mapY(0f, 1f) + 1f) < 1e-5f)
    }
}

class UpdateClipRepairTest {
    @Test
    fun liveEditsCannotStoreNanKeyframes() {
        val p = AddAssetClip(Fixtures.video10s.id, "c1").apply(Fixtures.emptyProject())
        val edited = UpdateClip("c1", "kf") { it.copy(keyframes = listOf(TransformKeyframe(0, 0f, 0f, Float.NaN, 0f, 1f))) }.apply(p)
        assertEquals(1f, edited.clip("c1").keyframes.single().scale)
    }
}
