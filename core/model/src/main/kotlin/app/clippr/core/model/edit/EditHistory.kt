package app.clippr.core.model.edit

import app.clippr.core.model.Project

/**
 * Linear undo/redo history built on immutable project snapshots.
 *
 * Each executed [EditCommand] records the project before and after it ran. Because projects are
 * immutable values that share unchanged structure, a snapshot costs little more than the parts
 * that changed, and undo can never drift from the true previous state the way inverse commands
 * can.
 *
 * Not thread-safe: use it from a single thread (the editor's state owner).
 */
class EditHistory(initial: Project, private val limit: Int = DEFAULT_LIMIT) {

    data class Entry(val label: String, val before: Project, val after: Project)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    var current: Project = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    /** Runs [command]. Returns false, recording nothing, if it did not change the project. */
    fun execute(command: EditCommand): Boolean {
        val before = current
        val after = command.apply(before)
        if (after == before) return false
        undoStack.addLast(Entry(command.label, before, after))
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        current = after
        return true
    }

    /** Reverts the last edit and returns its label, or null if there was nothing to undo. */
    fun undo(): String? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(entry)
        current = entry.before
        return entry.label
    }

    /** Re-applies the last undone edit and returns its label, or null if there was none. */
    fun redo(): String? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(entry)
        current = entry.after
        return entry.label
    }

    /**
     * Replaces the current project without recording an undo step. Used for changes that are not
     * user edits, such as refreshed asset metadata. History entries are kept.
     */
    fun replaceCurrent(project: Project) {
        current = project
    }

    /** Starts a fresh history at [project]. */
    fun reset(project: Project) {
        undoStack.clear()
        redoStack.clear()
        current = project
    }

    companion object {
        const val DEFAULT_LIMIT = 200
    }
}
