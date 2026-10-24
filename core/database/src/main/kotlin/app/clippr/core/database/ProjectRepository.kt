package app.clippr.core.database

import app.clippr.core.model.Project
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.TimelineSequence
import app.clippr.core.model.serialization.ProjectCodec
import app.clippr.core.model.serialization.ProjectFormatException
import app.clippr.core.model.time.FrameRate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/** What the Home screen shows for a project, without loading its timeline. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val modifiedAtMs: Long,
    val width: Int,
    val height: Int,
    val frameRate: FrameRate,
    val durationUs: Long,
    val clipCount: Int,
    val thumbnailPath: String?,
)

/** Result of opening a project document. */
sealed interface LoadResult {
    data class Loaded(val project: Project) : LoadResult
    data object NotFound : LoadResult

    /** The document exists but could not be read. It is left untouched in the database. */
    data class Unreadable(val reason: String) : LoadResult
}

/** Persists projects locally. All work runs on [io]; callers may use any dispatcher. */
class ProjectRepository(
    private val dao: ProjectDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observeProjects(): Flow<List<ProjectSummary>> =
        dao.observeProjects().map { rows -> rows.map { it.toSummary() } }

    suspend fun create(name: String, settings: ProjectSettings): Project = withContext(io) {
        val now = clock()
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Untitled project" },
            settings = settings,
            sequence = TimelineSequence.default(),
            createdAtMs = now,
            modifiedAtMs = now,
        )
        writeProject(project)
        project
    }

    suspend fun load(id: String): LoadResult = withContext(io) {
        val doc = dao.document(id) ?: return@withContext LoadResult.NotFound
        try {
            LoadResult.Loaded(ProjectCodec.decode(doc.json))
        } catch (e: ProjectFormatException) {
            LoadResult.Unreadable(e.message ?: "Unknown format error")
        }
    }

    /** Saves [project], stamping it with the current time. Returns the saved instance. */
    suspend fun save(project: Project): Project = withContext(io) {
        val stamped = project.copy(modifiedAtMs = clock())
        writeProject(stamped)
        stamped
    }

    suspend fun rename(id: String, name: String) = withContext(io) {
        val loaded = (load(id) as? LoadResult.Loaded)?.project ?: return@withContext
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) writeProject(loaded.copy(name = trimmed, modifiedAtMs = clock()))
    }

    suspend fun duplicate(id: String): Project? = withContext(io) {
        val source = (load(id) as? LoadResult.Loaded)?.project ?: return@withContext null
        val now = clock()
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} copy",
            createdAtMs = now,
            modifiedAtMs = now,
        )
        writeProject(copy)
        dao.project(id)?.thumbnailPath?.let { dao.setThumbnail(copy.id, it) }
        copy
    }

    suspend fun delete(id: String) = withContext(io) { dao.delete(id) }

    suspend fun setThumbnail(id: String, path: String?) = withContext(io) { dao.setThumbnail(id, path) }

    private suspend fun writeProject(project: Project) {
        val existingThumb = dao.project(project.id)?.thumbnailPath
        dao.save(
            ProjectEntity(
                id = project.id,
                name = project.name,
                createdAtMs = project.createdAtMs,
                modifiedAtMs = project.modifiedAtMs,
                width = project.settings.width,
                height = project.settings.height,
                fpsNumerator = project.settings.frameRate.numerator,
                fpsDenominator = project.settings.frameRate.denominator,
                durationUs = project.durationUs,
                clipCount = project.sequence.allClips.size,
                thumbnailPath = existingThumb,
            ),
            ProjectDocumentEntity(project.id, ProjectCodec.SCHEMA_VERSION, ProjectCodec.encode(project)),
        )
    }

    private fun ProjectEntity.toSummary() = ProjectSummary(
        id = id,
        name = name,
        createdAtMs = createdAtMs,
        modifiedAtMs = modifiedAtMs,
        width = width,
        height = height,
        frameRate = FrameRate(fpsNumerator.coerceAtLeast(1), fpsDenominator.coerceAtLeast(1)),
        durationUs = durationUs,
        clipCount = clipCount,
        thumbnailPath = thumbnailPath,
    )
}
