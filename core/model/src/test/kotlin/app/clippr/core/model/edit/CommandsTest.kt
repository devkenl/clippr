package app.clippr.core.model.edit

import app.clippr.core.model.Fixtures
import app.clippr.core.model.Fixtures.A1
import app.clippr.core.model.Fixtures.T1
import app.clippr.core.model.Fixtures.V1
import app.clippr.core.model.Project
import app.clippr.core.model.ProjectIntegrity
import app.clippr.core.model.SEC
import app.clippr.core.model.TextStyle
import app.clippr.core.model.TrackKind
import app.clippr.core.model.clip
import app.clippr.core.model.track
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommandsTest {

    /** V1: c1 = 10s video [0,10), c2 = 6s video [10,16). */
    private fun twoClips(): Project {
        var p = Fixtures.emptyProject()
        p = AddAssetClip(Fixtures.video10s.id, "c1").apply(p)
        p = AddAssetClip(Fixtures.video6s.id, "c2").apply(p)
        return p
    }

    private fun Project.assertValid() {
        val problems = ProjectIntegrity.problems(this)
        assertTrue(problems.isEmpty(), problems.joinToString())
    }

    @Test
    fun addAssetClipAppendsToCompatibleTrack() {
        val p = twoClips()
        val v1 = p.track(V1)
        assertEquals(listOf("c1", "c2"), v1.clips.map { it.id })
        assertEquals(10 * SEC, p.clip("c2").timelineStartUs)
        assertEquals(16 * SEC, p.durationUs)
        val withMusic = AddAssetClip(Fixtures.music.id, "m1").apply(p)
        assertEquals(listOf("m1"), withMusic.track(A1).clips.map { it.id })
        withMusic.assertValid()
    }

    @Test
    fun addAssetClipRejectsIncompatibleTrack() {
        val p = Fixtures.emptyProject()
        assertSame(p, AddAssetClip(Fixtures.music.id, "m1", trackId = V1).apply(p))
        assertSame(p, AddAssetClip("missing", "x").apply(p))
    }

    @Test
    fun imageClipsGetDefaultDuration() {
        val p = AddAssetClip(Fixtures.photo.id, "img").apply(Fixtures.emptyProject())
        assertEquals(DEFAULT_STILL_DURATION_US, p.clip("img").durationUs)
    }

    @Test
    fun moveReordersClipsWithoutLosingAny() {
        val p = MoveClip("c2", V1, 2 * SEC).apply(twoClips()) // first half of c1
        val v1 = p.track(V1)
        assertEquals(listOf("c2", "c1"), v1.clips.map { it.id })
        assertEquals(0L, p.clip("c2").timelineStartUs)
        assertEquals(6 * SEC, p.clip("c1").timelineStartUs)
        p.assertValid()
    }

    @Test
    fun moveToGapLeavesEarlierClipsAlone() {
        val p = MoveClip("c2", V1, 20 * SEC).apply(twoClips())
        assertEquals(0L, p.clip("c1").timelineStartUs)
        assertEquals(20 * SEC, p.clip("c2").timelineStartUs)
    }

    @Test
    fun moveAcrossIncompatibleTrackIsRejected() {
        val p = twoClips()
        assertSame(p, MoveClip("c1", A1, 0).apply(p))
    }

    @Test
    fun trimStartAdvancesSourceInAndKeepsEnd() {
        val p = TrimClipStart("c1", 3 * SEC).apply(twoClips())
        val c1 = p.clip("c1")
        assertEquals(3 * SEC, c1.timelineStartUs)
        assertEquals(3 * SEC, c1.sourceStartUs)
        assertEquals(10 * SEC, c1.timelineEndUs)
        p.assertValid()
    }

    @Test
    fun trimStartCannotExtendBeforeSourceStartOrPreviousClip() {
        var p = TrimClipStart("c2", 13 * SEC).apply(twoClips()) // c2 now [13,16) reading [3,6)
        assertEquals(3 * SEC, p.clip("c2").sourceStartUs)
        // Try to extend c2 back to 5s: it may only reach the end of c1 (10s).
        p = TrimClipStart("c2", 5 * SEC).apply(p)
        assertEquals(10 * SEC, p.clip("c2").timelineStartUs)
        assertEquals(0L, p.clip("c2").sourceStartUs)
        // Move c2 far right; now extending is limited by source start 0 (source has only 6s).
        p = MoveClip("c2", V1, 30 * SEC).apply(p)
        p = TrimClipStart("c2", 0).apply(p)
        assertEquals(30 * SEC, p.clip("c2").timelineStartUs)
        p.assertValid()
    }

    @Test
    fun trimEndIsClampedToSourceAndNextClip() {
        var p = TrimClipEnd("c1", 4 * SEC).apply(twoClips())
        assertEquals(4 * SEC, p.clip("c1").timelineEndUs)
        assertEquals(4 * SEC, p.clip("c1").sourceEndUs)
        // Extending past the next clip start (10s) is clamped.
        p = TrimClipEnd("c1", 15 * SEC).apply(p)
        assertEquals(10 * SEC, p.clip("c1").timelineEndUs)
        // With room after it, the clip can only grow to the source duration.
        p = MoveClip("c2", V1, 40 * SEC).apply(p)
        p = TrimClipEnd("c1", 25 * SEC).apply(p)
        assertEquals(10 * SEC, p.clip("c1").timelineEndUs)
        p.assertValid()
    }

    @Test
    fun trimRespectsMinimumDuration() {
        val p = TrimClipEnd("c1", 1).apply(twoClips())
        assertEquals(MIN_CLIP_DURATION_US, p.clip("c1").durationUs)
    }

    @Test
    fun imagesCanBeTrimmedLongerThanDefault() {
        var p = AddAssetClip(Fixtures.photo.id, "img").apply(Fixtures.emptyProject())
        p = TrimClipEnd("img", 20 * SEC).apply(p)
        assertEquals(20 * SEC, p.clip("img").durationUs)
        p = TrimClipStart("img", 5 * SEC).apply(p)
        assertEquals(5 * SEC, p.clip("img").timelineStartUs)
        assertEquals(0L, p.clip("img").sourceStartUs)
        assertEquals(15 * SEC, p.clip("img").durationUs)
        p.assertValid()
    }

    @Test
    fun splitProducesContiguousHalvesWithCorrectSourceRanges() {
        var p = twoClips()
        p = UpdateClip("c1", "fades") { it.copy(audio = it.audio.copy(fadeInUs = SEC, fadeOutUs = SEC)) }.apply(p)
        p = SplitClip("c1", 4 * SEC, "c1b").apply(p)
        val left = p.clip("c1")
        val right = p.clip("c1b")
        assertEquals(0L to 4 * SEC, left.timelineStartUs to left.timelineEndUs)
        assertEquals(4 * SEC to 10 * SEC, right.timelineStartUs to right.timelineEndUs)
        assertEquals(4 * SEC, left.sourceEndUs)
        assertEquals(4 * SEC, right.sourceStartUs)
        assertEquals(SEC to 0L, left.audio.fadeInUs to left.audio.fadeOutUs)
        assertEquals(0L to SEC, right.audio.fadeInUs to right.audio.fadeOutUs)
        assertEquals(listOf("c1", "c1b", "c2"), p.track(V1).clips.map { it.id })
        p.assertValid()
    }

    @Test
    fun splitWithSpeedKeepsTimelineContiguous() {
        var p = SetClipSpeed("c1", 2f).apply(twoClips()) // c1 now 5s long
        assertEquals(5 * SEC, p.clip("c1").durationUs)
        p = SplitClip("c1", 2 * SEC, "c1b").apply(p)
        assertEquals(4 * SEC, p.clip("c1").sourceEndUs)
        assertEquals(p.clip("c1").timelineEndUs, p.clip("c1b").timelineStartUs)
        assertEquals(5 * SEC, p.clip("c1b").timelineEndUs)
        p.assertValid()
    }

    @Test
    fun splitAtEdgeIsRejected() {
        val p = twoClips()
        assertSame(p, SplitClip("c1", 0, "x").apply(p))
        assertSame(p, SplitClip("c1", 10 * SEC, "x").apply(p))
        assertSame(p, SplitClip("c1", 10 * SEC - 1, "x").apply(p))
    }

    @Test
    fun deleteLeavesGapAndRippleDeleteClosesIt() {
        val base = twoClips()
        val plain = DeleteClips(setOf("c1")).apply(base)
        assertEquals(10 * SEC, plain.clip("c2").timelineStartUs)
        val ripple = DeleteClips(setOf("c1"), ripple = true).apply(base)
        assertEquals(0L, ripple.clip("c2").timelineStartUs)
        assertNull(ripple.sequence.clip("c1"))
    }

    @Test
    fun speedIncreaseThatLengthensClipPushesNeighbours() {
        val p = SetClipSpeed("c1", 0.5f).apply(twoClips()) // c1 becomes 20s
        assertEquals(20 * SEC, p.clip("c1").durationUs)
        assertEquals(20 * SEC, p.clip("c2").timelineStartUs)
        p.assertValid()
    }

    @Test
    fun lockedTracksRejectEdits() {
        val p = SetTrackLocked(V1, true).apply(twoClips())
        assertSame(p, TrimClipStart("c1", SEC).apply(p))
        assertSame(p, SplitClip("c1", 5 * SEC, "x").apply(p))
        assertSame(p, DeleteClips(setOf("c1")).apply(p))
        assertSame(p, MoveClip("c1", V1, 30 * SEC).apply(p))
        assertSame(p, UpdateClip("c1", "x") { it.copy(audio = it.audio.copy(volume = 0.5f)) }.apply(p))
    }

    @Test
    fun updateClipIgnoresTimingChangesAndSanitizes() {
        val p = UpdateClip("c1", "x") {
            it.copy(timelineStartUs = 99 * SEC, transform = it.transform.copy(scale = 100f, opacity = 3f))
        }.apply(twoClips())
        val c1 = p.clip("c1")
        assertEquals(0L, c1.timelineStartUs)
        assertEquals(8f, c1.transform.scale)
        assertEquals(1f, c1.transform.opacity)
    }

    @Test
    fun textClipsLiveOnTextTracks() {
        var p = AddTextClip("t", TextStyle("Hello"), startUs = 2 * SEC).apply(Fixtures.emptyProject())
        assertEquals(listOf("t"), p.track(T1).clips.map { it.id })
        assertSame(p, MoveClip("t", V1, 0).apply(p))
        p = TrimClipEnd("t", 9 * SEC).apply(p)
        assertEquals(7 * SEC, p.clip("t").durationUs)
        p.assertValid()
    }

    @Test
    fun addTrackGroupsByKindAndRemoveTrackKeepsMainVideo() {
        var p = AddTrack(TrackKind.AUDIO, "a2").apply(Fixtures.emptyProject())
        assertEquals(listOf(T1, V1, A1, "a2"), p.sequence.tracks.map { it.id })
        assertEquals("Audio 2", p.track("a2").name)
        p = RemoveTrack("a2").apply(p)
        assertFalse(p.sequence.tracks.any { it.id == "a2" })
        assertSame(p, RemoveTrack(V1).apply(p))
    }

    @Test
    fun importIsIdempotentPerAssetId() {
        val p = Fixtures.emptyProject()
        assertSame(p, ImportAssets(listOf(Fixtures.music)).apply(p))
    }
}
