package app.clippr.feature.export

import android.content.Context
import android.net.Uri
import app.clippr.core.media.VideoLibrary
import app.clippr.core.model.Project
import app.clippr.core.render.ExportEngine
import app.clippr.core.render.ExportSettings
import app.clippr.core.render.ExportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A finished export, handed to the app so it can appear under "Recent exports". */
data class ExportedVideo(
    val projectId: String,
    val projectName: String,
    val uri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
    val frameRate: app.clippr.core.model.time.FrameRate,
    val durationUs: Long,
    val sizeBytes: Long,
)

/** The overall export job as shown to the user, including saving to the gallery. */
sealed interface ExportJob {
    data object Idle : ExportJob
    data class Rendering(val projectId: String, val progress: Float?, val elapsedMs: Long) : ExportJob
    data class Saving(val projectId: String) : ExportJob
    data class Done(val projectId: String, val uri: Uri, val displayName: String, val sizeBytes: Long, val elapsedMs: Long) : ExportJob
    data class Failed(val projectId: String, val message: String) : ExportJob
    data class Cancelled(val projectId: String) : ExportJob
}

/**
 * App-scoped export orchestration: renders with [ExportEngine] into the cache directory, then
 * publishes the file to Movies/Clippr. Lives in the app container so an export keeps running if
 * the user navigates back to the editor. Must be used on the main thread.
 */
class ExportCoordinator(
    private val context: Context,
    private val engine: ExportEngine,
    private val library: VideoLibrary,
    private val appScope: CoroutineScope,
    /** Called after a successful export, e.g. to record it in the export history. */
    private val onExported: suspend (ExportedVideo) -> Unit = {},
) {
    private val _job = MutableStateFlow<ExportJob>(ExportJob.Idle)
    val job: StateFlow<ExportJob> = _job.asStateFlow()

    private var projectId: String? = null
    private var displayName: String = ""
    private var pending: ExportedVideo? = null

    init {
        appScope.launch {
            engine.state.collect { state ->
                val id = projectId ?: return@collect
                when (state) {
                    ExportState.Idle -> Unit
                    is ExportState.Running -> _job.value = ExportJob.Rendering(id, state.progress, state.elapsedMs)
                    is ExportState.Failed -> _job.value = ExportJob.Failed(id, state.message)
                    ExportState.Cancelled -> _job.value = ExportJob.Cancelled(id)
                    is ExportState.Completed -> {
                        _job.value = ExportJob.Saving(id)
                        try {
                            val uri = library.publish(state.file, displayName)
                            _job.value = ExportJob.Done(id, uri, displayName, state.sizeBytes, state.elapsedMs)
                            pending?.let { info ->
                                runCatching { onExported(info.copy(uri = uri, displayName = displayName, sizeBytes = state.sizeBytes)) }
                            }
                        } catch (e: Exception) {
                            _job.value = ExportJob.Failed(id, "Rendered, but saving to the gallery failed: ${e.message}")
                        } finally {
                            state.file.delete()
                        }
                    }
                }
            }
        }
    }

    val isBusy: Boolean get() = _job.value is ExportJob.Rendering || _job.value is ExportJob.Saving

    fun start(project: Project, settings: ExportSettings) {
        if (isBusy) return
        projectId = project.id
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val safeName = project.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Clippr" }.take(48)
        displayName = "$safeName-$stamp.mp4"
        val out = File(File(context.cacheDir, "exports"), displayName)
        val (w, h) = settings.outputSize(project.settings)
        pending = ExportedVideo(project.id, project.name, Uri.EMPTY, displayName, w, h, settings.frameRate, project.durationUs, 0L)
        _job.value = ExportJob.Rendering(project.id, null, 0)
        engine.start(project, settings, out)
    }

    fun cancel() = engine.cancel()

    /** Clears a finished job so the export screen shows settings again. */
    fun acknowledge() {
        if (!isBusy) {
            _job.value = ExportJob.Idle
            engine.reset()
        }
    }
}
