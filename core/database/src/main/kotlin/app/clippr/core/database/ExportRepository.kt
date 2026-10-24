package app.clippr.core.database

import app.clippr.core.model.time.FrameRate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/** A finished export as shown under "Recent exports". */
data class ExportRecord(
    val id: String,
    val projectId: String?,
    val projectName: String,
    val uri: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val frameRate: FrameRate,
    val durationUs: Long,
    val sizeBytes: Long,
    val createdAtMs: Long,
    val thumbnailPath: String?,
)

/** Local history of exports. Removing an entry never deletes the video itself. */
class ExportRepository(
    private val dao: ExportDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeExports(): Flow<List<ExportRecord>> = dao.observeExports().map { rows -> rows.map { it.toRecord() } }

    suspend fun all(): List<ExportRecord> = withContext(io) { dao.all().map { it.toRecord() } }

    suspend fun add(
        projectId: String?,
        projectName: String,
        uri: String,
        displayName: String,
        width: Int,
        height: Int,
        frameRate: FrameRate,
        durationUs: Long,
        sizeBytes: Long,
        thumbnailPath: String?,
    ): ExportRecord = withContext(io) {
        val entity = ExportEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            projectName = projectName,
            uri = uri,
            displayName = displayName,
            width = width,
            height = height,
            fpsNumerator = frameRate.numerator,
            fpsDenominator = frameRate.denominator,
            durationUs = durationUs,
            sizeBytes = sizeBytes,
            createdAtMs = clock(),
            thumbnailPath = thumbnailPath,
        )
        dao.upsert(entity)
        entity.toRecord()
    }

    suspend fun remove(id: String) = withContext(io) { dao.delete(id) }

    private fun ExportEntity.toRecord() = ExportRecord(
        id = id,
        projectId = projectId,
        projectName = projectName,
        uri = uri,
        displayName = displayName,
        width = width,
        height = height,
        frameRate = FrameRate(fpsNumerator.coerceAtLeast(1), fpsDenominator.coerceAtLeast(1)),
        durationUs = durationUs,
        sizeBytes = sizeBytes,
        createdAtMs = createdAtMs,
        thumbnailPath = thumbnailPath,
    )
}
