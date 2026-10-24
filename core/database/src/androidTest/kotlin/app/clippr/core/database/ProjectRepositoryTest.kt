package app.clippr.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.clippr.core.model.AspectRatioPreset
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.edit.AddAssetClip
import app.clippr.core.model.edit.ImportAssets
import app.clippr.core.model.edit.SplitClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class ProjectRepositoryTest {

    private lateinit var db: ClipprDatabase
    private lateinit var repo: ProjectRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ClipprDatabase::class.java).build()
        repo = ProjectRepository(db.projectDao(), Dispatchers.IO)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun editsSurviveSaveAndReload() = runTest {
        val created = repo.create("Trip", ProjectSettings.create(AspectRatioPreset.LANDSCAPE_16_9))
        val asset = MediaAsset("a", "content://test/video", "v.mp4", MediaType.VIDEO, durationUs = 8_000_000, width = 1920, height = 1080)
        var p = ImportAssets(listOf(asset)).apply(created)
        p = AddAssetClip("a", "c1").apply(p)
        p = SplitClip("c1", 3_000_000, "c2").apply(p)
        repo.save(p)

        val loaded = repo.load(created.id)
        assertIs<LoadResult.Loaded>(loaded)
        assertEquals(p.sequence, loaded.project.sequence)
        assertEquals(p.assets, loaded.project.assets)

        val summary = repo.observeProjects().first().single()
        assertEquals(2, summary.clipCount)
        assertEquals(8_000_000, summary.durationUs)
    }

    @Test
    fun duplicateAndDelete() = runTest {
        val created = repo.create("Original", ProjectSettings.create(AspectRatioPreset.SQUARE_1_1))
        val copy = repo.duplicate(created.id)!!
        assertEquals("Original copy", copy.name)
        assertEquals(2, repo.observeProjects().first().size)
        repo.delete(created.id)
        assertEquals(listOf(copy.id), repo.observeProjects().first().map { it.id })
        assertIs<LoadResult.NotFound>(repo.load(created.id))
    }
}
