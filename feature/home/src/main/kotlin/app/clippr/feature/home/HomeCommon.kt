package app.clippr.feature.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import app.clippr.core.database.ProjectSummary
import app.clippr.core.model.time.Timecode
import app.clippr.core.ui.components.ToolButton
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** "4K", "1080p", … from a canvas size. */
internal fun resolutionLabel(width: Int, height: Int): String {
    val short = minOf(width, height)
    return when {
        short >= 2160 -> "4K"
        short >= 1440 -> "1440p"
        else -> "${short}p"
    }
}

internal fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0).toDouble()
    return when {
        b >= 1e12 -> String.format(Locale.ROOT, "%.1f TB", b / 1e12)
        b >= 1e9 -> String.format(Locale.ROOT, "%.1f GB", b / 1e9)
        b >= 1e6 -> String.format(Locale.ROOT, "%.0f MB", b / 1e6)
        b >= 1e3 -> String.format(Locale.ROOT, "%.0f KB", b / 1e3)
        else -> "${bytes.coerceAtLeast(0)} B"
    }
}

/** "just now", "5 minutes ago", "2 hours ago", "3 days ago", or a date. */
internal fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - ms).coerceAtLeast(0) / 60_000
    fun plural(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> plural(minutes, "minute")
        minutes < 60 * 24 -> plural(minutes / 60, "hour")
        minutes < 60 * 24 * 7 -> plural(minutes / (60 * 24), "day")
        minutes < 60 * 24 * 30 -> plural(minutes / (60 * 24 * 7), "week")
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    }
}

/** Loads a JPEG from app storage off the main thread. */
@Composable
internal fun rememberFileImage(path: String?, version: Any? = null): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, path, version) {
        value = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { if (File(path).exists()) BitmapFactory.decodeFile(path)?.asImageBitmap() else null }.getOrNull()
            }
        }
    }
    return image
}

/** A rounded panel used for the right-hand cards on Home. */
@Composable
internal fun HomeCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = palette
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(18.dp))
            .padding(Spacing.lg),
        content = content,
    )
}

/** Section title with an optional "View all →" link. */
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    val c = palette
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
        if (action != null) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = c.accent)
                Spacer(Modifier.width(4.dp))
                Icon(ClipprIcons.ChevronRight, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Project card: thumbnail with duration, name, format and when it was last edited. */
@Composable
internal fun ProjectCard(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = palette
    var menu by remember { mutableStateOf(false) }
    val thumbnail = rememberFileImage(project.thumbnailPath, project.modifiedAtMs)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "Open ${project.name}", onClick = onOpen),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(c.panel),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                AspectGlyph(project.width.toFloat() / project.height.coerceAtLeast(1))
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.background.copy(alpha = 0.78f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(Timecode.formatDuration(project.durationUs), style = MaterialTheme.typography.labelSmall, color = c.textPrimary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${resolutionLabel(project.width, project.height)} · ${Timecode.formatDuration(project.durationUs)} · Edited ${ago(project.modifiedAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                ToolButton(ClipprIcons.More, "More actions for ${project.name}", { menu = true }, size = 40.dp)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, leadingIcon = { Icon(ClipprIcons.Edit, null) }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(ClipprIcons.Text, null) }, onClick = { menu = false; onRename() })
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
internal fun AspectGlyph(aspect: Float) {
    val c = palette
    val maxW = 64.dp
    val maxH = 44.dp
    val (w, h) = if (aspect >= maxW / maxH) maxW to maxW / aspect else maxH * aspect to maxH
    Box(Modifier.size(w, h).border(1.5.dp, c.outline, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Icon(ClipprIcons.Film, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
    }
}

/** Lays [items] out in rows of [columns], each cell sharing the width equally. */
@Composable
internal fun <T> GridRows(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = Spacing.lg,
    cell: @Composable (T, Modifier) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.chunked(columns.coerceAtLeast(1)).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
