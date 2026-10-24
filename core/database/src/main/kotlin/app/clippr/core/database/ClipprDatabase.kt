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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    @Query("SELECT id FROM projects ORDER BY modified_at_ms DESC")
    abstract suspend fun allIds(): List<String>

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

/**
 * A finished export, listed under "Recent exports" on Home. Kept when its project is deleted, so
 * there is deliberately no foreign key to [ProjectEntity].
 */
@Entity(tableName = "exports")
data class ExportEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String?,
    @ColumnInfo(name = "project_name") val projectName: String,
    /** The MediaStore item in Movies/Clippr. */
    val uri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "fps_num") val fpsNumerator: Int,
    @ColumnInfo(name = "fps_den") val fpsDenominator: Int,
    @ColumnInfo(name = "duration_us") val durationUs: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
)

@Dao
abstract class ExportDao {
    @Query("SELECT * FROM exports ORDER BY created_at_ms DESC")
    abstract fun observeExports(): Flow<List<ExportEntity>>

    @Query("SELECT * FROM exports")
    abstract suspend fun all(): List<ExportEntity>

    @Upsert
    abstract suspend fun upsert(entity: ExportEntity)

    @Query("DELETE FROM exports WHERE id = :id")
    abstract suspend fun delete(id: String)
}

@Database(
    entities = [ProjectEntity::class, ProjectDocumentEntity::class, ExportEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ClipprDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun exportDao(): ExportDao

    companion object {
        private const val NAME = "clippr.db"

        /** 0.2.0: adds the export history table. Existing projects are untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exports` (" +
                        "`id` TEXT NOT NULL, `project_id` TEXT, `project_name` TEXT NOT NULL, " +
                        "`uri` TEXT NOT NULL, `display_name` TEXT NOT NULL, `width` INTEGER NOT NULL, " +
                        "`height` INTEGER NOT NULL, `fps_num` INTEGER NOT NULL, `fps_den` INTEGER NOT NULL, " +
                        "`duration_us` INTEGER NOT NULL, `size_bytes` INTEGER NOT NULL, " +
                        "`created_at_ms` INTEGER NOT NULL, `thumbnail_path` TEXT, PRIMARY KEY(`id`))",
                )
            }
        }

        fun create(context: Context): ClipprDatabase =
            Room.databaseBuilder(context.applicationContext, ClipprDatabase::class.java, NAME)
                // No destructive fallback: user projects must never be silently discarded.
                // Every schema change ships with an explicit Migration.
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
