package app.clippr.feature.inspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.clippr.core.model.ChromaKey
import app.clippr.core.model.Clip
import app.clippr.core.model.Crop
import app.clippr.core.model.Easing
import app.clippr.core.model.FitMode
import app.clippr.core.model.MaskShape
import app.clippr.core.model.Project
import app.clippr.core.model.Transform
import app.clippr.core.model.TransitionMath
import app.clippr.core.model.TransitionType
import app.clippr.core.model.edit.Overlaps
import app.clippr.core.model.edit.SetTransitionDuration
import app.clippr.core.model.time.US_PER_SECOND
import app.clippr.core.ui.components.FilterChip
import app.clippr.core.ui.components.LabeledSlider
import app.clippr.core.ui.components.PanelSection
import app.clippr.core.ui.components.PrimaryButton
import app.clippr.core.ui.components.SecondaryButton
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.palette
import java.util.Locale

/** A clip on a video track that has a clip right before it, so it can transition from it. */
internal class TransitionContext(val clip: Clip, val previous: Clip, val overlapUs: Long, val maxUs: Long, val previousName: String) {
    val hasTransition: Boolean get() = overlapUs > 0

    companion object {
        fun of(project: Project, clip: Clip): TransitionContext? {
            val track = project.sequence.trackOfClip(clip.id) ?: return null
            if (!track.allowsOverlap) return null
            val index = track.clips.indexOfFirst { it.id == clip.id }
            val prev = track.clips.getOrNull(index - 1) ?: return null
            val overlap = Overlaps.overlapIn(track.clips, index)
            val max = minOf(
                Overlaps.maxOverlap(prev, clip),
                prev.durationUs - Overlaps.overlapIn(track.clips, index - 1) - 1,
                clip.durationUs / 2 - Overlaps.overlapOut(track.clips, index).coerceAtMost(clip.durationUs / 2),
            ).coerceAtLeast(0)
            val name = if (prev.isText) "title" else project.asset(prev.assetId)?.displayName ?: "previous clip"
            return TransitionContext(clip, prev, overlap, max, name)
        }
    }
}

private val TransitionEasings = listOf(Easing.EASE_IN_OUT to "Smooth", Easing.LINEAR to "Linear", Easing.EASE_IN to "Ease in", Easing.EASE_OUT to "Ease out")

/**
 * The Transition tab. Like Vegas Pro, a transition is the overlap between this clip and the
 * previous one: drag the clip over its neighbour on the timeline, or set the length here.
 */
@Composable
internal fun TransitionTab(ctx: TransitionContext, actions: InspectorActions) {
    val c = palette
    val clip = ctx.clip
    Column(Modifier.fillMaxWidth().padding(Spacing.lg)) {
        Text(
            if (ctx.hasTransition) "From “${ctx.previousName}”" else "No transition yet",
            style = MaterialTheme.typography.titleSmall,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (ctx.hasTransition) {
                "The overlap with the previous clip is the transition. Drag the clip on the timeline or use the slider to change its length."
            } else {
                "Drag this clip over the end of the previous one on the timeline, or add one here. Clips after it move left by the transition's length."
            },
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(Spacing.md))
        if (!ctx.hasTransition) {
            val oneSecond = US_PER_SECOND.coerceAtMost(ctx.maxUs)
            PrimaryButton(
                if (ctx.maxUs <= 0) "Clips too short for a transition" else "Add ${seconds(oneSecond)} cross dissolve",
                onClick = { actions.onCommand(SetTransitionDuration(clip.id, oneSecond)) },
                icon = ClipprIcons.Transition,
                enabled = ctx.maxUs > 0,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }
        // Length: edited locally while dragging, applied once on release (it moves clips).
        var length by remember(clip.id, ctx.overlapUs) { mutableFloatStateOf(ctx.overlapUs.toFloat()) }
        LaunchedEffect(ctx.overlapUs) { length = ctx.overlapUs.toFloat() }
        LabeledSlider(
            "Length",
            length,
            { length = it },
            { actions.onCommand(SetTransitionDuration(clip.id, length.toLong().coerceAtLeast(MIN_TRANSITION_US))) },
            MIN_TRANSITION_US.toFloat()..ctx.maxUs.coerceAtLeast(MIN_TRANSITION_US + 1).toFloat(),
            { seconds(it.toLong()) },
        )
        Spacer(Modifier.height(Spacing.sm))
        Text("Motion", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TransitionEasings.forEach { (e, label) ->
                FilterChip(label, clip.transition.easing == e, {
                    actions.apply(clip.id, "Transition easing") { it.copy(transition = it.transition.copy(easing = e)) }
                })
            }
        }
        Spacer(Modifier.height(Spacing.md))
        TransitionType.entries.groupBy { it.group }.forEach { (group, types) ->
            Text(group, style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            Spacer(Modifier.height(Spacing.xs))
            types.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    row.forEach { type ->
                        TransitionTile(type, clip.transition.type == type, Modifier.weight(1f)) {
                            actions.apply(clip.id, "Transition: ${type.label}") { it.copy(transition = it.transition.copy(type = type)) }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(Spacing.sm))
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton(
            "Remove transition",
            onClick = { actions.onCommand(SetTransitionDuration(clip.id, 0)) },
            icon = ClipprIcons.Close,
            destructive = true,
        )
    }
}

private const val MIN_TRANSITION_US = 100_000L

/** A tile previewing a transition halfway through: outgoing clip in grey-blue, incoming in violet. */
@Composable
private fun TransitionTile(type: TransitionType, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = palette
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.RadioButton, onClickLabel = type.label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(10.dp))
                .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.hairline, RoundedCornerShape(10.dp)),
        ) {
            Canvas(Modifier.fillMaxSize()) { drawTransitionPreview(type, 0.5f) }
        }
        Spacer(Modifier.height(4.dp))
        Text(type.label, style = MaterialTheme.typography.labelSmall, color = if (selected) c.accent else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val OutgoingColor = Color(0xFF4B5A78)
private val IncomingColor = Color(0xFF8B6CF6)

/** Draws a transition at progress [p] with two flat-coloured "clips" (incoming on top). */
private fun DrawScope.drawTransitionPreview(type: TransitionType, p: Float) {
    val w = size.width
    val h = size.height
    drawRect(Color.Black)
    fun frame(color: Color, look: app.clippr.core.model.LayerLook) {
        val cx = w / 2 * (1 + look.offsetX)
        val cy = h / 2 * (1 - look.offsetY)
        val fw = w * look.scale
        val fh = h * look.scale
        drawRect(color.copy(alpha = look.alpha.coerceIn(0f, 1f)), Offset(cx - fw / 2, cy - fh / 2), Size(fw, fh))
        // A small "sun" so motion and zoom are visible.
        drawCircle(Color.White.copy(alpha = 0.5f * look.alpha), radius = fh * 0.12f, center = Offset(cx + fw * 0.2f, cy - fh * 0.15f))
    }
    frame(OutgoingColor, TransitionMath.outgoing(type, p, incomingOnTop = true))
    val mask = TransitionMath.mask(type, isIncoming = true, incomingOnTop = true)
    if (mask == null) {
        frame(IncomingColor, TransitionMath.incoming(type, p, incomingOnTop = true))
    } else {
        when (mask.shape) {
            MaskShape.LEFT -> clipRect(left = w * (1 - p)) { frame(IncomingColor, TransitionMath.incoming(type, p, true)) }
            MaskShape.RIGHT -> clipRect(right = w * p) { frame(IncomingColor, TransitionMath.incoming(type, p, true)) }
            MaskShape.UP -> clipRect(top = h * (1 - p)) { frame(IncomingColor, TransitionMath.incoming(type, p, true)) }
            MaskShape.DOWN -> clipRect(bottom = h * p) { frame(IncomingColor, TransitionMath.incoming(type, p, true)) }
            MaskShape.CIRCLE -> {
                val r = p * kotlin.math.hypot(w / 2, h / 2)
                drawCircle(IncomingColor, radius = r, center = Offset(w / 2, h / 2))
            }
        }
    }
}

// Layout presets (picture in picture, split screen) ------------------------------------------------

private enum class LayoutPreset(val label: String) {
    FULL("Full"),
    PIP_TOP_RIGHT("Top right"),
    PIP_TOP_LEFT("Top left"),
    PIP_BOTTOM_RIGHT("Bottom right"),
    PIP_BOTTOM_LEFT("Bottom left"),
    SPLIT_LEFT("Left half"),
    SPLIT_RIGHT("Right half"),
}

/**
 * One-tap placements for clips on upper layers: picture-in-picture corners and split screen.
 * They set the clip's transform (and crop for split screen), which stays editable below.
 */
@Composable
internal fun LayoutSection(project: Project, clip: Clip, actions: InspectorActions) {
    val track = project.sequence.trackOfClip(clip.id) ?: return
    val isOverlay = track.kind == app.clippr.core.model.TrackKind.VIDEO && track.id != project.sequence.mainVideoTrack?.id
    PanelSection("Layout", ClipprIcons.PictureInPicture, initiallyExpanded = isOverlay) {
        Text(
            if (isOverlay) {
                "This clip is on an upper layer and plays over the video beneath it."
            } else {
                "Tip: add clips as overlays (picture-in-picture button in Media) to layer them over this one."
            },
            style = MaterialTheme.typography.bodySmall,
            color = palette.textTertiary,
        )
        Spacer(Modifier.height(Spacing.sm))
        LayoutPreset.entries.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                row.forEach { preset ->
                    LayoutTile(preset, Modifier.weight(1f)) {
                        actions.apply(clip.id, "Layout: ${preset.label}") { it.copy(transform = presetTransform(preset, it.transform), keyframes = emptyList()) }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}

private fun presetTransform(preset: LayoutPreset, current: Transform): Transform {
    val pip = 0.38f
    val margin = 0.06f
    val edge = 1f - pip - margin
    return when (preset) {
        LayoutPreset.FULL -> Transform(fit = current.fit, flipHorizontal = current.flipHorizontal, flipVertical = current.flipVertical)
        LayoutPreset.PIP_TOP_RIGHT -> current.copy(scale = pip, positionX = edge, positionY = edge, rotationDegrees = 0f, crop = Crop.NONE, fit = FitMode.FIT)
        LayoutPreset.PIP_TOP_LEFT -> current.copy(scale = pip, positionX = -edge, positionY = edge, rotationDegrees = 0f, crop = Crop.NONE, fit = FitMode.FIT)
        LayoutPreset.PIP_BOTTOM_RIGHT -> current.copy(scale = pip, positionX = edge, positionY = -edge, rotationDegrees = 0f, crop = Crop.NONE, fit = FitMode.FIT)
        LayoutPreset.PIP_BOTTOM_LEFT -> current.copy(scale = pip, positionX = -edge, positionY = -edge, rotationDegrees = 0f, crop = Crop.NONE, fit = FitMode.FIT)
        // Keep the middle half of the picture (half as wide, so it fits half the canvas), then
        // shift it to one side.
        LayoutPreset.SPLIT_LEFT -> current.copy(scale = 1f, positionX = -0.5f, positionY = 0f, rotationDegrees = 0f, fit = FitMode.FIT, crop = Crop(left = 0.25f, right = 0.25f))
        LayoutPreset.SPLIT_RIGHT -> current.copy(scale = 1f, positionX = 0.5f, positionY = 0f, rotationDegrees = 0f, fit = FitMode.FIT, crop = Crop(left = 0.25f, right = 0.25f))
    }
}

@Composable
private fun LayoutTile(preset: LayoutPreset, modifier: Modifier, onClick: () -> Unit) {
    val c = palette
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = preset.label, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)).background(c.panel)) {
            val w = size.width
            val h = size.height
            val inner = c.accent
            when (preset) {
                LayoutPreset.FULL -> drawRect(inner)
                LayoutPreset.SPLIT_LEFT -> drawRect(inner, Offset.Zero, Size(w / 2, h))
                LayoutPreset.SPLIT_RIGHT -> drawRect(inner, Offset(w / 2, 0f), Size(w / 2, h))
                else -> {
                    val pw = w * 0.38f
                    val ph = h * 0.38f
                    val m = w * 0.06f
                    val x = if (preset == LayoutPreset.PIP_TOP_LEFT || preset == LayoutPreset.PIP_BOTTOM_LEFT) m else w - pw - m
                    val y = if (preset == LayoutPreset.PIP_TOP_LEFT || preset == LayoutPreset.PIP_TOP_RIGHT) m * h / w else h - ph - m * h / w
                    drawRect(inner, Offset(x, y), Size(pw, ph))
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(preset.label, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// Chroma key -------------------------------------------------------------------------------------

private val KeyColors = listOf(ChromaKey.GREEN to "Green", ChromaKey.BLUE to "Blue")

/** Green/blue screen removal, in the Effects tab. */
@Composable
internal fun ChromaKeySection(clip: Clip, actions: InspectorActions) {
    val c = palette
    val key = clip.effects.chromaKey
    fun live(label: String, f: (ChromaKey) -> ChromaKey) =
        actions.onLive(clip.id, label) { it.copy(effects = it.effects.copy(chromaKey = f(it.effects.chromaKey ?: ChromaKey()))) }
    PanelSection("Chroma key", ClipprIcons.ColorWheel, initiallyExpanded = false, trailing = { if (key != null) ActiveDot() }) {
        Text(
            "Removes a green or blue background. Put this clip on an upper layer to show the video beneath it.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            FilterChip("Off", key == null, { actions.apply(clip.id, "Chroma key off") { it.copy(effects = it.effects.copy(chromaKey = null)) } })
            KeyColors.forEach { (argb, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(label, key?.colorArgb == argb, {
                        actions.apply(clip.id, "Chroma key $label") { it.copy(effects = it.effects.copy(chromaKey = (it.effects.chromaKey ?: ChromaKey()).copy(colorArgb = argb))) }
                    })
                }
            }
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(key?.colorArgb ?: ChromaKey.GREEN)))
        }
        if (key != null) {
            Spacer(Modifier.height(Spacing.sm))
            LabeledSlider("Strength", key.similarity, { v -> live("Key strength") { it.copy(similarity = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 0.4f)
            LabeledSlider("Edge softness", key.smoothness, { v -> live("Key softness") { it.copy(smoothness = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 0.1f)
            LabeledSlider("Spill removal", key.spill, { v -> live("Spill removal") { it.copy(spill = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 0.5f)
        }
    }
}

private fun seconds(us: Long) = String.format(Locale.ROOT, "%.2fs", us / 1_000_000f).replace(".00s", "s")
