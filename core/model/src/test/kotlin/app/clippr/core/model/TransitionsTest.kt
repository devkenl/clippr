package app.clippr.core.model

import app.clippr.core.model.Fixtures.A1
import app.clippr.core.model.Fixtures.V1
import app.clippr.core.model.edit.AddAssetClip
import app.clippr.core.model.edit.AddTrack
import app.clippr.core.model.edit.DeleteClips
import app.clippr.core.model.edit.MoveClip
import app.clippr.core.model.edit.Overlaps
import app.clippr.core.model.edit.SetTrackHidden
import app.clippr.core.model.edit.SetTransitionDuration
import app.clippr.core.model.edit.SplitClip
import app.clippr.core.model.edit.TrimClipEnd
import app.clippr.core.model.edit.TrimClipStart
import app.clippr.core.model.edit.UpdateClip
import app.clippr.core.model.render.RenderPlanner
import app.clippr.core.model.render.VideoSegment
import app.clippr.core.model.serialization.ProjectCodec
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransitionsTest {

    /** a: 0–10 s, b: 10–16 s, c: 16–26 s on the main video track. */
    private fun threeClips(): Project {
        var p = Fixtures.emptyProject()
        p = AddAssetClip(Fixtures.video10s.id, "a").apply(p)
        p = AddAssetClip(Fixtures.video6s.id, "b").apply(p)
        p = AddAssetClip(Fixtures.video10s.id, "c").apply(p)
        return p
    }

    private fun near(a: Float, b: Float, eps: Float = 1e-4f) = assertTrue(abs(a - b) <= eps, "expected $a, got $b")

    @Test
    fun droppingOntoThePreviousClipsTailMakesACrossfade() {
        // Drag b one second over the end of a.
        val p = MoveClip("b", V1, 9 * SEC).apply(threeClips())
        val clips = p.track(V1).clips
        assertEquals(listOf("a", "b", "c"), clips.map { it.id })
        assertEquals(9 * SEC, p.clip("b").timelineStartUs)
        assertEquals(SEC, Overlaps.overlapIn(clips, 1))
        assertEquals(emptyList(), ProjectIntegrity.problems(p))

        val plan = RenderPlanner.plan(p)
        assertEquals(2, plan.layers.size, "a and b play together, so the track uses two lanes")
        plan.layers.forEach { assertEquals(p.durationUs, it.segments.sumOf { s -> s.durationUs }) }
        val b = plan.layers.flatMap { it.segments }.filterIsInstance<VideoSegment.Media>().first { it.clipId == "b" }
        val a = plan.layers.flatMap { it.segments }.filterIsInstance<VideoSegment.Media>().first { it.clipId == "a" }
        val window = assertNotNull(b.transitionIn)
        assertEquals(9 * SEC, window.startUs)
        assertEquals(10 * SEC, window.endUs)
        assertEquals(b.transitionIn, a.transitionOut)
        // Sound crossfades over the same second.
        assertEquals(SEC, b.audio!!.fadeInUs)
        assertEquals(SEC, a.audio!!.fadeOutUs)
    }

    @Test
    fun overlapsLongerThanHalfAClipSnapInstead() {
        // b is 6 s long: more than 3 s of overlap is not a transition.
        val p = MoveClip("b", V1, 6 * SEC).apply(threeClips())
        assertTrue(Overlaps.isValid(p.track(V1).clips, allowOverlap = true))
        assertEquals(0L, Overlaps.overlapIn(p.track(V1).clips, p.track(V1).clips.indexOfFirst { it.id == "b" }))
    }

    @Test
    fun audioTracksNeverOverlap() {
        var p = Fixtures.emptyProject()
        p = AddAssetClip(Fixtures.music.id, "m1", A1).apply(p)
        p = AddAssetClip(Fixtures.music.id, "m2", A1).apply(p)
        p = MoveClip("m2", A1, 29 * SEC).apply(p)
        assertTrue(Overlaps.isValid(p.track(A1).clips, allowOverlap = false))
    }

    @Test
    fun trimmingIntoANeighbourMakesATransitionUpToHalf() {
        // b needs source before its in-point to extend over a: trim 4 s off its head, close the
        // gap, then drag its start 3 s back over a.
        var p = AddAssetClip(Fixtures.video10s.id, "a").apply(Fixtures.emptyProject())
        p = AddAssetClip(Fixtures.video10s.id, "b").apply(p)
        p = TrimClipStart("b", 14 * SEC).apply(p)
        p = MoveClip("b", V1, 10 * SEC).apply(p)
        p = TrimClipStart("b", 7 * SEC).apply(p)
        assertEquals(7 * SEC, p.clip("b").timelineStartUs)
        val clips = p.track(V1).clips
        val overlap = Overlaps.overlapIn(clips, 1)
        assertTrue(overlap in 1..p.clip("b").durationUs / 2, "overlap $overlap")
        assertEquals(emptyList(), ProjectIntegrity.problems(p))

        val extended = TrimClipEnd("a", 30 * SEC).apply(threeClips())
        // a cannot grow (source is 10 s), so nothing changes.
        assertEquals(threeClips(), extended)
    }

    @Test
    fun transitionLengthMovesTheClipAndEverythingAfterIt() {
        val p0 = threeClips()
        val p1 = SetTransitionDuration("b", SEC).apply(p0)
        assertEquals(9 * SEC, p1.clip("b").timelineStartUs)
        assertEquals(15 * SEC, p1.clip("c").timelineStartUs, "c keeps its place relative to b")
        // Longer than half of b (3 s) is clamped.
        val p2 = SetTransitionDuration("b", 10 * SEC).apply(p0)
        assertEquals(3 * SEC, Overlaps.overlapIn(p2.track(V1).clips, 1))
        // 0 removes it again.
        val p3 = SetTransitionDuration("b", 0).apply(p1)
        assertEquals(p0, p3)
    }

    @Test
    fun splittingInsideATransitionIsRefused() {
        val p = SetTransitionDuration("b", SEC).apply(threeClips())
        assertSame(p, SplitClip("b", 9 * SEC + 500_000, "b2").apply(p))
        val split = SplitClip("b", 12 * SEC, "b2").apply(p)
        assertEquals(emptyList(), ProjectIntegrity.problems(split))
        assertEquals(Transition.DEFAULT, split.clip("b2").transition)
    }

    @Test
    fun rippleDeleteKeepsTheNextTransition() {
        var p = SetTransitionDuration("b", SEC).apply(threeClips())
        p = SetTransitionDuration("c", SEC).apply(p)
        val deleted = DeleteClips(setOf("b"), ripple = true).apply(p)
        assertEquals(emptyList(), ProjectIntegrity.problems(deleted))
        // c now crossfades straight from a.
        assertEquals(9 * SEC, deleted.clip("c").timelineStartUs)
    }

    @Test
    fun crossfadeLooksTheSameWhicheverClipIsOnTop() {
        val outgoing = 0.2f
        val incoming = 0.9f
        for (p in listOf(0f, 0.25f, 0.5f, 0.8f, 1f)) {
            for (onTop in listOf(true, false)) {
                val inLook = TransitionMath.incoming(TransitionType.CROSSFADE, p, onTop)
                val outLook = TransitionMath.outgoing(TransitionType.CROSSFADE, p, onTop)
                // Composite over black: bottom first, then top with alpha blending.
                val (bottom, top) = if (onTop) (outgoing to outLook.alpha) to (incoming to inLook.alpha) else (incoming to inLook.alpha) to (outgoing to outLook.alpha)
                val result = top.first * top.second + bottom.first * bottom.second * (1 - top.second)
                near(outgoing * (1 - p) + incoming * p, result)
            }
        }
    }

    @Test
    fun slideUnderneathIsMaskedLikeSlideOnTop() {
        // Incoming slides in from the right. Where it has arrived must show the incoming clip.
        for (p in listOf(0.1f, 0.5f, 0.9f)) {
            val look = TransitionMath.incoming(TransitionType.SLIDE_LEFT, p, incomingOnTop = false)
            val leftEdgeU = ((look.offsetX - 1f) + 1f) / 2f // incoming's left edge in texture units
            val mask = TransitionMath.mask(TransitionType.SLIDE_LEFT, isIncoming = false, incomingOnTop = false)
            assertNotNull(mask)
            assertTrue(mask.invert)
            for (u in listOf(0.05f, 0.3f, 0.6f, 0.95f)) {
                val incomingCovers = u >= leftEdgeU
                val outgoingVisible = !TransitionMath.revealed(mask.shape, p, u, 0.5f)
                assertEquals(!incomingCovers, outgoingVisible, "p=$p u=$u")
            }
        }
        assertNull(TransitionMath.mask(TransitionType.SLIDE_LEFT, isIncoming = true, incomingOnTop = true))
        assertNull(TransitionMath.mask(TransitionType.CROSSFADE, isIncoming = true, incomingOnTop = true))
    }

    @Test
    fun layerLookCombinesTransitionAndClipOpacity() {
        var p = SetTransitionDuration("b", 2 * SEC).apply(threeClips())
        p = UpdateClip("b", "fade") { it.copy(transform = it.transform.copy(opacity = 0.5f)) }.apply(p)
        val plan = RenderPlanner.plan(p)
        val bLayer = plan.layers.first { layer -> layer.segments.any { it is VideoSegment.Media && it.clipId == "b" } }
        val b = bLayer.mediaAt(9 * SEC)!!
        assertEquals("b", b.clipId)
        val look = b.lookAt(9 * SEC)
        val w = b.transitionIn!!
        val expected = if (w.incomingOnTop) 0.5f * TransitionMath.progress(w.transition, w.startUs, w.endUs, 9 * SEC) else 0.5f
        near(expected, look.alpha)
        assertNull(bLayer.mediaAt(30 * SEC))
    }

    @Test
    fun newVideoTracksStackOnTopAndCanBeHidden() {
        var p = AddAssetClip(Fixtures.video10s.id, "base").apply(Fixtures.emptyProject())
        p = AddTrack(TrackKind.VIDEO, "v2").apply(p)
        p = AddAssetClip(Fixtures.photo.id, "logo", "v2", startUs = 0).apply(p)
        assertEquals(V1, p.sequence.mainVideoTrack!!.id)
        assertEquals(listOf("v2", V1), p.sequence.videoTracks.map { it.id })
        assertEquals("Video 2", p.sequence.track("v2")!!.name)
        val plan = RenderPlanner.plan(p)
        assertEquals(listOf("v2", V1), plan.layers.map { it.trackId }, "top layer first")

        val hidden = SetTrackHidden("v2", true).apply(p)
        assertEquals(listOf(V1), RenderPlanner.plan(hidden).layers.map { it.trackId })
        assertEquals(hidden, ProjectCodec.decode(ProjectCodec.encode(hidden)))
    }

    @Test
    fun hiddenVideoTrackStillPlaysItsSound() {
        var p = AddAssetClip(Fixtures.video10s.id, "base").apply(Fixtures.emptyProject())
        p = AddTrack(TrackKind.VIDEO, "v2").apply(p)
        p = AddAssetClip(Fixtures.video6s.id, "over", "v2", startUs = 0).apply(p)
        p = SetTrackHidden("v2", true).apply(p)
        val plan = RenderPlanner.plan(p)
        assertTrue(plan.audioLanes.any { lane -> lane.any { it is app.clippr.core.model.render.AudioSegment.Media && it.clipId == "over" } })
    }

    @Test
    fun transitionsAndChromaKeyRoundTrip() {
        var p = SetTransitionDuration("b", SEC).apply(threeClips())
        p = UpdateClip("b", "t") {
            it.copy(
                transition = Transition(TransitionType.IRIS, Easing.LINEAR),
                effects = it.effects.copy(chromaKey = ChromaKey(ChromaKey.BLUE, 0.3f, 0.2f, 0.7f)),
            )
        }.apply(p)
        assertEquals(p, ProjectCodec.decode(ProjectCodec.encode(p)))
        val unknown = ProjectCodec.encode(p).replace("\"iris\"", "\"hologram\"")
        assertEquals(TransitionType.CROSSFADE, ProjectCodec.decode(unknown).clip("b").transition.type)
    }

    @Test
    fun transitionTypeIdsAreUnique() {
        assertEquals(TransitionType.entries.size, TransitionType.entries.map { it.id }.toSet().size)
    }
}
