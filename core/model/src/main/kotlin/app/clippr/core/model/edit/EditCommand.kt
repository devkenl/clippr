package app.clippr.core.model.edit

import app.clippr.core.model.Project

/**
 * A single user-visible edit. Commands are pure functions from one immutable [Project] to the
 * next. A command that cannot apply (unknown clip, locked track, out-of-range value) returns the
 * project unchanged, and [EditHistory] records nothing for it.
 */
interface EditCommand {
    /** Short label shown in undo/redo affordances, e.g. "Split clip". */
    val label: String

    fun apply(project: Project): Project
}

/** Minimum timeline duration any clip may be trimmed down to, unless a frame is longer. */
const val MIN_CLIP_DURATION_US: Long = 100_000L

/** Minimum clip duration for a project: one frame, but never below [MIN_CLIP_DURATION_US]. */
fun Project.minClipDurationUs(): Long = maxOf(MIN_CLIP_DURATION_US, settings.frameRate.frameDurationUs)
