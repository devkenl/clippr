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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.clippr.core.model.Clip
import app.clippr.core.model.EffectCategory
import app.clippr.core.model.StyleEffectType
import app.clippr.core.model.StyleEffects
import app.clippr.core.model.VisualEffects
import app.clippr.core.ui.components.FilterChip
import app.clippr.core.ui.components.LabeledSlider
import app.clippr.core.ui.components.PanelSection
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.palette
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Effects tab: effects already on the clip (with their strength), then a browser of every
 * effect grouped by category. Tapping a tile adds the effect; tapping it again removes it.
 */
@Composable
internal fun EffectsTab(clip: Clip, actions: InspectorActions) {
    val c = palette
    val fx = clip.effects
    fun live(label: String, f: (VisualEffects) -> VisualEffects) = actions.onLive(clip.id, label) { it.copy(effects = f(it.effects)) }

    val basicActive = fx.blur > 0f || fx.vignette > 0f || fx.grain > 0f
    if (fx.styles.isNotEmpty()) {
        PanelSection("Applied effects", ClipprIcons.Sparkle, initiallyExpanded = true) {
            fx.styles.forEach { applied ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledSlider(
                        applied.type.label,
                        applied.amount,
                        { v -> live(applied.type.label) { StyleEffects.setAmount(it, applied.type, v) } },
                        actions.onCommit,
                        0f..1f,
                        ::percent,
                        modifier = Modifier.weight(1f),
                        defaultValue = applied.type.defaultAmount,
                    )
                    Box(
                        Modifier
                            .padding(start = Spacing.sm)
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Remove ${applied.type.label}") {
                                actions.apply(clip.id, "Remove ${applied.type.label}") { it.copy(effects = StyleEffects.remove(it.effects, applied.type)) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(ClipprIcons.Close, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
    PanelSection("Blur, vignette & grain", ClipprIcons.Sliders, initiallyExpanded = false, trailing = { if (basicActive) ActiveDot() }) {
        LabeledSlider("Blur", fx.blur, { v -> live("Blur") { it.copy(blur = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 0f)
        LabeledSlider("Vignette", fx.vignette, { v -> live("Vignette") { it.copy(vignette = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 0f)
        LabeledSlider("Film grain", fx.grain, { v -> live("Film grain") { it.copy(grain = v) } }, actions.onCommit, 0f..1f, ::percent, defaultValue = 0f)
    }

    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val category = EffectCategory.entries.firstOrNull { it.name == categoryName }
    Column(Modifier.fillMaxWidth().padding(Spacing.lg)) {
        Text("Add effects", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        Spacer(Modifier.height(2.dp))
        Text("Tap to add, tap again to remove. Effects stack in the order you add them.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterChip("All", category == null, { categoryName = null })
            EffectCategory.entries.forEach { cat -> FilterChip(cat.label, category == cat, { categoryName = cat.name }) }
        }
        Spacer(Modifier.height(Spacing.md))
        val shown = StyleEffectType.entries.filter { category == null || it.category == category }
        shown.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                row.forEach { type ->
                    val active = fx.styles.any { it.type == type }
                    EffectTile(type, active, Modifier.weight(1f)) {
                        actions.apply(clip.id, if (active) "Remove ${type.label}" else "Add ${type.label}") { it.copy(effects = StyleEffects.toggle(it.effects, type)) }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

@Composable
private fun EffectTile(type: StyleEffectType, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = palette
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Switch, onClickLabel = if (active) "Remove ${type.label}" else "Add ${type.label}", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .clip(RoundedCornerShape(10.dp))
                .border(if (active) 2.dp else 1.dp, if (active) c.accent else c.hairline, RoundedCornerShape(10.dp)),
        ) {
            EffectSwatch(type, Modifier.fillMaxSize())
            if (active) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(c.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ClipprIcons.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(12.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            type.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) c.accent else c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A small illustration of what an effect does, drawn over the same simple scene (sky, sun,
 * hills). These are illustrations, not renders of the clip.
 */
@Composable
private fun EffectSwatch(type: StyleEffectType, modifier: Modifier) {
    Canvas(modifier) {
        when (type) {
            StyleEffectType.MIRROR -> {
                clipRect(right = size.width / 2) { scene() }
                translate(left = size.width) { scale(-1f, 1f, pivot = Offset.Zero) { clipRect(right = size.width / 2) { scene() } } }
            }
            StyleEffectType.FISHEYE -> {
                scene()
                drawCircle(Color.Black.copy(alpha = 0.55f), radius = size.minDimension * 0.9f, style = Stroke(size.minDimension * 0.5f))
            }
            StyleEffectType.SHAKE -> {
                translate(left = -3.dp.toPx(), top = 2.dp.toPx()) { scene(alpha = 0.5f) }
                translate(left = 3.dp.toPx(), top = -2.dp.toPx()) { scene(alpha = 0.6f) }
            }
            else -> scene()
        }
        when (type) {
            StyleEffectType.SHARPEN -> drawPath(hillPath(), Color.White.copy(alpha = 0.8f), style = Stroke(1.5.dp.toPx()))
            StyleEffectType.GLOW -> drawCircle(
                Brush.radialGradient(listOf(Color(0xCCFFE8B0), Color.Transparent), center = sun(), radius = size.minDimension * 0.6f),
                radius = size.minDimension * 0.6f,
                center = sun(),
            )
            StyleEffectType.LIGHT_LEAK -> drawRect(
                Brush.linearGradient(listOf(Color(0xAAFF7A3D), Color(0x55FFD36E), Color.Transparent), start = Offset.Zero, end = Offset(size.width * 0.8f, size.height)),
            )
            StyleEffectType.ZOOM_BLUR -> {
                val center = Offset(size.width / 2, size.height / 2)
                for (i in 0 until 16) {
                    val a = i * Math.PI / 8
                    val d = Offset(cos(a).toFloat(), sin(a).toFloat())
                    drawLine(Color.White.copy(alpha = 0.25f), center + d * (size.minDimension * 0.25f), center + d * size.maxDimension, 2.dp.toPx())
                }
            }
            StyleEffectType.CHROMATIC -> {
                drawPath(hillPath(), Color(0x88FF3B5C), style = Stroke(2.dp.toPx()))
                translate(left = 3.dp.toPx()) { drawPath(hillPath(), Color(0x8836D1FF), style = Stroke(2.dp.toPx())) }
            }
            StyleEffectType.PIXELATE -> {
                val step = size.width / 8
                var x = 0f
                while (x < size.width) {
                    drawLine(Color.Black.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(Color.Black.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += step
                }
            }
            StyleEffectType.POSTERIZE -> {
                val bands = listOf(Color(0x33000000), Color(0x00000000), Color(0x22FFFFFF))
                bands.forEachIndexed { i, b -> drawRect(b, topLeft = Offset(0f, size.height * i / 3f), size = Size(size.width, size.height / 3f)) }
            }
            StyleEffectType.SKETCH -> {
                drawRect(Color(0xE6F2F0EA))
                drawPath(hillPath(), Color(0xFF2B2B2B), style = Stroke(1.5.dp.toPx()))
                drawCircle(Color(0xFF2B2B2B), radius = size.minDimension * 0.12f, center = sun(), style = Stroke(1.5.dp.toPx()))
            }
            StyleEffectType.VHS -> {
                var y = 0f
                while (y < size.height) {
                    drawLine(Color.Black.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += 3.dp.toPx()
                }
                drawRect(Color(0x33FF00FF), topLeft = Offset(0f, size.height * 0.62f), size = Size(size.width, 3.dp.toPx()))
            }
            StyleEffectType.OLD_FILM -> {
                drawRect(Color(0x66704214))
                drawLine(Color.White.copy(alpha = 0.6f), Offset(size.width * 0.3f, 0f), Offset(size.width * 0.32f, size.height), 1f)
                drawLine(Color.White.copy(alpha = 0.4f), Offset(size.width * 0.75f, 0f), Offset(size.width * 0.74f, size.height * 0.6f), 1f)
            }
            StyleEffectType.GLITCH -> {
                drawRect(Color(0x99FF2D75), topLeft = Offset(size.width * 0.1f, size.height * 0.3f), size = Size(size.width * 0.5f, 4.dp.toPx()))
                drawRect(Color(0x9933E0FF), topLeft = Offset(size.width * 0.4f, size.height * 0.55f), size = Size(size.width * 0.55f, 5.dp.toPx()))
                drawRect(Color(0x66FFFFFF), topLeft = Offset(0f, size.height * 0.75f), size = Size(size.width * 0.3f, 3.dp.toPx()))
            }
            else -> Unit
        }
    }
}

private fun DrawScope.sun() = Offset(size.width * 0.7f, size.height * 0.32f)

private fun DrawScope.hillPath(): Path = Path().apply {
    moveTo(0f, size.height * 0.72f)
    quadraticTo(size.width * 0.25f, size.height * 0.48f, size.width * 0.5f, size.height * 0.68f)
    quadraticTo(size.width * 0.75f, size.height * 0.86f, size.width, size.height * 0.58f)
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}

private fun DrawScope.scene(alpha: Float = 1f) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF3B2A6E).copy(alpha = alpha), Color(0xFFB57BDB).copy(alpha = alpha), Color(0xFFF2B38A).copy(alpha = alpha))))
    drawCircle(Color(0xFFFFE6B3).copy(alpha = alpha), radius = size.minDimension * 0.12f, center = sun())
    drawPath(hillPath(), Color(0xFF1E1838).copy(alpha = alpha))
}
