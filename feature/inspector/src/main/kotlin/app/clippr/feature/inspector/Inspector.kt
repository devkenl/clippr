package app.clippr.feature.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.clippr.core.model.AspectRatioPreset
import app.clippr.core.model.Clip
import app.clippr.core.model.ClipAudio
import app.clippr.core.model.FitMode
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import app.clippr.core.model.Project
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.TextAlignment
import app.clippr.core.model.TextStyle
import app.clippr.core.model.Transform
import app.clippr.core.model.time.FrameRate
import app.clippr.core.model.time.Timecode
import app.clippr.core.model.time.US_PER_SECOND
import app.clippr.core.ui.components.LabeledSlider
import app.clippr.core.ui.components.PanelSection
import app.clippr.core.ui.components.SecondaryButton
import app.clippr.core.ui.components.SegmentedChoice
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.TimecodeStyle
import app.clippr.core.ui.theme.palette
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Edits applied by the inspector. [onLive] previews a change without recording history;
 * [onCommit] records the latest previewed change as one undo step.
 */
class InspectorActions(
    val onLive: (clipId: String, label: String, update: (Clip) -> Clip) -> Unit,
    val onCommit: () -> Unit,
    val onSpeed: (clipId: String, speed: Float) -> Unit,
    val onProjectSettings: (ProjectSettings) -> Unit,
    val onRenameProject: (String) -> Unit,
) {
    /** A discrete change (toggle, preset): preview and commit at once. */
    fun apply(clipId: String, label: String, update: (Clip) -> Clip) {
        onLive(clipId, label, update)
        onCommit()
    }
}

@Composable
fun Inspector(
    project: Project,
    selectedClip: Clip?,
    actions: InspectorActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        if (selectedClip == null) {
            ProjectPanel(project, actions)
        } else {
            ClipPanel(project, selectedClip, project.asset(selectedClip.assetId), actions)
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun ClipPanel(project: Project, clip: Clip, asset: MediaAsset?, actions: InspectorActions) {
    val c = palette
    val isText = clip.isText
    val type = asset?.type
    val hasVisual = isText || type == MediaType.VIDEO || type == MediaType.IMAGE
    val hasAudio = !isText && asset?.hasAudio == true

    // Header
    Column(Modifier.fillMaxWidth().padding(Spacing.lg)) {
        Text(
            when {
                isText -> "Text"
                type == MediaType.VIDEO -> "Video clip"
                type == MediaType.IMAGE -> "Photo"
                type == MediaType.AUDIO -> "Audio clip"
                else -> "Clip"
            }.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = c.accent,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            if (isText) clip.text?.text?.lines()?.firstOrNull().orEmpty().ifBlank { "Text" } else asset?.displayName ?: "Missing media",
            style = MaterialTheme.typography.titleMedium,
            color = c.textPrimary,
            maxLines = 2,
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            InfoPair("Start", Timecode.format(clip.timelineStartUs, project.settings.frameRate))
            InfoPair("Duration", Timecode.format(clip.durationUs, project.settings.frameRate))
        }
        if (type == MediaType.VIDEO || type == MediaType.AUDIO) {
            Spacer(Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                InfoPair("Source in", Timecode.format(clip.sourceStartUs, project.settings.frameRate))
                InfoPair("Source out", Timecode.format(clip.sourceEndUs, project.settings.frameRate))
            }
        }
    }
    app.clippr.core.ui.components.Hairline()

    if (isText) TextSection(clip, actions)
    if (hasVisual) TransformSection(clip, isText, actions)
    if (hasVisual && !isText) CropSection(clip, actions)
    if (!isText) SpeedSection(clip, type, actions)
    if (hasAudio) AudioSection(clip, actions)
}

@Composable
private fun InfoPair(label: String, value: String) {
    val c = palette
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        Text(value, style = TimecodeStyle, color = c.textSecondary)
    }
}

@Composable
private fun TransformSection(clip: Clip, isText: Boolean, actions: InspectorActions) {
    val t = clip.transform
    fun live(label: String, f: (Transform) -> Transform) = actions.onLive(clip.id, label) { it.copy(transform = f(it.transform)) }
    PanelSection("Transform", ClipprIcons.Transform) {
        LabeledSlider("Position X", t.positionX, { v -> live("Move") { it.copy(positionX = v) } }, actions.onCommit, -1f..1f, ::signed2, defaultValue = 0f)
        LabeledSlider("Position Y", t.positionY, { v -> live("Move") { it.copy(positionY = v) } }, actions.onCommit, -1f..1f, ::signed2, defaultValue = 0f)
        LabeledSlider("Scale", t.scale, { v -> live("Scale") { it.copy(scale = v) } }, actions.onCommit, 0.1f..4f, ::percent, defaultValue = 1f)
        LabeledSlider("Rotation", t.rotationDegrees, { v -> live("Rotate") { it.copy(rotationDegrees = v) } }, actions.onCommit, -180f..180f, { "${it.roundToInt()}°" }, defaultValue = 0f)
        LabeledSlider("Opacity", t.opacity, { v -> live("Opacity") { it.copy(opacity = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 1f)
        if (!isText) {
            Spacer(Modifier.height(Spacing.sm))
            Text("Frame", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
            Spacer(Modifier.height(Spacing.xs))
            SegmentedChoice(
                options = FitMode.entries,
                selected = t.fit,
                label = { if (it == FitMode.FIT) "Fit" else "Fill" },
                onSelect = { mode -> actions.apply(clip.id, "Change framing") { c -> c.copy(transform = c.transform.copy(fit = mode)) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        SecondaryButton(
            "Reset transform",
            onClick = {
                actions.apply(clip.id, "Reset transform") { c -> c.copy(transform = Transform(crop = c.transform.crop, fit = c.transform.fit)) }
            },
            icon = ClipprIcons.Undo,
            enabled = !t.isIdentityPlacement || t.opacity != 1f,
        )
    }
}

@Composable
private fun CropSection(clip: Clip, actions: InspectorActions) {
    val crop = clip.transform.crop
    fun live(f: (app.clippr.core.model.Crop) -> app.clippr.core.model.Crop) =
        actions.onLive(clip.id, "Crop") { it.copy(transform = it.transform.copy(crop = f(it.transform.crop))) }
    PanelSection("Crop", ClipprIcons.Crop, initiallyExpanded = false) {
        LabeledSlider("Left", crop.left, { v -> live { it.copy(left = v) } }, actions.onCommit, 0f..0.45f, ::percent, defaultValue = 0f)
        LabeledSlider("Right", crop.right, { v -> live { it.copy(right = v) } }, actions.onCommit, 0f..0.45f, ::percent, defaultValue = 0f)
        LabeledSlider("Top", crop.top, { v -> live { it.copy(top = v) } }, actions.onCommit, 0f..0.45f, ::percent, defaultValue = 0f)
        LabeledSlider("Bottom", crop.bottom, { v -> live { it.copy(bottom = v) } }, actions.onCommit, 0f..0.45f, ::percent, defaultValue = 0f)
    }
}

private val SpeedPresets = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)

@Composable
private fun SpeedSection(clip: Clip, type: MediaType?, actions: InspectorActions) {
    if (type == MediaType.IMAGE) return
    PanelSection("Speed", ClipprIcons.Speed, initiallyExpanded = false) {
        SegmentedChoice(
            options = SpeedPresets,
            selected = SpeedPresets.firstOrNull { it == clip.speed } ?: 1f,
            label = { formatSpeed(it) },
            onSelect = { actions.onSpeed(clip.id, it) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "Changing speed keeps the clip's start and pushes later clips if it gets longer.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textTertiary,
        )
    }
}

@Composable
private fun AudioSection(clip: Clip, actions: InspectorActions) {
    val c = palette
    val a: ClipAudio = clip.audio
    val maxFade = (clip.durationUs / 2).coerceAtMost(5 * US_PER_SECOND).coerceAtLeast(1)
    fun live(label: String, f: (ClipAudio) -> ClipAudio) = actions.onLive(clip.id, label) { it.copy(audio = f(it.audio)) }
    PanelSection("Audio", ClipprIcons.Volume) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mute clip", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = a.muted,
                onCheckedChange = { m -> actions.apply(clip.id, if (m) "Mute clip" else "Unmute clip") { it.copy(audio = it.audio.copy(muted = m)) } },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.panelHigh, uncheckedBorderColor = c.outline),
            )
        }
        LabeledSlider("Volume", a.volume, { v -> live("Volume") { it.copy(volume = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 1f, enabled = !a.muted)
        LabeledSlider(
            "Fade in",
            a.fadeInUs.coerceAtMost(maxFade).toFloat(),
            { v -> live("Fade in") { it.copy(fadeInUs = v.toLong()) } },
            actions.onCommit,
            0f..maxFade.toFloat(),
            { seconds(it.toLong()) },
            defaultValue = 0f,
        )
        LabeledSlider(
            "Fade out",
            a.fadeOutUs.coerceAtMost(maxFade).toFloat(),
            { v -> live("Fade out") { it.copy(fadeOutUs = v.toLong()) } },
            actions.onCommit,
            0f..maxFade.toFloat(),
            { seconds(it.toLong()) },
            defaultValue = 0f,
        )
    }
}

private val TextColors = listOf(
    0xFFF5F5F7.toInt(), 0xFF101116.toInt(), 0xFFA78BFA.toInt(), 0xFFFBBF24.toInt(), 0xFF5EEAD4.toInt(), 0xFFF87171.toInt(),
)
private val TextBackgrounds = listOf(0x00000000, 0xB3101116.toInt(), 0xE6A78BFA.toInt(), 0xE6F5F5F7.toInt())

@Composable
private fun TextSection(clip: Clip, actions: InspectorActions) {
    val c = palette
    val style: TextStyle = clip.text ?: return
    fun live(label: String, f: (TextStyle) -> TextStyle) = actions.onLive(clip.id, label) { it.copy(text = it.text?.let(f)) }

    PanelSection("Text", ClipprIcons.Text) {
        var draft by remember(clip.id) { mutableStateOf(style.text) }
        var editing by remember(clip.id) { mutableStateOf(false) }
        // Keep the field in sync with undo/redo while the user is not typing.
        LaunchedEffect(style.text) { if (!editing) draft = style.text }
        // Commit typing as one undo step once the user pauses or leaves the field.
        LaunchedEffect(draft, editing) {
            if (editing && draft != style.text) {
                delay(700)
                actions.onCommit()
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                live("Edit text") { s -> s.copy(text = it) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    if (editing && !it.isFocused) actions.onCommit()
                    editing = it.isFocused
                },
            minLines = 2,
            maxLines = 5,
            placeholder = { Text("Type your title") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent,
                unfocusedBorderColor = c.outline,
                focusedContainerColor = c.panel,
                unfocusedContainerColor = c.panel,
                cursorColor = c.accent,
            ),
        )
        Spacer(Modifier.height(Spacing.md))
        LabeledSlider("Size", style.sizeFraction, { v -> live("Text size") { it.copy(sizeFraction = v) } }, actions.onCommit, 0.02f..0.25f, { "${(it * 100).roundToInt()}" }, defaultValue = 0.07f)
        Text("Color", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        SwatchRow(TextColors, style.colorArgb) { argb -> actions.apply(clip.id, "Text color") { it.copy(text = it.text?.copy(colorArgb = argb)) } }
        Spacer(Modifier.height(Spacing.md))
        Text("Background", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        SwatchRow(TextBackgrounds, style.backgroundArgb) { argb -> actions.apply(clip.id, "Text background") { it.copy(text = it.text?.copy(backgroundArgb = argb)) } }
        Spacer(Modifier.height(Spacing.md))
        SegmentedChoice(
            options = TextAlignment.entries,
            selected = style.alignment,
            label = { when (it) { TextAlignment.START -> "Left"; TextAlignment.CENTER -> "Center"; TextAlignment.END -> "Right" } },
            onSelect = { a -> actions.apply(clip.id, "Align text") { it.copy(text = it.text?.copy(alignment = a)) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Bold", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = style.bold,
                onCheckedChange = { b -> actions.apply(clip.id, "Bold text") { it.copy(text = it.text?.copy(bold = b)) } },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.panelHigh, uncheckedBorderColor = c.outline),
            )
        }
    }
}

@Composable
private fun SwatchRow(colors: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    val c = palette
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        colors.forEach { argb ->
            val isSelected = argb == selected
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (isSelected) c.accent else c.outline, CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(if (argb ushr 24 == 0) c.panel else Color(argb))
                    .clickable(role = Role.RadioButton, onClickLabel = "Choose colour") { onSelect(argb) },
                contentAlignment = Alignment.Center,
            ) {
                if (argb ushr 24 == 0) Icon(ClipprIcons.Close, contentDescription = "None", tint = c.textTertiary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private val ShortSides = listOf(720, 1080, 1440, 2160)

@Composable
private fun ProjectPanel(project: Project, actions: InspectorActions) {
    val c = palette
    val settings = project.settings
    Column(Modifier.fillMaxWidth().padding(Spacing.lg)) {
        Text("PROJECT", style = MaterialTheme.typography.labelSmall, color = c.accent)
        Spacer(Modifier.height(Spacing.sm))
        var name by remember(project.id) { mutableStateOf(project.name) }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Name") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onRenameProject(name) }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && name != project.name) actions.onRenameProject(name) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, cursorColor = c.accent),
        )
        Spacer(Modifier.height(Spacing.md))
        Text("Select a clip on the timeline to edit it.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
    }
    app.clippr.core.ui.components.Hairline()
    PanelSection("Format", ClipprIcons.Layers) {
        val shortSide = minOf(settings.width, settings.height)
        Text("Aspect ratio", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        SegmentedChoice(
            options = AspectRatioPreset.entries,
            selected = settings.preset ?: AspectRatioPreset.LANDSCAPE_16_9,
            label = { it.label },
            onSelect = { preset -> actions.onProjectSettings(ProjectSettings.create(preset, shortSide, settings.frameRate)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.md))
        Text("Resolution", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        SegmentedChoice(
            options = ShortSides,
            selected = ShortSides.firstOrNull { it == shortSide } ?: 1080,
            label = { if (it == 2160) "4K" else "${it}p" },
            onSelect = { side ->
                val preset = settings.preset ?: AspectRatioPreset.LANDSCAPE_16_9
                actions.onProjectSettings(ProjectSettings.create(preset, side, settings.frameRate))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.md))
        Text("Frame rate", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        SegmentedChoice(
            options = FrameRate.COMMON,
            selected = FrameRate.COMMON.firstOrNull { it == settings.frameRate } ?: FrameRate.FPS_30,
            label = { "${it.numerator / it.denominator}" },
            onSelect = { fr -> actions.onProjectSettings(settings.copy(frameRate = fr)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.md))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(c.panel)
                .padding(Spacing.md),
        ) {
            Column {
                Text("${settings.width} × ${settings.height} · ${settings.frameRate}", style = TimecodeStyle, color = c.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${project.sequence.allClips.size} clips · ${project.assets.size} media · ${Timecode.formatDuration(project.durationUs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
        }
    }
}

private fun percent(v: Float) = "${(v * 100).roundToInt()}%"
private fun signed2(v: Float) = String.format(Locale.ROOT, "%+.2f", v)
private fun seconds(us: Long) = String.format(Locale.ROOT, "%.1fs", us / 1_000_000f)
private fun formatSpeed(s: Float) = if (s == s.toInt().toFloat()) "${s.toInt()}×" else "${s}×"
