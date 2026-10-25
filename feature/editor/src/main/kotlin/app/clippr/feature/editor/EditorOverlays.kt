package app.clippr.feature.editor

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clippr.core.ui.components.PrimaryButton
import app.clippr.core.ui.components.SecondaryButton
import app.clippr.core.ui.components.ToolButton
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.TimecodeStyle
import app.clippr.core.ui.theme.palette
import java.util.Locale

/**
 * Voiceover recorder. Recording starts at the playhead; the timeline plays (muted) while you
 * speak, and the take is placed on a free audio track when you stop.
 */
@Composable
internal fun VoiceoverCard(
    state: VoiceoverState,
    timecode: String,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = palette
    val busy = state.recording || state.countdown > 0
    Column(
        modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .border(1.dp, c.hairline, RoundedCornerShape(18.dp))
            .padding(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ClipprIcons.Mic, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("Record voiceover", style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
            ToolButton(ClipprIcons.Close, if (state.recording) "Discard recording" else "Close", onClose, size = 36.dp)
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            when {
                state.countdown > 0 -> "Get ready…"
                state.recording -> "Recording from $timecode. Speak while the video plays; preview sound is muted."
                else -> "Starts at the playhead ($timecode). The video plays muted while you record."
            },
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(Spacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Big record / stop button.
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (busy) c.panelHigh else c.danger)
                    .clickable(role = Role.Button, onClickLabel = if (busy) "Stop recording" else "Start recording") { if (busy) onStop() else onRecord() }
                    .semantics { contentDescription = if (busy) "Stop recording" else "Start recording" },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.countdown > 0 -> Text("${state.countdown}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    state.recording -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(c.danger))
                    else -> Box(Modifier.size(26.dp).clip(CircleShape).background(Color.White))
                }
            }
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.weight(1f)) {
                Text(
                    formatElapsed(state.elapsedMs),
                    style = TimecodeStyle.copy(fontSize = 22.sp),
                    color = if (state.recording) c.textPrimary else c.textTertiary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(6.dp))
                LevelMeter(if (state.recording) state.level else 0f)
            }
        }
        state.error?.let {
            Spacer(Modifier.height(Spacing.sm))
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger)
        }
    }
}

@Composable
private fun LevelMeter(level: Float) {
    val c = palette
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val bars = 24
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (bars - 1)) / bars
        val lit = (level * bars * 1.4f).toInt()
        for (i in 0 until bars) {
            val color = when {
                i >= lit -> c.panelHigh
                i > bars * 0.85f -> c.danger
                i > bars * 0.65f -> c.warning
                else -> c.success
            }
            drawRoundRect(color, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    return String.format(Locale.ROOT, "%d:%02d.%d", total / 60, total % 60, (ms % 1000) / 100)
}

/** Offered for projects whose media still streams from a cloud app (made before 0.2.0). */
@Composable
internal fun CloudMediaBanner(count: Int, busy: Boolean, status: String?, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val c = palette
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.panel)
            .border(1.dp, c.warning.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ClipprIcons.Cloud, contentDescription = null, tint = c.warning, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(
            status ?: "$count file${if (count == 1) " streams" else "s stream"} from a cloud app, which can stop playback and export. Copy ${if (count == 1) "it" else "them"} to this device.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.sm))
        if (busy) {
            CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            PrimaryButton("Copy", onCopy)
        }
    }
}

/** Shown over the preview when playback failed, with the reason and a way to try again. */
@Composable
internal fun PreviewErrorCard(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRestart: (() -> Unit)? = null,
) {
    val c = palette
    Column(
        modifier
            .widthIn(max = 460.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface.copy(alpha = 0.96f))
            .border(1.dp, c.danger.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(Spacing.md)
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(ClipprIcons.Warning, contentDescription = null, tint = c.danger, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(message, style = MaterialTheme.typography.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.align(Alignment.End)) {
            SecondaryButton("Dismiss", onDismiss)
            if (onRestart != null) {
                SecondaryButton("Try again", onRetry)
                PrimaryButton("Restart Clippr", onRestart, icon = ClipprIcons.Retry)
            } else {
                PrimaryButton("Try again", onRetry, icon = ClipprIcons.Retry)
            }
        }
    }
}

/** A quiet note over the preview, e.g. that transitions preview as cuts on this device. */
@Composable
internal fun PreviewNoticeChip(text: String, modifier: Modifier = Modifier) {
    val c = palette
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(c.panel.copy(alpha = 0.92f))
            .padding(horizontal = Spacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ClipprIcons.Info, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.textPrimary, maxLines = 2)
    }
}

/** Progress of an import or copy when no banner is showing it. */
@Composable
internal fun ImportStatusChip(text: String, modifier: Modifier = Modifier) {
    val c = palette
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(c.panel)
            .padding(horizontal = Spacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.textPrimary, maxLines = 1)
    }
}
