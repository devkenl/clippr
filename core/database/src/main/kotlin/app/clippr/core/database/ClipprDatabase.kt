package app.clippr.core.database

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Lightweight per-project row used by the Home screen. The full editing document lives in
 * [ProjectDocumentEntity] so that listing projects never loads timelines.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "modified_at_ms") val modifiedAtMs: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "fps_num") val fpsNumerator: Int,
    @ColumnInfo(name = "fps_den") val fpsDenominator: Int,
    @ColumnInfo(name = "duration_us") val durationUs: Long,
    @ColumnInfo(name = "clip_count") val clipCount: Int,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
)

/** The versioned JSON project document (see ProjectCodec in core:model). */
@Entity(
    tableName = "project_documents",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProjectDocumentEntity(
    @PrimaryKey @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    val json: String,
)

@Dao
abstract class ProjectDao {
    @Query("SELECT * FROM projects ORDER BY modified_at_ms DESC")
    abstract fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    abstract suspend fun project(id: String): ProjectEntity?

    @Query("SELECT * FROM project_documents WHERE project_id = :id")
    abstract suspend fun document(id: String): ProjectDocumentEntity?

    @Upsert
    abstract suspend fun upsertProject(entity: ProjectEntity)

    @Upsert
    abstract suspend fun upsertDocument(entity: ProjectDocumentEntity)

    /** Writes the summary row and the document atomically. */
    @Transaction
    open suspend fun save(project: ProjectEntity, document: ProjectDocumentEntity) {
        upsertProject(project)
        upsertDocument(document)
    }

    @Query("UPDATE projects SET thumbnail_path = :path WHERE id = :id")
    abstract suspend fun setThumbnail(id: String, path: String?)

    @Query("DELETE FROM projects WHERE id = :id")
    abstract suspend fun delete(id: String)
}

@Database(
    entities = [ProjectEntity::class, ProjectDocumentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ClipprDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        private const val NAME = "clippr.db"

        fun create(context: Context): ClipprDatabase =
            Room.databaseBuilder(context.applicationContext, ClipprDatabase::class.java, NAME)
                // No destructive fallback: user projects must never be silently discarded.
                // Every future schema change ships with an explicit Migration.
                .build()
    }
}
