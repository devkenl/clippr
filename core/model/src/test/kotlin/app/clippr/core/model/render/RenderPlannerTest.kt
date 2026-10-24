package app.clippr.core.model.render

import app.clippr.core.model.Fixtures
import app.clippr.core.model.Fixtures.A1
import app.clippr.core.model.Fixtures.V1
import app.clippr.core.model.Project
import app.clippr.core.model.SEC
import app.clippr.core.model.TextStyle
import app.clippr.core.model.edit.AddAssetClip
import app.clippr.core.model.edit.AddTextClip
import app.clippr.core.model.edit.MoveClip
import app.clippr.core.model.edit.SetTrackMuted
import app.clippr.core.model.edit.TrimClipStart
import app.clippr.core.model.edit.UpdateClip
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderPlannerTest {

    private fun project(): Project {
        var p = Fixtures.emptyProject()
        p = AddAssetClip(Fixtures.video10s.id, "c1").apply(p)
        p = AddAssetClip(Fixtures.video6s.id, "c2").apply(p)
        p = MoveClip("c2", V1, 12 * SEC).apply(p) // gap [10,12)
        p = AddAssetClip(Fixtures.music.id, "m1", startUs = 2 * SEC).apply(p) // [2,32)
        return p
    }

    @Test
    fun videoLaneIsContiguousAndPaddedToTotalDuration() {
        val plan = RenderPlanner.plan(project())
        assertEquals(32 * SEC, plan.durationUs)
        assertEquals(plan.durationUs, plan.video.sumOf { it.durationUs })
        val kinds = plan.video.map { it::class.simpleName to it.durationUs }
        assertEquals(
            listOf(
                "Media" to 10 * SEC,
                "Gap" to 2 * SEC,
                "Media" to 6 * SEC,
                "Gap" to 14 * SEC,
            ),
            kinds,
        )
    }

    @Test
    fun trimmedClipCarriesSourceRange() {
        val p = TrimClipStart("c1", 3 * SEC).apply(project())
        val plan = RenderPlanner.plan(p)
        assertIs<VideoSegment.Gap>(plan.video[0])
        assertEquals(3 * SEC, plan.video[0].durationUs)
        val media = plan.video[1] as VideoSegment.Media
        assertEquals(3 * SEC to 10 * SEC, media.sourceStartUs to media.sourceEndUs)
        assertEquals(7 * SEC, media.durationUs)
    }

    @Test
    fun audioLanesStartWithLeadingGap() {
        val plan = RenderPlanner.plan(project())
        assertEquals(1, plan.audioLanes.size)
        val lane = plan.audioLanes.single()
        assertEquals(AudioSegment.Gap(2 * SEC), lane[0])
        assertEquals(30 * SEC, (lane[1] as AudioSegment.Media).durationUs)
    }

    @Test
    fun mutedTracksAndSilentClipsProduceNoAudio() {
        var p = SetTrackMuted(A1, true).apply(project())
        p = SetTrackMuted(V1, true).apply(p)
        val plan = RenderPlanner.plan(p)
        assertTrue(plan.audioLanes.isEmpty())
        assertNull((plan.video[0] as VideoSegment.Media).audio)

        val silent = UpdateClip("m1", "mute") { it.copy(audio = it.audio.copy(muted = true)) }.apply(project())
        assertTrue(RenderPlanner.plan(silent).audioLanes.isEmpty())
    }

    @Test
    fun videoWithoutAudioTrackHasNoMix() {
        val p = AddAssetClip(Fixtures.silentVideo.id, "s").apply(Fixtures.emptyProject())
        assertNull((RenderPlanner.plan(p).video[0] as VideoSegment.Media).audio)
    }

    @Test
    fun missingAssetsBecomeGapsWithoutShiftingTiming() {
        val p = project()
        val broken = p.copy(assets = p.assets.filter { it.id != Fixtures.video10s.id })
        val plan = RenderPlanner.plan(broken)
        assertEquals(VideoSegment.Gap(10 * SEC), plan.video[0])
        assertEquals(plan.durationUs, plan.video.sumOf { it.durationUs })
    }

    @Test
    fun fadesAreShrunkToFitClip() {
        val p = UpdateClip("c2", "fade") { it.copy(audio = it.audio.copy(fadeInUs = 5 * SEC, fadeOutUs = 5 * SEC)) }
            .apply(project())
        val mix = RenderPlanner.mixFor(p.sequence.clip("c2")!!)!!
        assertEquals(6 * SEC, mix.fadeInUs + mix.fadeOutUs)
    }

    @Test
    fun textOverlaysAreCollected() {
        val p = AddTextClip("t1", TextStyle("Title"), startUs = SEC).apply(project())
        val plan = RenderPlanner.plan(p)
        assertEquals(listOf("t1"), plan.texts.map { it.clipId })
        assertEquals(SEC, plan.texts.single().startUs)
    }

    @Test
    fun emptyProjectProducesEmptyPlan() {
        val plan = RenderPlanner.plan(Fixtures.emptyProject())
        assertTrue(plan.isEmpty)
        assertTrue(plan.video.isEmpty())
    }
}
