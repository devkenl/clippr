package app.clippr.core.render

import android.content.Context
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.clippr.core.model.Project
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.render.RenderPlanner
import app.clippr.core.model.time.FrameRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class ExportQuality(val label: String, val bitsPerPixel: Float) {
    STANDARD("Standard", 0.10f),
    HIGH("High", 0.16f),
}

data class ExportSettings(
    /** Short side of the output in pixels: 720, 1080 or 2160. */
    val shortSide: Int,
    val frameRate: FrameRate,
    val quality: ExportQuality = ExportQuality.STANDARD,
) {
    fun outputSize(project: ProjectSettings): Pair<Int, Int> = OutputSize.forExport(project, shortSide)

    fun videoBitrate(project: ProjectSettings): Int {
        val (w, h) = outputSize(project)
        return (w.toLong() * h * frameRate.fps * quality.bitsPerPixel).toLong().coerceIn(1_000_000L, 120_000_000L).toInt()
    }
}

sealed interface ExportState {
    data object Idle : ExportState

    /** [progress] is in 0..1, or null while Media3 cannot estimate it yet. */
    data class Running(val progress: Float?, val elapsedMs: Long) : ExportState
    data class Completed(val file: File, val sizeBytes: Long, val elapsedMs: Long) : ExportState
    data class Failed(val message: String) : ExportState
    data object Cancelled : ExportState
}

/**
 * Renders a project to an MP4 file (H.264 video, AAC audio) with Media3 [Transformer], using the
 * same [CompositionFactory] as the preview. Progress comes from Transformer itself; nothing is
 * simulated. Must be used on the main thread.
 */
@OptIn(UnstableApi::class)
@MainThread
class ExportEngine(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transformer: Transformer? = null
    private var progressJob: Job? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value is ExportState.Running

    fun start(project: Project, settings: ExportSettings, output: File) {
        if (isRunning) return
        val (w, h) = settings.outputSize(project.settings)
        val plan = RenderPlanner.plan(
            project.copy(settings = project.settings.copy(frameRate = settings.frameRate)),
            w,
            h,
        )
        val composition: Composition = CompositionFactory.create(plan, w, h, RenderResources.get(appContext)) ?: run {
            _state.value = ExportState.Failed("The timeline is empty.")
            return
        }

        output.parentFile?.mkdirs()
        output.delete()
        outputFile = output
        startedAt = SystemClock.elapsedRealtime()

        val encoderFactory = DefaultEncoderFactory.Builder(appContext)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(settings.videoBitrate(project.settings)).build(),
            )
            .setEnableFallback(true)
            .build()

        val t = Transformer.Builder(appContext)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopProgress()
                    transformer = null
                    val file = outputFile
                    _state.value = if (file != null && file.exists()) {
                        ExportState.Completed(file, file.length(), elapsed())
                    } else {
                        ExportState.Failed("The exported file is missing.")
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    stopProgress()
                    transformer = null
                    outputFile?.delete()
                    _state.value = ExportState.Failed(describe(exportException))
                }
            })
            .build()

        transformer = t
        _state.value = ExportState.Running(progress = null, elapsedMs = 0)
        try {
            t.start(composition, output.absolutePath)
        } catch (e: RuntimeException) {
            transformer = null
            _state.value = ExportState.Failed(e.message ?: "Export could not start.")
            return
        }
        startProgress(t)
    }

    fun cancel() {
        val t = transformer ?: return
        t.cancel()
        transformer = null
        stopProgress()
        outputFile?.delete()
        _state.value = ExportState.Cancelled
    }

    /** Returns to [ExportState.Idle] after a finished, failed or cancelled export. */
    fun reset() {
        if (!isRunning) _state.value = ExportState.Idle
    }

    private fun startProgress(t: Transformer) {
        val holder = ProgressHolder()
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && transformer === t) {
                val progress = when (t.getProgress(holder)) {
                    Transformer.PROGRESS_STATE_AVAILABLE -> holder.progress / 100f
                    else -> null
                }
                if (_state.value is ExportState.Running) {
                    _state.value = ExportState.Running(progress, elapsed())
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopProgress() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun elapsed() = SystemClock.elapsedRealtime() - startedAt

    private fun describe(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        -> "This device's encoder does not support these export settings. Try a lower resolution or frame rate."
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        ExportException.ERROR_CODE_IO_NO_PERMISSION,
        -> "A media file used in this project can no longer be opened."
        else -> e.message ?: "Export failed (${e.errorCodeName})."
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
