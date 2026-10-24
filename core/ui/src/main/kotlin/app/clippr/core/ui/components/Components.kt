package app.clippr.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Radii
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.palette

/** The Clippr mark: a play shape cut by a diagonal edit line. */
@Composable
fun ClipprLogo(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val accent = palette.accent
    val cutColor = palette.background
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val tile = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, w * 0.28f, h * 0.28f),
            )
        }
        drawPath(tile, color = accent.copy(alpha = 0.16f))
        val tri = Path().apply {
            moveTo(w * 0.36f, h * 0.24f)
            lineTo(w * 0.78f, h * 0.5f)
            lineTo(w * 0.36f, h * 0.76f)
            close()
        }
        drawPath(tri, color = accent)
        drawLine(
            color = cutColor,
            start = Offset(w * 0.30f, h * 0.70f),
            end = Offset(w * 0.70f, h * 0.30f),
            strokeWidth = w * 0.07f,
            cap = StrokeCap.Round,
        )
    }
}

/** Square icon button used in toolbars. Keeps a 44dp minimum touch target. */
@Composable
fun ToolButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color? = null,
    size: Dp = 44.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val c = palette
    val bg = when {
        selected -> c.accentSoft
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> c.textTertiary.copy(alpha = 0.5f)
        tint != null -> tint
        selected -> c.accent
        else -> c.textPrimary
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(Radii.sm))
            .background(bg)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(22.dp))
    }
}

/** Labelled tool for the phone tool drawer: icon above a short label. */
@Composable
fun DrawerTool(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val c = palette
    val fg = when {
        !enabled -> c.textTertiary.copy(alpha = 0.5f)
        selected -> c.accent
        else -> c.textPrimary
    }
    Column(
        modifier
            .width(68.dp)
            .clip(RoundedCornerShape(Radii.md))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = palette
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) c.accent else c.panelHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (enabled) c.onAccent else c.textTertiary
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val c = palette
    val fg = when {
        !enabled -> c.textTertiary
        destructive -> c.danger
        else -> c.textPrimary
    }
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(50))
            .background(c.panel)
            .border(1.dp, c.hairline, RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** A collapsible inspector section. Expansion state survives configuration changes. */
@Composable
fun PanelSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val c = palette
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse $title" else "Expand $title") { expanded = !expanded }
                .padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
            trailing()
            Icon(
                ClipprIcons.ChevronDown,
                contentDescription = null,
                tint = c.textTertiary,
                modifier = Modifier.size(18.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md)) { content() }
        }
        Hairline()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(palette.hairline))
}

/**
 * Slider with a label and formatted value. [onValueChange] fires continuously for live preview;
 * [onCommit] fires once when the gesture ends, which is when an undo step should be recorded.
 * Double-tapping the label resets to [defaultValue].
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    defaultValue: Float? = null,
    enabled: Boolean = true,
) {
    val c = palette
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled && defaultValue != null,
                        onClick = {},
                        onDoubleClick = {
                            if (defaultValue != null) {
                                onValueChange(defaultValue)
                                onCommit()
                            }
                        },
                    ),
            )
            Text(format(value), style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onCommit,
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = c.textPrimary,
                activeTrackColor = c.accent,
                inactiveTrackColor = c.panelHigh,
                disabledActiveTrackColor = c.textTertiary,
                disabledInactiveTrackColor = c.panel,
            ),
            modifier = Modifier.height(36.dp),
        )
    }
}

/** Pill-shaped single choice control. */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
) {
    val c = palette
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(c.panel)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val isEnabled = enabled(option)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) c.panelHigh else Color.Transparent)
                    .clickable(enabled = isEnabled, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        !isEnabled -> c.textTertiary.copy(alpha = 0.6f)
                        isSelected -> c.textPrimary
                        else -> c.textSecondary
                    },
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A row of pill tabs (selected tab on a raised pill), scrollable when it doesn't fit. Used for
 * the inspector tabs and the Home navigation.
 */
@Composable
fun PillTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector?> = emptyList(),
    compact: Boolean = false,
) {
    val c = palette
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(50))
            .background(c.surface)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, label ->
            val isSelected = i == selected
            Row(
                Modifier
                    .heightIn(min = if (compact) 34.dp else 40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) c.panelHigh else Color.Transparent)
                    .clickable(role = Role.Tab, onClickLabel = label) { onSelect(i) }
                    .padding(horizontal = if (compact) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icons.getOrNull(i)?.let {
                    Icon(it, contentDescription = null, tint = if (isSelected) c.accent else c.textSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) c.textPrimary else c.textSecondary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Small selectable chip used for filters. */
@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = palette
    Box(
        modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.accentSoft else c.panel)
            .border(1.dp, if (selected) c.accent.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(50))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) c.accent else c.textSecondary)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = palette
    Column(
        modifier.padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(c.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.xs))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(Spacing.lg))
            action()
        }
    }
}

/** Circular progress ring with an exact fraction (no indeterminate animation is faked). */
@Composable
fun ProgressRing(progress: Float?, modifier: Modifier = Modifier, size: Dp = 120.dp, stroke: Dp = 8.dp) {
    val c = palette
    val p by animateFloatAsState((progress ?: 0f).coerceIn(0f, 1f), label = "progress")
    Canvas(modifier.size(size)) {
        val s = stroke.toPx()
        drawArc(c.panelHigh, 0f, 360f, false, style = Stroke(s), topLeft = Offset(s / 2, s / 2), size = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s))
        if (progress != null) {
            drawArc(c.accent, -90f, 360f * p, false, style = Stroke(s, cap = StrokeCap.Round), topLeft = Offset(s / 2, s / 2), size = androidx.compose.ui.geometry.Size(this.size.width - s, this.size.height - s))
        }
    }
}

val DefaultContentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md)
