package app.clippr.core.model.serialization

import app.clippr.core.model.ColorGrade
import app.clippr.core.model.Crop
import app.clippr.core.model.Easing
import app.clippr.core.model.FilterPreset
import app.clippr.core.model.TransformKeyframe
import app.clippr.core.model.VisualEffects
import app.clippr.core.model.VolumeKeyframe
import app.clippr.core.model.FitMode
import app.clippr.core.model.Fixtures
import app.clippr.core.model.SEC
import app.clippr.core.model.TextAlignment
import app.clippr.core.model.TextStyle
import app.clippr.core.model.TimelineSequence
import app.clippr.core.model.edit.AddAssetClip
import app.clippr.core.model.edit.AddTextClip
import app.clippr.core.model.edit.SetClipSpeed
import app.clippr.core.model.edit.SetTrackLocked
import app.clippr.core.model.edit.UpdateClip
import app.clippr.core.model.time.FrameRate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProjectCodecTest {

    @Test
    fun richProjectRoundTripsExactly() {
        var p = Fixtures.emptyProject().copy(
            name = "Trip \"2026\" — ünïcödé\n✂",
            settings = Fixtures.emptyProject().settings.copy(frameRate = FrameRate.NTSC_29_97),
            createdAtMs = 1_761_000_000_000L,
            modifiedAtMs = 1_761_000_123_456L,
        )
        p = AddAssetClip(Fixtures.video10s.id, "c1").apply(p)
        p = AddAssetClip(Fixtures.music.id, "m1").apply(p)
        p = SetClipSpeed("c1", 1.5f).apply(p)
        p = UpdateClip("c1", "t") {
            it.copy(
                transform = it.transform.copy(positionX = 0.25f, scale = 1.75f, rotationDegrees = -12.5f, opacity = 0.8f, crop = Crop(0.1f, 0f, 0.05f, 0.2f), fit = FitMode.FILL),
                audio = it.audio.copy(volume = 0.42f, fadeInUs = 1_234_567, fadeOutUs = 7),
            )
        }.apply(p)
        p = AddTextClip(
            "t1",
            TextStyle("Hello\tworld", colorArgb = 0xFFA78BFA.toInt(), alignment = TextAlignment.END, fontId = "custom:abc.ttf", strokeWidth = 0.1f, shadow = true),
        ).apply(p)
        p = UpdateClip("c1", "look") {
            it.copy(
                keyframes = listOf(TransformKeyframe(0, 0f, 0f, 1f, 0f, 1f), TransformKeyframe(2_000_000, 0.5f, 0.1f, 1.4f, 15f, 0.8f, Easing.HOLD)),
                volumeKeyframes = listOf(VolumeKeyframe(0, 0.2f), VolumeKeyframe(1_000_000, 0.9f, Easing.EASE_OUT)),
                color = ColorGrade(exposure = 0.5f, contrast = -0.2f, filter = FilterPreset.TEAL_ORANGE, filterStrength = 0.7f, lutId = "film.cube"),
                effects = VisualEffects(blur = 0.3f, vignette = 0.5f, grain = 0.2f, fadeInUs = 500_000, fadeOutUs = 250_000),
            )
        }.apply(p)
        p = SetTrackLocked(TimelineSequence.MAIN_AUDIO_TRACK_ID, true).apply(p)

        val decoded = ProjectCodec.decode(ProjectCodec.encode(p))
        assertEquals(p, decoded)
    }

    @Test
    fun largeMicrosecondValuesSurviveWithoutPrecisionLoss() {
        val huge = (1L shl 53) + 1 // not representable as Double
        val asset = Fixtures.video10s.copy(durationUs = huge + 10 * SEC)
        val p = Fixtures.emptyProject().copy(assets = listOf(asset), createdAtMs = huge)
        assertEquals(huge, ProjectCodec.decode(ProjectCodec.encode(p)).createdAtMs)
    }

    @Test
    fun missingOptionalFieldsUseDefaults() {
        val minimal = """
            {"format":"clippr.project","schemaVersion":1,"id":"x",
             "settings":{"width":1280,"height":720},
             "tracks":[{"id":"v","kind":"VIDEO","clips":[]}]}
        """.trimIndent()
        val p = ProjectCodec.decode(minimal)
        assertEquals("Untitled", p.name)
        assertEquals(FrameRate.FPS_30, p.settings.frameRate)
        assertEquals(1, p.sequence.tracks.size)
    }

    @Test
    fun rejectsFutureVersionsAndGarbage() {
        assertFailsWith<ProjectFormatException> {
            ProjectCodec.decode("""{"format":"clippr.project","schemaVersion":999,"id":"x","settings":{}}""")
        }
        assertFailsWith<ProjectFormatException> { ProjectCodec.decode("{not json") }
        assertFailsWith<ProjectFormatException> { ProjectCodec.decode("""{"format":"other"}""") }
        // A clip with an empty source range is corrupt: it is left out and reported, not loaded silently.
        val decoded = ProjectCodec.decodeDetailed(
            """{"format":"clippr.project","schemaVersion":1,"id":"x","settings":{"width":2,"height":2},
               "tracks":[{"id":"v","kind":"VIDEO","clips":[{"id":"c","assetId":"a","start":0,"in":5,"out":5}]}]}""",
        )
        assertEquals(listOf("c"), decoded.droppedClipIds)
        assertTrue(decoded.project.sequence.allClips.isEmpty())
    }

    @Test
    fun jsonParserHandlesEscapesAndNesting() {
        val v = Json.parse("""{"a":[1,-2.5e3,true,null,"é\"\\"],"b":{}}""") as JsonValue.Obj
        val arr = v.arr("a")
        assertEquals(JsonValue.Num("1"), arr[0])
        assertEquals(-2500.0, (arr[1] as JsonValue.Num).toDouble())
        assertEquals(JsonValue.Str("é\"\\"), arr[4])
        assertEquals(v, Json.parse(Json.write(v)))
    }
}
