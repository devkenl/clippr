package app.clippr.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.clippr.core.database.ExportRecord
import app.clippr.core.database.ProjectSummary
import app.clippr.core.ui.components.EmptyState
import app.clippr.core.ui.components.PrimaryButton
import app.clippr.core.ui.components.ToolButton
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.palette

/** What Home can ask the screen to do. */
internal class HomeActions(
    val newProject: () -> Unit,
    val importMedia: () -> Unit,
    val templates: () -> Unit,
    val allProjects: () -> Unit,
    val manageStorage: () -> Unit,
    val openProject: (String) -> Unit,
    val rename: (ProjectSummary) -> Unit,
    val duplicate: (String) -> Unit,
    val delete: (ProjectSummary) -> Unit,
    val playExport: (ExportRecord) -> Unit,
    val shareExport: (ExportRecord) -> Unit,
    val removeExport: (ExportRecord) -> Unit,
    val allExports: () -> Unit,
)

@Composable
internal fun HomeTab(state: HomeUiState, wide: Boolean, actions: HomeActions, versionName: String) {
    val c = palette
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                HeroBanner(returning = state.projects.isNotEmpty(), compact = false)
                QuickActions(actions, wide = true)
                RecentProjects(state, actions, columns = 3)
            }
            Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                RecentExportsCard(state, actions)
                StorageCard(state.storage, actions.manageStorage)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            HeroBanner(returning = state.projects.isNotEmpty(), compact = true)
            QuickActions(actions, wide = false)
            RecentProjects(state, actions, columns = 2)
            RecentExportsCard(state, actions)
            StorageCard(state.storage, actions.manageStorage)
        }
    }
    Spacer(Modifier.height(Spacing.xl))
    Text(
        "Clippr $versionName · Free and open source (GPL-3.0) · No ads, accounts or tracking",
        style = MaterialTheme.typography.labelSmall,
        color = c.textTertiary,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun QuickActions(actions: HomeActions, wide: Boolean) {
    val c = palette
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (wide) Spacing.lg else Spacing.sm)) {
        // New project: the primary action, in the accent colour.
        Row(
            Modifier
                .weight(if (wide) 1.4f else 1f)
                .heightIn(min = if (wide) 132.dp else 112.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(c.accentStrong, c.accent)))
                .clickable(role = Role.Button, onClickLabel = "New project", onClick = actions.newProject)
                .padding(if (wide) Spacing.lg else Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = if (wide) Alignment.CenterHorizontally else Alignment.Start) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                    Icon(ClipprIcons.Plus, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text("New project", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                if (wide) Text("Start a new video from scratch", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
            }
            if (wide) Icon(ClipprIcons.ChevronRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        QuickAction(ClipprIcons.Folder, "Import media", if (wide) "Add videos, photos and audio to a new project" else null, actions.importMedia, Modifier.weight(1f), wide)
        QuickAction(ClipprIcons.Grid, "Start from template", if (wide) "Formats and ready-made titles" else null, actions.templates, Modifier.weight(1f), wide)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, title: String, body: String?, onClick: () -> Unit, modifier: Modifier, wide: Boolean) {
    val c = palette
    Column(
        modifier
            .heightIn(min = if (wide) 132.dp else 112.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
            .padding(if (wide) Spacing.lg else Spacing.md),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(c.panelHigh), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 2)
        if (body != null) {
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2)
        }
    }
}

@Composable
private fun RecentProjects(state: HomeUiState, actions: HomeActions, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionHeader("Recent projects", action = if (state.projects.size > columns * 2) "View all" else null, onAction = actions.allProjects)
        when {
            state.loading -> Unit
            state.projects.isEmpty() -> EmptyState(
                icon = ClipprIcons.Film,
                title = "No projects yet",
                body = "Create a project, or import clips to start editing right away. Everything stays on this device.",
                modifier = Modifier.fillMaxWidth(),
                action = { PrimaryButton("New project", actions.newProject, icon = ClipprIcons.Plus) },
            )
            else -> GridRows(state.projects.take(columns * 2), columns) { p, m ->
                ProjectCard(
                    project = p,
                    onOpen = { actions.openProject(p.id) },
                    onRename = { actions.rename(p) },
                    onDuplicate = { actions.duplicate(p.id) },
                    onDelete = { actions.delete(p) },
                    modifier = m,
                )
            }
        }
    }
}

@Composable
internal fun RecentExportsCard(state: HomeUiState, actions: HomeActions, limit: Int = 4, showHeader: Boolean = true) {
    val c = palette
    HomeCard(Modifier.fillMaxWidth()) {
        if (showHeader) {
            SectionHeader("Recent exports", action = if (state.exports.size > limit) "View all" else null, onAction = actions.allExports)
            Spacer(Modifier.height(Spacing.sm))
        }
        if (state.exports.isEmpty()) {
            Text("Videos you export appear here, ready to play or share.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
        state.exports.take(limit).forEachIndexed { i, e ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
            ExportRow(e, missing = e.id in state.missingExportIds, actions)
        }
    }
}

@Composable
private fun ExportRow(export: ExportRecord, missing: Boolean, actions: HomeActions) {
    val c = palette
    var menu by remember { mutableStateOf(false) }
    val thumb = rememberFileImage(export.thumbnailPath)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !missing, role = Role.Button, onClickLabel = "Play ${export.projectName}") { actions.playExport(export) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(76.dp, 56.dp).clip(RoundedCornerShape(10.dp)).background(c.panel), contentAlignment = Alignment.Center) {
            if (thumb != null) {
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(ClipprIcons.Film, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(export.projectName, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${resolutionLabel(export.width, export.height)} · ${formatBytes(export.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            Text(if (missing) "Deleted from the gallery" else ago(export.createdAtMs), style = MaterialTheme.typography.bodySmall, color = if (missing) c.warning else c.textTertiary)
        }
        Icon(
            if (missing) ClipprIcons.Warning else ClipprIcons.CheckCircle,
            contentDescription = if (missing) "File missing" else "Saved to Movies/Clippr",
            tint = if (missing) c.warning else c.success,
            modifier = Modifier.size(22.dp),
        )
        Box {
            ToolButton(ClipprIcons.More, "More actions for ${export.projectName}", { menu = true }, size = 40.dp)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!missing) {
                    DropdownMenuItem(text = { Text("Play") }, leadingIcon = { Icon(ClipprIcons.Play, null) }, onClick = { menu = false; actions.playExport(export) })
                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(ClipprIcons.Share, null) }, onClick = { menu = false; actions.shareExport(export) })
                }
                export.projectId?.let { id ->
                    DropdownMenuItem(text = { Text("Open project") }, leadingIcon = { Icon(ClipprIcons.Edit, null) }, onClick = { menu = false; actions.openProject(id) })
                }
                DropdownMenuItem(
                    text = { Text("Remove from list") },
                    leadingIcon = { Icon(ClipprIcons.Close, null) },
                    onClick = { menu = false; actions.removeExport(export) },
                )
            }
        }
    }
}

@Composable
internal fun StorageCard(storage: StorageInfo?, onManage: (() -> Unit)?, title: String = "Device storage") {
    val c = palette
    HomeCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.md))
        if (storage == null) {
            Text("Measuring…", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            return@HomeCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ClipprIcons.Storage, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                val used = if (storage.totalBytes > 0) storage.usedBytes.toFloat() / storage.totalBytes else 0f
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.panelHigh)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(used.coerceIn(0f, 1f)).clip(RoundedCornerShape(4.dp)).background(c.accent))
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Text("${formatBytes(storage.freeBytes)} free of ${formatBytes(storage.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                    Text("${(used * 100).toInt()}% used", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Text("Used by Clippr · ${formatBytes(storage.clipprBytes)}", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
        Spacer(Modifier.height(Spacing.xs))
        StorageRow(c.accent, "Exports", storage.exportsBytes)
        StorageRow(c.warning, "Copied media & voiceovers", storage.mediaBytes)
        StorageRow(Color(0xFF7C8CF8), "Fonts & LUTs", storage.resourcesBytes)
        StorageRow(c.textTertiary, "Cache", storage.cacheBytes)
        if (onManage != null) {
            Spacer(Modifier.height(Spacing.md))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.panel)
                    .clickable(role = Role.Button, onClick = onManage)
                    .padding(horizontal = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Manage storage", style = MaterialTheme.typography.labelLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
                Icon(ClipprIcons.ChevronRight, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun StorageRow(color: Color, label: String, bytes: Long) {
    val c = palette
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Spacing.md))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(formatBytes(bytes), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
    }
}
