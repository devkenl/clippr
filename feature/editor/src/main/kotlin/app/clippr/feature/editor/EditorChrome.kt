package app.clippr.feature.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.clippr.core.model.time.FrameRate
import app.clippr.core.model.time.Timecode
import app.clippr.core.render.PreviewQuality
import app.clippr.core.ui.components.ClipprLogo
import app.clippr.core.ui.components.DrawerTool
import app.clippr.core.ui.components.PrimaryButton
import app.clippr.core.ui.components.ToolButton
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.TimecodeStyle
import app.clippr.core.ui.theme.palette

@Composable
internal fun EditorTopBar(
    name: String,
    saveStatus: SaveStatus,
    canUndo: Boolean,
    canRedo: Boolean,
    undoLabel: String?,
    redoLabel: String?,
    focusMode: Boolean,
    compact: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleFocus: (() -> Unit)?,
    onExport: () -> Unit,
    exportEnabled: Boolean,
) {
    val c = palette
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (compact) 52.dp else 56.dp)
            .background(c.background)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(ClipprIcons.Back, "Back to projects", onBack)
        if (!compact) {
            ClipprLogo(size = 26.dp)
            Spacer(Modifier.width(Spacing.md))
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SaveIndicator(saveStatus)
        }
        ToolButton(ClipprIcons.Undo, undoLabel?.let { "Undo $it" } ?: "Undo", onUndo, enabled = canUndo)
        ToolButton(ClipprIcons.Redo, redoLabel?.let { "Redo $it" } ?: "Redo", onRedo, enabled = canRedo)
        if (onToggleFocus != null) {
            ToolButton(ClipprIcons.Focus, if (focusMode) "Show panels" else "Hide panels (focus mode)", onToggleFocus, selected = focusMode)
        }
        Spacer(Modifier.width(Spacing.sm))
        PrimaryButton(if (compact) "Export" else "Export", onExport, icon = ClipprIcons.Export, enabled = exportEnabled)
    }
}

@Composable
private fun SaveIndicator(status: SaveStatus) {
    val c = palette
    val (text, color) = when (status) {
        SaveStatus.SAVED -> "Saved" to c.textTertiary
        SaveStatus.UNSAVED -> "Edited" to c.textTertiary
        SaveStatus.SAVING -> "Saving…" to c.textSecondary
        SaveStatus.FAILED -> "Not saved" to c.danger
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (status == SaveStatus.SAVED) c.success.copy(alpha = 0.8f) else color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
internal fun TransportBar(
    isPlaying: Boolean,
    positionUs: Long,
    durationUs: Long,
    frameRate: FrameRate,
    quality: PreviewQuality,
    guides: GuideMode,
    compact: Boolean,
    enabled: Boolean,
    onPlayPause: () -> Unit,
    onStep: (Int) -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onQuality: (PreviewQuality) -> Unit,
    onGuides: () -> Unit,
    onFullscreen: (() -> Unit)?,
) {
    val c = palette
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(c.background)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                Timecode.format(positionUs, frameRate),
                style = TimecodeStyle,
                color = c.textPrimary,
                maxLines = 1,
            )
            if (!compact) {
                Text(" / " + Timecode.format(durationUs, frameRate), style = TimecodeStyle, color = c.textTertiary, maxLines = 1)
            }
        }
        if (!compact) ToolButton(ClipprIcons.SkipBack, "Go to start", onStart, enabled = enabled)
        ToolButton(ClipprIcons.Back, "Previous frame", { onStep(-1) }, enabled = enabled, size = 40.dp)
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(if (enabled) c.textPrimary else c.panelHigh)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = if (isPlaying) "Pause" else "Play", onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) ClipprIcons.Pause else ClipprIcons.Play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = c.background,
                modifier = Modifier.size(22.dp),
            )
        }
        ToolButton(ClipprIcons.ChevronRight, "Next frame", { onStep(1) }, enabled = enabled, size = 40.dp)
        if (!compact) ToolButton(ClipprIcons.SkipForward, "Go to end", onEnd, enabled = enabled)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            ToolButton(ClipprIcons.Layers, guides.label, onGuides, selected = guides != GuideMode.OFF, size = 40.dp)
            if (!compact) QualityMenu(quality, onQuality)
            if (onFullscreen != null) ToolButton(ClipprIcons.Fullscreen, "Fullscreen preview", onFullscreen, size = 40.dp)
        }
    }
}

@Composable
private fun QualityMenu(quality: PreviewQuality, onQuality: (PreviewQuality) -> Unit) {
    val c = palette
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .height(32.dp)
                .widthIn(min = 72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.panel)
                .clickable(role = Role.Button, onClickLabel = "Preview quality") { open = true }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Preview: ${quality.label}", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PreviewQuality.entries.forEach { q ->
                DropdownMenuItem(
                    text = { Text("${q.label} (${q.maxLongSide}p max)") },
                    onClick = {
                        onQuality(q)
                        open = false
                    },
                    leadingIcon = { if (q == quality) Icon(ClipprIcons.Check, contentDescription = null, tint = c.accent) },
                )
            }
        }
    }
}

@Composable
internal fun TimelineToolbar(
    hasSelection: Boolean,
    snapping: Boolean,
    compact: Boolean,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onAddText: () -> Unit,
    onSnapping: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
) {
    val c = palette
    Row(
        Modifier.fillMaxWidth().height(46.dp).background(c.surface).padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact) {
            ToolButton(ClipprIcons.Split, "Split at playhead (S)", onSplit, size = 40.dp)
            ToolButton(ClipprIcons.Trash, "Delete selection (Delete)", onDelete, enabled = hasSelection, size = 40.dp)
            ToolButton(ClipprIcons.Text, "Add title (T)", onAddText, size = 40.dp)
        }
        Spacer(Modifier.weight(1f))
        ToolButton(ClipprIcons.Magnet, if (snapping) "Snapping on" else "Snapping off", onSnapping, selected = snapping, size = 40.dp)
        ToolButton(ClipprIcons.ZoomOut, "Zoom out", onZoomOut, size = 40.dp)
        ToolButton(ClipprIcons.ZoomIn, "Zoom in", onZoomIn, size = 40.dp)
        ToolButton(ClipprIcons.Fullscreen, "Fit timeline", onFit, size = 40.dp)
    }
}

/** Phone-only bottom drawer with thumb-reachable tools. */
@Composable
internal fun ToolDrawer(
    hasSelection: Boolean,
    onMedia: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onText: () -> Unit,
    onEdit: () -> Unit,
    onProject: () -> Unit,
) {
    val c = palette
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DrawerTool(ClipprIcons.Import, "Media", onMedia)
        DrawerTool(ClipprIcons.Split, "Split", onSplit)
        DrawerTool(ClipprIcons.Sliders, "Edit", onEdit, enabled = hasSelection)
        DrawerTool(ClipprIcons.Trash, "Delete", onDelete, enabled = hasSelection)
        DrawerTool(ClipprIcons.Text, "Text", onText)
        DrawerTool(ClipprIcons.Layers, "Project", onProject)
    }
}

/** Horizontal divider the user drags to resize the preview and timeline areas. */
@Composable
internal fun ResizeHandle(onDrag: (Float) -> Unit, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val c = palette
    var active by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (active) 1f else 0.6f, label = "handle")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(c.background)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState(onDrag),
                onDragStarted = { active = true },
                onDragStopped = { active = false },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(44.dp)
                .height(4.dp)
                .alpha(alpha)
                .clip(RoundedCornerShape(2.dp))
                .background(if (active) c.accent else c.outline),
        )
    }
}
