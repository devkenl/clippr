package app.clippr.feature.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clippr.core.model.Animation
import app.clippr.core.model.AspectRatioPreset
import app.clippr.core.model.Clip
import app.clippr.core.model.ColorGrade
import app.clippr.core.model.Easing
import app.clippr.core.model.FilterPreset
import app.clippr.core.model.FitMode
import app.clippr.core.model.MediaAsset
import app.clippr.core.model.MediaType
import app.clippr.core.model.Project
import app.clippr.core.model.ProjectSettings
import app.clippr.core.model.TextAlignment
import app.clippr.core.model.TextStyle
import app.clippr.core.model.Transform
import app.clippr.core.model.VisualEffects
import app.clippr.core.model.time.FrameRate
import app.clippr.core.model.time.Timecode
import app.clippr.core.model.time.US_PER_SECOND
import app.clippr.core.ui.components.FilterChip
import app.clippr.core.ui.components.LabeledSlider
import app.clippr.core.ui.components.PanelSection
import app.clippr.core.ui.components.SecondaryButton
import app.clippr.core.ui.components.SegmentedChoice
import app.clippr.core.ui.components.ToolButton
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.TimecodeStyle
import app.clippr.core.ui.theme.palette
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/** A font the user can pick, with a Compose family to preview its name in. */
@Immutable
data class FontChoice(val id: String, val name: String, val family: FontFamily, val isCustom: Boolean)

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
    val onSeek: (timelineUs: Long) -> Unit = {},
    val onImportFont: () -> Unit = {},
    val onImportLut: () -> Unit = {},
) {
    /** A discrete change (toggle, preset): preview and commit at once. */
    fun apply(clipId: String, label: String, update: (Clip) -> Clip) {
        onLive(clipId, label, update)
        onCommit()
    }
}

/** Where the playhead is relative to the inspected clip, for keyframe editing. */
private class KeyContext(val clip: Clip, val playheadUs: Long, val toleranceUs: Long) {
    val insideClip: Boolean get() = clip.contains(playheadUs)
    val sourceUs: Long get() = clip.sourceTimeAt(playheadUs)
}

@Composable
fun Inspector(
    project: Project,
    selectedClip: Clip?,
    actions: InspectorActions,
    modifier: Modifier = Modifier,
    playheadUs: Long = 0L,
    fonts: List<FontChoice> = emptyList(),
    luts: List<String> = emptyList(),
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        if (selectedClip == null) {
            ProjectPanel(project, actions)
        } else {
            val tolerance = project.settings.frameRate.frameDurationUs / 2 + 1
            ClipPanel(project, selectedClip, project.asset(selectedClip.assetId), actions, KeyContext(selectedClip, playheadUs, tolerance), fonts, luts)
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun ClipPanel(
    project: Project,
    clip: Clip,
    asset: MediaAsset?,
    actions: InspectorActions,
    key: KeyContext,
    fonts: List<FontChoice>,
    luts: List<String>,
) {
    val c = palette
    val isText = clip.isText
    val type = asset?.type
    val hasVisual = isText || type == MediaType.VIDEO || type == MediaType.IMAGE
    val hasAudio = !isText && asset?.hasAudio == true

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

    if (isText) TextSection(clip, actions, fonts)
    if (hasVisual) TransformSection(clip, isText, actions, key)
    if (hasVisual) KeyframeSection(clip, actions, key)
    if (hasVisual && !isText) ColorSection(clip, actions, luts)
    if (hasVisual && !isText) EffectsSection(clip, actions)
    if (hasVisual) FadeSection(clip, isText, actions)
    if (hasVisual && !isText) CropSection(clip, actions)
    if (!isText) SpeedSection(clip, type, actions)
    if (hasAudio) AudioSection(clip, actions, key)
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
private fun TransformSection(clip: Clip, isText: Boolean, actions: InspectorActions, key: KeyContext) {
    // With keyframes, show (and edit) the values at the playhead.
    val t = if (clip.keyframes.isNotEmpty() && key.insideClip) Animation.transformAt(clip, key.sourceUs) else clip.transform
    fun live(label: String, f: (Transform) -> Transform) {
        val src = if (key.insideClip) key.sourceUs else clip.sourceStartUs
        actions.onLive(clip.id, label) { Animation.editTransformAt(it, src, key.toleranceUs, f) }
    }
    PanelSection("Transform", ClipprIcons.Transform) {
        if (clip.keyframes.isNotEmpty()) {
            Text(
                if (key.insideClip) "Animated — changes update the keyframe at the playhead." else "Animated — move the playhead over the clip to edit.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.accent,
            )
            Spacer(Modifier.height(Spacing.xs))
        }
        val editable = clip.keyframes.isEmpty() || key.insideClip
        LabeledSlider("Position X", t.positionX, { v -> live("Move") { it.copy(positionX = v) } }, actions.onCommit, -1f..1f, ::signed2, defaultValue = 0f, enabled = editable)
        LabeledSlider("Position Y", t.positionY, { v -> live("Move") { it.copy(positionY = v) } }, actions.onCommit, -1f..1f, ::signed2, defaultValue = 0f, enabled = editable)
        LabeledSlider("Scale", t.scale, { v -> live("Scale") { it.copy(scale = v) } }, actions.onCommit, 0.1f..4f, ::percent, defaultValue = 1f, enabled = editable)
        LabeledSlider("Rotation", t.rotationDegrees, { v -> live("Rotate") { it.copy(rotationDegrees = v) } }, actions.onCommit, -180f..180f, { "${it.roundToInt()}°" }, defaultValue = 0f, enabled = editable)
        LabeledSlider("Opacity", t.opacity, { v -> live("Opacity") { it.copy(opacity = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 1f, enabled = editable)
        if (!isText) {
            Spacer(Modifier.height(Spacing.sm))
            Text("Frame", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
            Spacer(Modifier.height(Spacing.xs))
            SegmentedChoice(
                options = FitMode.entries,
                selected = clip.transform.fit,
                label = { if (it == FitMode.FIT) "Fit" else "Fill" },
                onSelect = { mode -> actions.apply(clip.id, "Change framing") { c -> c.copy(transform = c.transform.copy(fit = mode)) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        SecondaryButton(
            "Reset transform",
            onClick = {
                actions.apply(clip.id, "Reset transform") { c -> c.copy(transform = Transform(crop = c.transform.crop, fit = c.transform.fit), keyframes = emptyList()) }
            },
            icon = ClipprIcons.Undo,
            enabled = !clip.transform.isIdentityPlacement || clip.transform.opacity != 1f || clip.keyframes.isNotEmpty(),
        )
    }
}

private val EasingLabels = mapOf(
    Easing.LINEAR to "Linear",
    Easing.EASE_IN to "Ease in",
    Easing.EASE_OUT to "Ease out",
    Easing.EASE_IN_OUT to "Smooth",
    Easing.HOLD to "Hold",
)

@Composable
private fun KeyframeSection(clip: Clip, actions: InspectorActions, key: KeyContext) {
    val c = palette
    val keys = clip.keyframes
    val atIndex = if (key.insideClip) Animation.indexNear(keys, key.sourceUs, key.toleranceUs) { it.sourceUs } else -1
    val visible = keys.mapNotNull { k -> Animation.timelineTimeOf(clip, k.sourceUs) }.sorted()
    PanelSection("Keyframes", ClipprIcons.Keyframe, initiallyExpanded = keys.isNotEmpty()) {
        Text(
            "Keyframes record position, scale, rotation and opacity at a moment. Clippr animates between them.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val prev = visible.lastOrNull { it < key.playheadUs - key.toleranceUs }
            val next = visible.firstOrNull { it > key.playheadUs + key.toleranceUs }
            ToolButton(ClipprIcons.Back, "Previous keyframe", { prev?.let(actions.onSeek) }, enabled = prev != null, size = 40.dp)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (atIndex >= 0) c.accentSoft else c.panel)
                    .clickable(enabled = key.insideClip, role = Role.Button) {
                        if (atIndex >= 0) {
                            actions.apply(clip.id, "Remove keyframe") { Animation.removeTransformKeyframe(it, key.sourceUs, key.toleranceUs) }
                        } else {
                            actions.apply(clip.id, "Add keyframe") { Animation.addTransformKeyframe(it, key.sourceUs, key.toleranceUs) }
                        }
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (atIndex >= 0) ClipprIcons.KeyframeFilled else ClipprIcons.Keyframe,
                        contentDescription = null,
                        tint = if (key.insideClip) c.accent else c.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        when {
                            !key.insideClip -> "Playhead is outside this clip"
                            atIndex >= 0 -> "Remove keyframe"
                            else -> "Add keyframe here"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (key.insideClip) c.textPrimary else c.textTertiary,
                    )
                }
            }
            ToolButton(ClipprIcons.ChevronRight, "Next keyframe", { next?.let(actions.onSeek) }, enabled = next != null, size = 40.dp)
        }
        if (atIndex >= 0) {
            Spacer(Modifier.height(Spacing.md))
            Text("Motion to the next keyframe", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(Spacing.xs))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Easing.entries.forEach { e ->
                    FilterChip(EasingLabels.getValue(e), keys[atIndex].easing == e, {
                        actions.apply(clip.id, "Change easing") { Animation.setTransformEasing(it, key.sourceUs, key.toleranceUs, e) }
                    })
                }
            }
        }
        if (keys.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Text("${keys.size} keyframe${if (keys.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            Spacer(Modifier.height(Spacing.xs))
            SecondaryButton(
                "Clear all keyframes",
                onClick = {
                    actions.apply(clip.id, "Clear keyframes") {
                        val held = if (key.insideClip) Animation.transformAt(it, key.sourceUs) else it.transform
                        it.copy(keyframes = emptyList(), transform = held)
                    }
                },
                icon = ClipprIcons.Trash,
                destructive = true,
            )
        }
    }
}

@Composable
private fun ColorSection(clip: Clip, actions: InspectorActions, luts: List<String>) {
    val c = palette
    val g = clip.color
    fun live(label: String, f: (ColorGrade) -> ColorGrade) = actions.onLive(clip.id, label) { it.copy(color = f(it.color)) }
    PanelSection("Color", ClipprIcons.ColorWheel, initiallyExpanded = !g.isIdentity) {
        Text("Filter", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterPreset.entries.forEach { f ->
                FilterChip(f.label, g.filter == f, { actions.apply(clip.id, "Filter") { it.copy(color = it.color.copy(filter = f)) } })
            }
        }
        if (g.filter != FilterPreset.NONE) {
            LabeledSlider("Filter strength", g.filterStrength, { v -> live("Filter strength") { it.copy(filterStrength = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 1f)
        }
        Spacer(Modifier.height(Spacing.sm))
        LabeledSlider("Exposure", g.exposure, { v -> live("Exposure") { it.copy(exposure = v) } }, actions.onCommit, -2f..2f, { String.format(Locale.ROOT, "%+.1f", it) }, defaultValue = 0f)
        LabeledSlider("Contrast", g.contrast, { v -> live("Contrast") { it.copy(contrast = v) } }, actions.onCommit, -1f..1f, ::signedPercent, defaultValue = 0f)
        LabeledSlider("Highlights", g.highlights, { v -> live("Highlights") { it.copy(highlights = v) } }, actions.onCommit, -1f..1f, ::signedPercent, defaultValue = 0f)
        LabeledSlider("Shadows", g.shadows, { v -> live("Shadows") { it.copy(shadows = v) } }, actions.onCommit, -1f..1f, ::signedPercent, defaultValue = 0f)
        LabeledSlider("Saturation", g.saturation, { v -> live("Saturation") { it.copy(saturation = v) } }, actions.onCommit, -1f..1f, ::signedPercent, defaultValue = 0f)
        LabeledSlider("Temperature", g.temperature, { v -> live("Temperature") { it.copy(temperature = v) } }, actions.onCommit, -1f..1f, ::signedPercent, defaultValue = 0f)
        LabeledSlider("Tint", g.tint, { v -> live("Tint") { it.copy(tint = v) } }, actions.onCommit, -1f..1f, ::signedPercent, defaultValue = 0f)
        Spacer(Modifier.height(Spacing.sm))
        Text("LUT (.cube)", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterChip("None", g.lutId == null, { actions.apply(clip.id, "Remove LUT") { it.copy(color = it.color.copy(lutId = null)) } })
            luts.forEach { id ->
                FilterChip(id.removeSuffix(".cube"), g.lutId == id, { actions.apply(clip.id, "Apply LUT") { it.copy(color = it.color.copy(lutId = id)) } })
            }
            FilterChip("+ Import", false, actions.onImportLut)
        }
        if (g.lutId != null && g.lutId !in luts) {
            Text("LUT “${g.lutId}” is missing on this device; it is skipped.", style = MaterialTheme.typography.bodySmall, color = c.warning)
        }
        Spacer(Modifier.height(Spacing.md))
        SecondaryButton(
            "Reset color",
            onClick = { actions.apply(clip.id, "Reset color") { it.copy(color = ColorGrade.NONE) } },
            icon = ClipprIcons.Undo,
            enabled = !g.isIdentity,
        )
    }
}

@Composable
private fun EffectsSection(clip: Clip, actions: InspectorActions) {
    val fx = clip.effects
    fun live(label: String, f: (VisualEffects) -> VisualEffects) = actions.onLive(clip.id, label) { it.copy(effects = f(it.effects)) }
    PanelSection("Effects", ClipprIcons.Sparkle, initiallyExpanded = fx.blur > 0f || fx.vignette > 0f || fx.grain > 0f) {
        LabeledSlider("Blur", fx.blur, { v -> live("Blur") { it.copy(blur = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 0f)
        LabeledSlider("Vignette", fx.vignette, { v -> live("Vignette") { it.copy(vignette = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 0f)
        LabeledSlider("Film grain", fx.grain, { v -> live("Film grain") { it.copy(grain = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 0f)
    }
}

@Composable
private fun FadeSection(clip: Clip, isText: Boolean, actions: InspectorActions) {
    val fx = clip.effects
    val maxFade = (clip.durationUs / 2).coerceAtMost(5 * US_PER_SECOND).coerceAtLeast(1)
    fun live(label: String, f: (VisualEffects) -> VisualEffects) = actions.onLive(clip.id, label) { it.copy(effects = f(it.effects)) }
    PanelSection(if (isText) "Fade" else "Fade to black", ClipprIcons.Fade, initiallyExpanded = fx.hasFades) {
        LabeledSlider(
            if (isText) "Fade in" else "From black",
            fx.fadeInUs.coerceAtMost(maxFade).toFloat(),
            { v -> live("Fade in") { it.copy(fadeInUs = v.toLong()) } },
            actions.onCommit,
            0f..maxFade.toFloat(),
            { seconds(it.toLong()) },
            defaultValue = 0f,
        )
        LabeledSlider(
            if (isText) "Fade out" else "To black",
            fx.fadeOutUs.coerceAtMost(maxFade).toFloat(),
            { v -> live("Fade out") { it.copy(fadeOutUs = v.toLong()) } },
            actions.onCommit,
            0f..maxFade.toFloat(),
            { seconds(it.toLong()) },
            defaultValue = 0f,
        )
        if (!isText) {
            Text(
                "For a dip to black between two clips, fade the first one out and the next one in.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary,
            )
        }
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
private fun AudioSection(clip: Clip, actions: InspectorActions, key: KeyContext) {
    val c = palette
    val a = clip.audio
    val maxFade = (clip.durationUs / 2).coerceAtMost(5 * US_PER_SECOND).coerceAtLeast(1)
    val keys = clip.volumeKeyframes
    val volume = if (keys.isNotEmpty() && key.insideClip) Animation.volumeAt(clip, key.sourceUs) else a.volume
    val atIndex = if (key.insideClip) Animation.indexNear(keys, key.sourceUs, key.toleranceUs) { it.sourceUs } else -1
    fun liveAudio(label: String, f: (app.clippr.core.model.ClipAudio) -> app.clippr.core.model.ClipAudio) =
        actions.onLive(clip.id, label) { it.copy(audio = f(it.audio)) }
    PanelSection("Audio", ClipprIcons.Volume) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mute clip", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = a.muted,
                onCheckedChange = { m -> actions.apply(clip.id, if (m) "Mute clip" else "Unmute clip") { it.copy(audio = it.audio.copy(muted = m)) } },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.panelHigh, uncheckedBorderColor = c.outline),
            )
        }
        LabeledSlider(
            if (keys.isEmpty()) "Volume" else "Volume (keyframed)",
            volume,
            { v ->
                val src = if (key.insideClip) key.sourceUs else clip.sourceStartUs
                actions.onLive(clip.id, "Volume") { Animation.editVolumeAt(it, src, key.toleranceUs, v) }
            },
            actions.onCommit,
            0f..1f,
            ::percent,
            defaultValue = 1f,
            enabled = !a.muted && (keys.isEmpty() || key.insideClip),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (keys.isEmpty()) "Volume keyframes" else "${keys.size} volume keyframe${if (keys.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ToolButton(
                if (atIndex >= 0) ClipprIcons.KeyframeFilled else ClipprIcons.Keyframe,
                if (atIndex >= 0) "Remove volume keyframe" else "Add volume keyframe at playhead",
                {
                    if (atIndex >= 0) {
                        actions.apply(clip.id, "Remove volume keyframe") { Animation.removeVolumeKeyframe(it, key.sourceUs, key.toleranceUs) }
                    } else {
                        actions.apply(clip.id, "Add volume keyframe") { Animation.addVolumeKeyframe(it, key.sourceUs, key.toleranceUs) }
                    }
                },
                enabled = key.insideClip,
                selected = atIndex >= 0,
                size = 40.dp,
            )
        }
        LabeledSlider(
            "Fade in",
            a.fadeInUs.coerceAtMost(maxFade).toFloat(),
            { v -> liveAudio("Fade in") { it.copy(fadeInUs = v.toLong()) } },
            actions.onCommit,
            0f..maxFade.toFloat(),
            { seconds(it.toLong()) },
            defaultValue = 0f,
        )
        LabeledSlider(
            "Fade out",
            a.fadeOutUs.coerceAtMost(maxFade).toFloat(),
            { v -> liveAudio("Fade out") { it.copy(fadeOutUs = v.toLong()) } },
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
private fun TextSection(clip: Clip, actions: InspectorActions, fonts: List<FontChoice>) {
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
        Text("Font", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            fonts.forEach { f -> FontTile(f, f.id == style.fontId) { actions.apply(clip.id, "Change font") { it.copy(text = it.text?.copy(fontId = f.id)) } } }
            Box(
                Modifier
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.panel)
                    .border(1.dp, c.hairline, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = "Import a font", onClick = actions.onImportFont)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ClipprIcons.Import, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Import font", style = MaterialTheme.typography.labelMedium, color = c.accent)
                }
            }
        }
        if (fonts.none { it.id == style.fontId }) {
            Text("This font is missing on this device; the default font is used.", style = MaterialTheme.typography.bodySmall, color = c.warning)
        }
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
        LabeledSlider("Outline", style.strokeWidth, { v -> live("Text outline") { it.copy(strokeWidth = v) } }, actions.onCommit, 0f..0.25f, { "${(it * 100).roundToInt()}" }, defaultValue = 0f)
        if (style.strokeWidth > 0f) {
            Text("Outline color", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(Spacing.xs))
            SwatchRow(TextColors, style.strokeColorArgb) { argb -> actions.apply(clip.id, "Outline color") { it.copy(text = it.text?.copy(strokeColorArgb = argb)) } }
            Spacer(Modifier.height(Spacing.md))
        }
        SegmentedChoice(
            options = TextAlignment.entries,
            selected = style.alignment,
            label = { when (it) { TextAlignment.START -> "Left"; TextAlignment.CENTER -> "Center"; TextAlignment.END -> "Right" } },
            onSelect = { a -> actions.apply(clip.id, "Align text") { it.copy(text = it.text?.copy(alignment = a)) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        ToggleRow("Bold", style.bold) { b -> actions.apply(clip.id, "Bold text") { it.copy(text = it.text?.copy(bold = b)) } }
        ToggleRow("Shadow", style.shadow) { b -> actions.apply(clip.id, "Text shadow") { it.copy(text = it.text?.copy(shadow = b)) } }
    }
}

@Composable
private fun FontTile(font: FontChoice, selected: Boolean, onClick: () -> Unit) {
    val c = palette
    Column(
        Modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.accentSoft else c.panel)
            .border(1.dp, if (selected) c.accent else c.hairline, RoundedCornerShape(10.dp))
            .clickable(role = Role.RadioButton, onClickLabel = font.name, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Aa", fontFamily = font.family, fontSize = 20.sp, color = if (selected) c.accent else c.textPrimary, maxLines = 1)
        Text(
            font.name,
            style = MaterialTheme.typography.labelSmall,
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = palette
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.panelHigh, uncheckedBorderColor = c.outline),
        )
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
        Text("Select a clip on the timeline or tap it in the preview to edit it.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
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
private fun signedPercent(v: Float) = String.format(Locale.ROOT, "%+d", (v * 100).roundToInt())
private fun signed2(v: Float) = String.format(Locale.ROOT, "%+.2f", v)
private fun seconds(us: Long) = String.format(Locale.ROOT, "%.1fs", us / 1_000_000f)
private fun formatSpeed(s: Float) = if (s == s.toInt().toFloat()) "${s.toInt()}×" else "${s}×"
