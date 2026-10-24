package app.clippr.core.model.edit

import app.clippr.core.model.Fixtures
import app.clippr.core.model.SEC
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditHistoryTest {

    @Test
    fun undoAndRedoRestoreExactSnapshots() {
        val initial = Fixtures.emptyProject()
        val h = EditHistory(initial)
        assertTrue(h.execute(AddAssetClip(Fixtures.video10s.id, "c1")))
        val afterAdd = h.current
        assertTrue(h.execute(SplitClip("c1", 4 * SEC, "c2")))
        val afterSplit = h.current

        assertEquals("Split clip", h.undo())
        assertEquals(afterAdd, h.current)
        assertEquals("Add clip", h.undo())
        assertEquals(initial, h.current)
        assertNull(h.undo())

        assertEquals("Add clip", h.redo())
        assertEquals("Split clip", h.redo())
        assertEquals(afterSplit, h.current)
        assertFalse(h.canRedo)
    }

    @Test
    fun noOpCommandsAreNotRecorded() {
        val h = EditHistory(Fixtures.emptyProject())
        assertFalse(h.execute(SplitClip("nope", SEC, "x")))
        assertFalse(h.canUndo)
    }

    @Test
    fun newEditClearsRedo() {
        val h = EditHistory(Fixtures.emptyProject())
        h.execute(AddAssetClip(Fixtures.video10s.id, "c1"))
        h.undo()
        assertTrue(h.canRedo)
        h.execute(AddAssetClip(Fixtures.video6s.id, "c2"))
        assertFalse(h.canRedo)
    }

    @Test
    fun historyIsBoundedByLimit() {
        val h = EditHistory(Fixtures.emptyProject(), limit = 3)
        repeat(5) { i -> h.execute(RenameProject("Name $i")) }
        var undone = 0
        while (h.undo() != null) undone++
        assertEquals(3, undone)
        assertEquals("Name 1", h.current.name)
    }
}
