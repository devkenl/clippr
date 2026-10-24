package app.clippr.feature.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.clippr.core.database.SettingsValues
import app.clippr.core.model.AspectRatioPreset
import app.clippr.core.model.time.FrameRate
import app.clippr.core.ui.components.SecondaryButton
import app.clippr.core.ui.components.SegmentedChoice
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.TimecodeStyle
import app.clippr.core.ui.theme.palette

// Learn ------------------------------------------------------------------------------------------

private data class Lesson(val icon: ImageVector, val title: String, val body: String)

private val GettingStarted = listOf(
    Lesson(ClipprIcons.Plus, "1. Create a project", "Pick a format (16:9 for YouTube, 9:16 for Reels and TikTok) or start from a template."),
    Lesson(ClipprIcons.Import, "2. Import media", "Add videos, photos and music. The first import is laid out on the timeline for you."),
    Lesson(ClipprIcons.Split, "3. Cut", "Move the playhead and tap Split (S). Drag a clip's edges to trim; long-press a clip for more."),
    Lesson(ClipprIcons.Sliders, "4. Polish", "Select a clip, then use the tabs on the right: Video, Audio, Speed, Animation, Adjust and Effects."),
    Lesson(ClipprIcons.Export, "5. Export", "Export an MP4 to Movies/Clippr and share it straight from Clippr."),
)

private val Gestures = listOf(
    "Tap a clip in the preview" to "Select it",
    "Drag / pinch / twist in the preview" to "Move, scale and rotate the clip",
    "Double-tap the preview" to "Reset the clip's position",
    "Long-press a clip on the timeline" to "Split, duplicate, freeze frame, delete",
    "Pinch the timeline" to "Zoom in and out",
    "Double-tap a slider label" to "Reset that value",
)

private val Shortcuts = listOf(
    "Space / K" to "Play / pause",
    "S or Ctrl+B" to "Split at playhead",
    "Delete" to "Delete selection (Shift: ripple delete)",
    "Ctrl+Z / Ctrl+Shift+Z" to "Undo / redo",
    "← / →" to "Previous / next frame (Shift: 1 second)",
    "J / L" to "Back / forward 1 second",
    "Ctrl+D" to "Duplicate clip",
    "T" to "Add title",
    "R" to "Record voiceover",
    "Ctrl+I" to "Import media",
    "Ctrl+E" to "Export",
    "F" to "Fullscreen preview",
)

private val Tips = listOf(
    Lesson(ClipprIcons.Cloud, "Files from Google Drive", "Clippr copies files picked from cloud apps to your device, because streamed files can stop playing mid-edit."),
    Lesson(ClipprIcons.Speed, "Smooth 4K previews", "If playback stutters on heavy timelines, switch the preview to Balanced or Fast. Exports always use full quality."),
    Lesson(ClipprIcons.Keyframe, "Animate anything", "Add a keyframe, move the playhead, then drag the clip in the preview. Clippr animates between them."),
    Lesson(ClipprIcons.Mic, "Voiceovers", "Tap the microphone: the video plays muted while you talk, and the take lands on its own audio track."),
)

@Composable
internal fun LearnTab(wide: Boolean) {
    val c = palette
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
        Column {
            Text("Learn", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Text("Everything here works offline.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
        Text("Getting started", style = MaterialTheme.typography.titleMedium, color = c.textSecondary)
        GridRows(GettingStarted, if (wide) 3 else 1, spacing = Spacing.md) { l, m -> LessonCard(l, m) }
        Text("Tips", style = MaterialTheme.typography.titleMedium, color = c.textSecondary)
        GridRows(Tips, if (wide) 2 else 1, spacing = Spacing.md) { l, m -> LessonCard(l, m) }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg), modifier = Modifier.fillMaxWidth()) {
            if (wide) {
                ReferenceCard("Touch gestures", Gestures, Modifier.weight(1f))
                ReferenceCard("Keyboard shortcuts", Shortcuts, Modifier.weight(1f), mono = true)
            }
        }
        if (!wide) {
            ReferenceCard("Touch gestures", Gestures, Modifier.fillMaxWidth())
            ReferenceCard("Keyboard shortcuts", Shortcuts, Modifier.fillMaxWidth(), mono = true)
        }
    }
}

@Composable
private fun LessonCard(lesson: Lesson, modifier: Modifier) {
    val c = palette
    HomeCard(modifier) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(lesson.icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(Spacing.md))
        Text(lesson.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(lesson.body, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}

@Composable
private fun ReferenceCard(title: String, rows: List<Pair<String, String>>, modifier: Modifier, mono: Boolean = false) {
    val c = palette
    HomeCard(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.sm))
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    k,
                    style = if (mono) TimecodeStyle else MaterialTheme.typography.bodyMedium,
                    color = c.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(v, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
            }
        }
    }
}

// Settings ---------------------------------------------------------------------------------------

private val ShortSides = listOf(720, 1080, 1440, 2160)

internal class SettingsActions(
    val update: ((SettingsValues) -> SettingsValues) -> Unit,
    val clearCache: () -> Unit,
    val findUnused: () -> Unit,
    val removeUnused: () -> Unit,
    val refreshStorage: () -> Unit,
)

@Composable
internal fun SettingsTab(state: HomeUiState, wide: Boolean, versionName: String, actions: SettingsActions) {
    val c = palette
    val s = state.settings
    LaunchedEffect(Unit) {
        actions.refreshStorage()
        actions.findUnused()
    }
    Column(Modifier.widthIn(max = 980.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
        val left: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                HomeCard(Modifier.fillMaxWidth()) {
                    Text("New projects", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text("Used by “New project” and “Import media”.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                    Spacer(Modifier.height(Spacing.md))
                    Label("Format")
                    SegmentedChoice(AspectRatioPreset.entries, s.defaultAspect, { it.label }, { v -> actions.update { it.copy(defaultAspect = v) } }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Spacing.md))
                    Label("Resolution")
                    SegmentedChoice(ShortSides, s.defaultShortSide, { if (it == 2160) "4K" else "${it}p" }, { v -> actions.update { it.copy(defaultShortSide = v) } }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Spacing.md))
                    Label("Frame rate")
                    SegmentedChoice(FrameRate.COMMON, s.defaultFrameRate, { "${it.numerator / it.denominator}" }, { v -> actions.update { it.copy(defaultFrameRate = v) } }, Modifier.fillMaxWidth())
                }
                HomeCard(Modifier.fillMaxWidth()) {
                    Text("Editing", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Spacer(Modifier.height(Spacing.md))
                    Label("Preview quality")
                    SegmentedChoice(
                        listOf("HIGH", "BALANCED", "FAST"),
                        s.previewQuality,
                        { it.lowercase().replaceFirstChar(Char::uppercase) },
                        { v -> actions.update { it.copy(previewQuality = v) } },
                        Modifier.fillMaxWidth(),
                    )
                    Text("Lower quality keeps playback smooth on heavy 4K timelines. Exports always use full quality.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                    Spacer(Modifier.height(Spacing.md))
                    Label("Default export quality")
                    SegmentedChoice(listOf("STANDARD", "HIGH"), s.exportQuality, { it.lowercase().replaceFirstChar(Char::uppercase) }, { v -> actions.update { it.copy(exportQuality = v) } }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Spacing.sm))
                    SettingSwitch(
                        "Copy files from cloud apps",
                        "Files picked from Google Drive and similar apps are copied to this device so playback and export stay reliable.",
                        s.copyCloudFiles,
                    ) { v -> actions.update { it.copy(copyCloudFiles = v) } }
                    SettingSwitch("Voiceover countdown", "Count 3-2-1 before recording starts.", s.voiceoverCountdown) { v -> actions.update { it.copy(voiceoverCountdown = v) } }
                }
            }
        }
        val right: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                StorageCard(state.storage, onManage = null, title = "Storage")
                HomeCard(Modifier.fillMaxWidth()) {
                    Text("Free up space", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Spacer(Modifier.height(Spacing.sm))
                    Text("Thumbnails and waveforms are rebuilt when needed.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Spacer(Modifier.height(Spacing.sm))
                    SecondaryButton("Clear cache (${formatBytes(state.storage?.cacheBytes ?: 0)})", actions.clearCache, icon = ClipprIcons.Trash)
                    Spacer(Modifier.height(Spacing.md))
                    val unused = state.storage?.unusedBytes
                    Text(
                        "Copies, voiceovers and stills that no project uses any more" +
                            if (unused == null) " (checking…)" else ": ${formatBytes(unused)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    SecondaryButton("Remove unused media", actions.removeUnused, icon = ClipprIcons.Trash, enabled = (unused ?: 0L) > 0L)
                    Spacer(Modifier.height(Spacing.sm))
                    Text("Exported videos live in Movies/Clippr; manage them in your gallery.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
                AboutCard(versionName)
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                left(Modifier.weight(1f))
                right(Modifier.weight(1f))
            }
        } else {
            left(Modifier.fillMaxWidth())
            right(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AboutCard(versionName: String) {
    val c = palette
    val context = LocalContext.current
    HomeCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            app.clippr.core.ui.components.ClipprLogo(size = 32.dp)
            Spacer(Modifier.width(Spacing.md))
            Column {
                Text("Clippr $versionName", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text("GPL-3.0-or-later", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            "Free and open source. No ads, watermarks, accounts or tracking. Clippr has no internet permission: your projects and media never leave this device.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(Spacing.md))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.panel)
                .clickable(role = Role.Button) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }
                }
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ClipprIcons.Link, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("Source code and licences", style = MaterialTheme.typography.labelLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
            Icon(ClipprIcons.ChevronRight, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

private const val SOURCE_URL = "https://github.com/devkenl/clippr"

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun SettingSwitch(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = palette
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
        Spacer(Modifier.width(Spacing.md))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.panelHigh, uncheckedBorderColor = c.outline),
        )
    }
}
