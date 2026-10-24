package app.clippr.feature.export

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.clippr.core.database.LoadResult
import app.clippr.core.database.ProjectRepository
import app.clippr.core.media.VideoLibrary
import app.clippr.core.model.Project
import app.clippr.core.model.time.FrameRate
import app.clippr.core.model.time.Timecode
import app.clippr.core.render.EncoderSupport
import app.clippr.core.render.ExportQuality
import app.clippr.core.render.ExportSettings
import app.clippr.core.ui.components.PrimaryButton
import app.clippr.core.ui.components.ProgressRing
import app.clippr.core.ui.components.SecondaryButton
import app.clippr.core.ui.components.SegmentedChoice
import app.clippr.core.ui.components.ToolButton
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.TimecodeStyle
import app.clippr.core.ui.theme.palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class ExportUiState(
    val project: Project? = null,
    val error: String? = null,
    val shortSide: Int = 1080,
    val frameRate: FrameRate = FrameRate.FPS_30,
    val quality: ExportQuality = ExportQuality.STANDARD,
) {
    val settings: ExportSettings get() = ExportSettings(shortSide, frameRate, quality)

    /** Rough output size: video bitrate plus 128 kbit/s AAC. */
    val estimatedBytes: Long
        get() {
            val p = project ?: return 0
            val bits = (settings.videoBitrate(p.settings) + 128_000L) * (p.durationUs / 1_000_000.0)
            return (bits / 8).toLong()
        }
}

class ExportViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    val coordinator: ExportCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            when (val r = repository.load(projectId)) {
                is LoadResult.Loaded -> {
                    val p = r.project
                    val projectShort = minOf(p.settings.width, p.settings.height)
                    val side = ExportResolutions.firstOrNull { it == projectShort } ?: 1080
                    val fps = FrameRate.COMMON.firstOrNull { it == p.settings.frameRate } ?: FrameRate.FPS_30
                    _state.update { it.copy(project = p, shortSide = side, frameRate = fps) }
                    ensureSupported()
                }
                LoadResult.NotFound -> _state.update { it.copy(error = "This project no longer exists.") }
                is LoadResult.Unreadable -> _state.update { it.copy(error = "This project could not be read (${r.reason}).") }
            }
        }
    }

    fun isSupported(shortSide: Int, fps: FrameRate): Boolean {
        val p = _state.value.project ?: return false
        val (w, h) = ExportSettings(shortSide, fps).outputSize(p.settings)
        return EncoderSupport.isSupported(w, h, fps.fps.toInt())
    }

    fun setResolution(side: Int) {
        _state.update { it.copy(shortSide = side) }
        ensureSupported()
    }

    fun setFrameRate(fps: FrameRate) = _state.update { it.copy(frameRate = fps) }

    fun setQuality(q: ExportQuality) = _state.update { it.copy(quality = q) }

    /** Falls back to the highest supported frame rate for the chosen size. */
    private fun ensureSupported() {
        val s = _state.value
        if (!isSupported(s.shortSide, s.frameRate)) {
            FrameRate.COMMON.reversed().firstOrNull { isSupported(s.shortSide, it) }?.let { fr -> _state.update { it.copy(frameRate = fr) } }
        }
    }

    fun export() {
        val p = _state.value.project ?: return
        coordinator.start(p, _state.value.settings)
    }

    companion object {
        val ExportResolutions = listOf(720, 1080, 2160)
    }
}

@Composable
fun ExportScreen(viewModel: ExportViewModel, library: VideoLibrary, onBack: () -> Unit) {
    val c = palette
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val job by viewModel.coordinator.job.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val busy = job is ExportJob.Rendering || job is ExportJob.Saving
    LaunchedEffect(busy) { view.keepScreenOn = busy }
    BackHandler {
        if (!busy) viewModel.coordinator.acknowledge()
        onBack()
    }

    Column(Modifier.fillMaxSize().background(c.background).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(ClipprIcons.Back, "Back to editor", {
                if (!busy) viewModel.coordinator.acknowledge()
                onBack()
            })
            Text("Export", style = MaterialTheme.typography.titleLarge, color = c.textPrimary, modifier = Modifier.padding(start = Spacing.sm))
        }
        val project = ui.project
        when {
            ui.error != null -> Message(ui.error!!)
            project == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    ProjectCard(project)
                    when (val j = job) {
                        is ExportJob.Rendering -> if (j.projectId == project.id) Progress(j.progress, j.elapsedMs, viewModel.coordinator::cancel) else Busy()
                        is ExportJob.Saving -> Progress(1f, null, null, label = "Saving to gallery…")
                        is ExportJob.Done -> if (j.projectId == project.id) {
                            Done(
                                j,
                                onShare = { context.startActivity(library.shareIntent(j.uri, "Share video")) },
                                onOpen = { runCatching { context.startActivity(library.viewIntent(j.uri)) } },
                                onAgain = viewModel.coordinator::acknowledge,
                            )
                        } else {
                            Settings(ui, viewModel)
                        }
                        is ExportJob.Failed -> if (j.projectId == project.id) {
                            Failure(j.message, onRetry = viewModel.coordinator::acknowledge)
                        } else {
                            Settings(ui, viewModel)
                        }
                        is ExportJob.Cancelled -> {
                            LaunchedEffect(j) { viewModel.coordinator.acknowledge() }
                            Settings(ui, viewModel)
                        }
                        ExportJob.Idle -> Settings(ui, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: Project) {
    val c = palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .padding(Spacing.lg),
    ) {
        Text(project.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            "${Timecode.formatDuration(project.durationUs)} · ${project.sequence.allClips.size} clips · ${project.settings.width}×${project.settings.height}",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
    }
}

@Composable
private fun Settings(ui: ExportUiState, vm: ExportViewModel) {
    val c = palette
    val project = ui.project ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Label("Resolution")
        SegmentedChoice(
            options = ExportViewModel.ExportResolutions,
            selected = ui.shortSide,
            label = { if (it == 2160) "4K" else "${it}p" },
            onSelect = vm::setResolution,
            enabled = { side -> FrameRate.COMMON.any { vm.isSupported(side, it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        val (w, h) = ui.settings.outputSize(project.settings)
        Text("$w × $h", style = TimecodeStyle, color = c.textTertiary)

        Label("Frame rate")
        SegmentedChoice(
            options = FrameRate.COMMON,
            selected = ui.frameRate,
            label = { "${it.numerator / it.denominator} fps" },
            onSelect = vm::setFrameRate,
            enabled = { vm.isSupported(ui.shortSide, it) },
            modifier = Modifier.fillMaxWidth(),
        )

        Label("Quality")
        SegmentedChoice(
            options = ExportQuality.entries,
            selected = ui.quality,
            label = { it.label },
            onSelect = vm::setQuality,
            modifier = Modifier.fillMaxWidth(),
        )

        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(Spacing.md),
        ) {
            Column {
                Text("MP4 · H.264 video · AAC audio", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                Text(
                    "About ${formatBytes(ui.estimatedBytes)} · ${String.format(Locale.ROOT, "%.1f", ui.settings.videoBitrate(project.settings) / 1_000_000f)} Mbit/s · Saved to Movies/Clippr",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                Text("No watermark. Rendered on this device.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
        }
        val supported = vm.isSupported(ui.shortSide, ui.frameRate)
        if (!supported) {
            Text("This device's encoder doesn't support this combination.", style = MaterialTheme.typography.bodySmall, color = c.warning)
        }
        PrimaryButton(
            "Export video",
            onClick = vm::export,
            icon = ClipprIcons.Export,
            enabled = project.durationUs > 0 && supported,
            modifier = Modifier.fillMaxWidth(),
        )
        if (project.durationUs <= 0) {
            Text("Add clips to the timeline before exporting.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = palette.textSecondary)
}

@Composable
private fun Progress(progress: Float?, elapsedMs: Long?, onCancel: (() -> Unit)?, label: String? = null) {
    val c = palette
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            ProgressRing(progress, size = 148.dp)
            Text(
                if (progress == null) "…" else "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                color = c.textPrimary,
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(label ?: if (progress == null) "Preparing encoder…" else "Rendering your video", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        if (elapsedMs != null) {
            Text("Elapsed ${Timecode.formatShort(elapsedMs * 1000)}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "Keep Clippr open until the export finishes.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
            textAlign = TextAlign.Center,
        )
        if (onCancel != null) {
            Spacer(Modifier.height(Spacing.lg))
            SecondaryButton("Cancel export", onCancel, icon = ClipprIcons.Close, destructive = true)
        }
    }
}

@Composable
private fun Busy() {
    Message("Another project is exporting. Wait for it to finish.")
}

@Composable
private fun Done(job: ExportJob.Done, onShare: () -> Unit, onOpen: () -> Unit, onAgain: () -> Unit) {
    val c = palette
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(c.success.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(ClipprIcons.Check, contentDescription = null, tint = c.success, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(Spacing.lg))
        Text("Export complete", style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
        Text(
            "${job.displayName} · ${formatBytes(job.sizeBytes)} · ${Timecode.formatDuration(job.elapsedMs * 1000)} to render",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )
        Text("Saved to Movies/Clippr", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        Spacer(Modifier.height(Spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PrimaryButton("Share", onShare, icon = ClipprIcons.Share)
            SecondaryButton("Play", onOpen, icon = ClipprIcons.Play)
            SecondaryButton("Export again", onAgain)
        }
    }
}

@Composable
private fun Failure(message: String, onRetry: () -> Unit) {
    val c = palette
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(ClipprIcons.Warning, contentDescription = null, tint = c.danger, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(Spacing.md))
        Text("Export failed", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Text(message, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.lg))
        SecondaryButton("Back to settings", onRetry)
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary, textAlign = TextAlign.Center)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
    else -> String.format(Locale.ROOT, "%d KB", bytes / 1000)
}
