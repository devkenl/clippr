package app.clippr.core.model

import app.clippr.core.model.serialization.ProjectCodec
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplatesTest {

    private fun idFactory(): (String) -> String {
        var n = 0
        return { prefix -> "$prefix-${n++}" }
    }

    @Test
    fun everyTemplateProducesAValidProject() {
        for (t in ProjectTemplates.all) {
            val empty = Project("p", t.name, t.settings)
            val p = t.applyTo(empty, idFactory())
            assertEquals(emptyList(), ProjectIntegrity.problems(p), t.id)
            assertEquals(t.settings, p.settings)
            assertEquals(t.content.size, p.sequence.allClips.size, "${t.id}: every title is placed")
            assertTrue(p.sequence.allClips.all { it.isText }, t.id)
            assertEquals(p, ProjectCodec.decode(ProjectCodec.encode(p)), "${t.id} round-trips")
        }
    }

    @Test
    fun templateIdsAreUniqueAndFontsAreBundled() {
        assertEquals(ProjectTemplates.all.size, ProjectTemplates.all.map { it.id }.toSet().size)
        val fonts = FontIds.BUILT_IN.map { it.first }.toSet()
        for (t in ProjectTemplates.all) {
            assertTrue(t.cardFontId in fonts, "${t.id} card font")
            t.content.forEach { assertTrue(it.style.fontId in fonts, "${t.id} uses ${it.style.fontId}") }
        }
    }

    @Test
    fun simultaneousTitlesGetTheirOwnTrack() {
        val t = ProjectTemplates.byId("vlog_intro")!!
        val p = t.applyTo(Project("p", "x", t.settings), idFactory())
        assertEquals(2, p.sequence.tracks.count { it.kind == TrackKind.TEXT })
        val pop = p.sequence.allClips.first { it.text?.text == "MY WEEKEND" }
        assertEquals(2, pop.keyframes.size)
    }
}
