package app.clippr.feature.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clippr.core.database.ProjectSummary
import app.clippr.core.model.AspectRatioPreset
import app.clippr.core.model.time.FrameRate
import app.clippr.core.model.time.Timecode
import app.clippr.core.ui.components.ClipprLogo
import app.clippr.core.ui.components.EmptyState
import app.clippr.core.ui.components.PrimaryButton
import app.clippr.core.ui.components.SegmentedChoice
import app.clippr.core.ui.components.ToolButton
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HomeScreen(viewModel: HomeViewModel, onOpenProject: (String) -> Unit) {
    val c = palette
    val state by viewModel.state.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.background).systemBarsPadding()) {
        val wide = maxWidth >= 720.dp
        val gutter = if (wide) 40.dp else 20.dp
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (wide) 260.dp else 160.dp),
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(if (wide) 20.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Header(wide, onNew = { creating = true })
            }
            when (val s = state) {
                HomeState.Loading -> Unit
                is HomeState.Ready -> {
                    if (s.projects.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                icon = ClipprIcons.Film,
                                title = "Start your first edit",
                                body = "Create a project, import clips and music, cut them on the timeline and export an MP4. Everything stays on this device.",
                                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                                action = { PrimaryButton("New project", { creating = true }, icon = ClipprIcons.Plus) },
                            )
                        }
                    } else {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("Recent projects", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
                        }
                        items(s.projects, key = { it.id }) { p ->
                            ProjectCard(
                                project = p,
                                onOpen = { onOpenProject(p.id) },
                                onRename = { renaming = p },
                                onDuplicate = { viewModel.duplicate(p.id) },
                                onDelete = { deleting = p },
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NewProjectSheet(
            onDismiss = { creating = false },
            onCreate = { name, preset, side, fps ->
                creating = false
                viewModel.create(name, preset, side, fps, onOpenProject)
            },
        )
    }
    renaming?.let { p ->
        RenameDialog(p.name, onDismiss = { renaming = null }) { name ->
            viewModel.rename(p.id, name)
            renaming = null
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("The project and its edits are removed from Clippr. Your original media files are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(p.id)
                    deleting = null
                }) { Text("Delete", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            containerColor = c.panel,
        )
    }
}

@Composable
private fun Header(wide: Boolean, onNew: () -> Unit) {
    val c = palette
    Column(Modifier.fillMaxWidth().padding(top = if (wide) 40.dp else 20.dp, bottom = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClipprLogo(size = if (wide) 40.dp else 32.dp)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text("Clippr", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Text("Create freely. Edit professionally.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
            if (wide) PrimaryButton("New project", onNew, icon = ClipprIcons.Plus)
        }
        if (!wide) {
            Spacer(Modifier.height(Spacing.lg))
            PrimaryButton("New project", onNew, icon = ClipprIcons.Plus, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = palette
    var menu by remember { mutableStateOf(false) }
    val thumbnail by produceState<ImageBitmap?>(null, project.thumbnailPath, project.modifiedAtMs) {
        val path = project.thumbnailPath
        value = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { if (File(path).exists()) BitmapFactory.decodeFile(path)?.asImageBitmap() else null }.getOrNull()
            }
        }
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "Open ${project.name}", onClick = onOpen),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(16.dp))
                .background(c.surface)
                .border(1.dp, c.hairline, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val img = thumbnail
            if (img != null) {
                Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                AspectGlyph(project.width.toFloat() / project.height.coerceAtLeast(1))
            }
            Box(
                Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.background.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(Timecode.formatDuration(project.durationUs), style = MaterialTheme.typography.labelSmall, color = c.textPrimary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${project.width}×${project.height} · ${project.clipCount} clips · ${relativeTime(project.modifiedAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                ToolButton(ClipprIcons.More, "More actions for ${project.name}", { menu = true }, size = 40.dp)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(ClipprIcons.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(ClipprIcons.Copy, null) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(
                        text = { Text("Delete", color = c.danger) },
                        leadingIcon = { Icon(ClipprIcons.Trash, null, tint = c.danger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** Outline of the project's frame shape, used when no thumbnail exists yet. */
@Composable
private fun AspectGlyph(aspect: Float) {
    val c = palette
    val maxW = 64.dp
    val maxH = 44.dp
    val (w, h) = if (aspect >= maxW / maxH) maxW to maxW / aspect else maxH * aspect to maxH
    Box(Modifier.size(w, h).border(1.5.dp, c.outline, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Icon(ClipprIcons.Film, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
    }
}

private val Resolutions = listOf(720, 1080, 2160)

@Composable
private fun NewProjectSheet(
    onDismiss: () -> Unit,
    onCreate: (String, AspectRatioPreset, Int, FrameRate) -> Unit,
) {
    val c = palette
    var name by rememberSaveable { mutableStateOf("") }
    var preset by rememberSaveable { mutableStateOf(AspectRatioPreset.LANDSCAPE_16_9) }
    var side by rememberSaveable { mutableStateOf(1080) }
    var fpsIndex by rememberSaveable { mutableStateOf(FrameRate.COMMON.indexOf(FrameRate.FPS_30)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("New project", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                placeholder = { Text("Untitled project") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, cursorColor = c.accent),
            )
            Text("Format", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                AspectRatioPreset.entries.forEach { p ->
                    AspectTile(p, p == preset, Modifier.weight(1f)) { preset = p }
                }
            }
            Text("Resolution", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
            SegmentedChoice(Resolutions, side, { if (it == 2160) "4K" else "${it}p" }, { side = it }, Modifier.fillMaxWidth())
            Text("Frame rate", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
            SegmentedChoice(
                FrameRate.COMMON.indices.toList(),
                fpsIndex,
                { "${FrameRate.COMMON[it].numerator} fps" },
                { fpsIndex = it },
                Modifier.fillMaxWidth(),
            )
            val (w, h) = preset.dimensionsForShortSide(side)
            Text("$w × $h", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            Spacer(Modifier.height(Spacing.xs))
            PrimaryButton(
                "Create project",
                onClick = { onCreate(name, preset, side, FrameRate.COMMON[fpsIndex]) },
                icon = ClipprIcons.Check,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AspectTile(preset: AspectRatioPreset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = palette
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.accentSoft else c.panel)
            .border(1.dp, if (selected) c.accent else c.hairline, RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClickLabel = preset.label, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val aspect = preset.width.toFloat() / preset.height
        val box = 30.dp
        val (w, h) = if (aspect >= 1f) box to box / aspect else box * aspect to box
        Box(Modifier.height(box), contentAlignment = Alignment.Center) {
            Box(Modifier.size(w, h).border(1.5.dp, if (selected) c.accent else c.textTertiary, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.height(6.dp))
        Text(preset.label, style = MaterialTheme.typography.labelSmall, color = if (selected) c.accent else c.textSecondary)
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val c = palette
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename project") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, cursorColor = c.accent),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = c.panel,
    )
}

private fun relativeTime(ms: Long): String {
    val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0)
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)}d ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))
    }
}
