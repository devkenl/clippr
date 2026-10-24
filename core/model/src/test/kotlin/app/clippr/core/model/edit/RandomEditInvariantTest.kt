package app.clippr.core.model.edit

import app.clippr.core.model.Animation
import app.clippr.core.model.Fixtures
import app.clippr.core.model.Project
import app.clippr.core.model.ProjectIntegrity
import app.clippr.core.model.SEC
import app.clippr.core.model.TextStyle
import app.clippr.core.model.TrackKind
import app.clippr.core.model.render.RenderPlanner
import app.clippr.core.model.serialization.ProjectCodec
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Applies thousands of random edits and checks after every step that:
 * - track invariants hold (sorted, non-overlapping, valid source ranges),
 * - the render plan exactly covers the timeline,
 * - the project survives a save/load round trip,
 * - undoing everything returns to the original project.
 */
class RandomEditInvariantTest {

    @Test
    fun randomEditsPreserveInvariants() {
        for (seed in 1..40) runSession(Random(seed), steps = 150)
    }

    private fun runSession(rnd: Random, steps: Int) {
        val initial = Fixtures.emptyProject()
        val history = EditHistory(initial, limit = Int.MAX_VALUE)
        var nextId = 0
        fun id() = "x${nextId++}"
        val assets = initial.assets

        repeat(steps) { step ->
            val p = history.current
            val clips = p.sequence.allClips
            val anyClip = clips.randomOrNull(rnd)
            val t = rnd.nextLong(0, 40 * SEC)
            val command: EditCommand? = when (rnd.nextInt(13)) {
                0, 1 -> AddAssetClip(assets.random(rnd).id, id(), startUs = if (rnd.nextBoolean()) t else null)
                2 -> anyClip?.let { c ->
                    val target = p.sequence.tracks.random(rnd)
                    MoveClip(c.id, target.id, t)
                }
                3 -> anyClip?.let { TrimClipStart(it.id, it.timelineStartUs + rnd.nextLong(-5 * SEC, 5 * SEC)) }
                4 -> anyClip?.let { TrimClipEnd(it.id, it.timelineEndUs + rnd.nextLong(-5 * SEC, 5 * SEC)) }
                5 -> anyClip?.let { SplitClip(it.id, it.timelineStartUs + rnd.nextLong(0, it.durationUs + 1), id()) }
                6 -> anyClip?.let { DeleteClips(setOf(it.id), ripple = rnd.nextBoolean()) }
                7 -> anyClip?.let { SetClipSpeed(it.id, listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f).random(rnd)) }
                8 -> AddTextClip(id(), TextStyle("T$step"), startUs = t, durationUs = rnd.nextLong(1, 5 * SEC))
                9 -> anyClip?.let { c ->
                    UpdateClip(c.id, "random") {
                        it.copy(
                            transform = it.transform.copy(scale = rnd.nextFloat() * 3f, rotationDegrees = rnd.nextFloat() * 720f),
                            audio = it.audio.copy(volume = rnd.nextFloat(), fadeInUs = rnd.nextLong(0, 3 * SEC)),
                        )
                    }
                }
                11 -> anyClip?.let { c ->
                    if (rnd.nextBoolean()) {
                        DuplicateClip(c.id, id())
                    } else {
                        UpdateClip(c.id, "keyframe") {
                            Animation.editTransformAt(
                                Animation.addTransformKeyframe(it, it.sourceStartUs + rnd.nextLong(0, it.sourceDurationUs), 10_000),
                                it.sourceStartUs,
                                10_000,
                            ) { t -> t.copy(scale = 0.5f + rnd.nextFloat()) }
                        }
                    }
                }
                10 -> if (rnd.nextBoolean()) AddTrack(TrackKind.AUDIO, id()) else {
                    p.sequence.tracks.random(rnd).let { SetTrackLocked(it.id, rnd.nextInt(4) == 0) }
                }
                else -> if (history.canUndo && rnd.nextInt(3) == 0) {
                    history.undo(); null
                } else {
                    null
                }
            }
            if (command != null) history.execute(command)
            check(history.current, "seed step $step after ${command?.label}")
        }

        while (history.canUndo) history.undo()
        assertEquals(initial, history.current)
    }

    private fun check(p: Project, context: String) {
        val problems = ProjectIntegrity.problems(p)
        assertTrue(problems.isEmpty(), "$context: $problems")
        val plan = RenderPlanner.plan(p)
        assertEquals(p.durationUs, plan.video.sumOf { it.durationUs }, context)
        for (lane in plan.audioLanes) {
            assertTrue(lane.sumOf { it.durationUs } <= plan.durationUs, "$context: audio lane longer than timeline")
        }
        assertEquals(p, ProjectCodec.decode(ProjectCodec.encode(p)), "$context: codec round trip")
    }
}
