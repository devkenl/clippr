package app.clippr.feature.timeline

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clippr.core.model.Clip
import app.clippr.core.model.MediaType
import app.clippr.core.model.Project
import app.clippr.core.model.Track
import app.clippr.core.model.TrackKind
import app.clippr.core.model.edit.EditCommand
import app.clippr.core.model.edit.MoveClip
import app.clippr.core.model.edit.Snapping
import app.clippr.core.model.edit.TrimClipEnd
import app.clippr.core.model.edit.TrimClipStart
import app.clippr.core.model.time.Timecode
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.ClipprColors
import app.clippr.core.ui.theme.TimecodeStyle
import app.clippr.core.ui.theme.palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Size metrics for the timeline; tablets get roomier rows than phones. */
@Immutable
data class TimelineMetrics(
    val headerWidth: Dp,
    val rulerHeight: Dp,
    val videoRow: Dp,
    val audioRow: Dp,
    val textRow: Dp,
    val compactHeaders: Boolean,
) {
    fun rowHeight(kind: TrackKind): Dp = when (kind) {
        TrackKind.VIDEO -> videoRow
        TrackKind.AUDIO -> audioRow
        TrackKind.TEXT -> textRow
    }

    companion object {
        val Tablet = TimelineMetrics(headerWidth = 148.dp, rulerHeight = 30.dp, videoRow = 72.dp, audioRow = 52.dp, textRow = 40.dp, compactHeaders = false)
        val Phone = TimelineMetrics(headerWidth = 52.dp, rulerHeight = 28.dp, videoRow = 60.dp, audioRow = 46.dp, textRow = 36.dp, compactHeaders = true)
    }
}

/** An in-progress drag. The timeline renders [command] applied to the project while dragging. */
private sealed interface DragOp {
    val snapLineUs: Long?
    fun command(): EditCommand

    data class Move(val clipId: String, val trackId: String, val startUs: Long, override val snapLineUs: Long?) : DragOp {
        override fun command() = MoveClip(clipId, trackId, startUs)
    }

    data class TrimStart(val clipId: String, val timeUs: Long, override val snapLineUs: Long?) : DragOp {
        override fun command() = TrimClipStart(clipId, timeUs)
    }

    data class TrimEnd(val clipId: String, val timeUs: Long, override val snapLineUs: Long?) : DragOp {
        override fun command() = TrimClipEnd(clipId, timeUs)
    }
}

private data class TrackRow(val track: Track, val topPx: Float, val heightPx: Float)

private sealed interface Hit {
    data class Empty(val row: TrackRow?) : Hit
    data class OnClip(val clip: Clip, val row: TrackRow) : Hit
    data class TrimHandle(val clip: Clip, val row: TrackRow, val start: Boolean) : Hit
}

private sealed interface Outcome {
    data class Tap(val change: PointerInputChange) : Outcome
    data class Drag(val change: PointerInputChange) : Outcome
    data object Pinch : Outcome
    data object LongPress : Outcome
    data object Cancel : Outcome
}

/**
 * The editing timeline: ruler, track headers and tracks.
 *
 * Interaction model (touch first, mouse and keyboard friendly):
 * - Tap a clip to select it; Shift/Ctrl+tap adds to the selection; tap empty space to deselect.
 * - Drag a selected clip, or long-press any clip, to move it (also between compatible tracks).
 * - Drag the handles of a selected clip to trim. Both snap to clip edges and the playhead.
 * - One finger on empty space scrolls with fling; two fingers pan and pinch-zoom.
 * - Mouse wheel scrolls; Ctrl+wheel zooms around the pointer.
 * - Tap or drag on the ruler to move the playhead. Double-tap empty track space to fit.
 */
@Composable
fun Timeline(
    project: Project,
    playheadUs: Long,
    isPlaying: Boolean,
    selectedClipIds: Set<String>,
    missingAssetIds: Set<String>,
    snappingEnabled: Boolean,
    viewport: TimelineViewport,
    media: TimelineMediaCache,
    metrics: TimelineMetrics,
    onSeek: (Long) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    onSelect: (clipId: String?, additive: Boolean) -> Unit,
    onEdit: (EditCommand) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onToggleTrackLock: (String) -> Unit,
    onAddAudioTrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = palette
    val density = LocalDensity.current
    val vScroll = rememberScrollState()
    var drag by remember { mutableStateOf<DragOp?>(null) }
    val displayProject = remember(project, drag) { drag?.command()?.apply(project) ?: project }

    val rows = remember(displayProject.sequence.tracks.map { it.id to it.kind }, metrics, density) {
        var top = 0f
        displayProject.sequence.tracks.map { t ->
            val h = with(density) { metrics.rowHeight(t.kind).toPx() }
            TrackRow(t, top, h).also { top += h }
        }
    }
    // Rows carry the latest track content (clips) while keeping layout stable.
    val liveRows = rows.map { r -> r.copy(track = displayProject.sequence.track(r.track.id) ?: r.track) }
    val addRowHeight = 44.dp
    val contentHeight = with(density) { (liveRows.sumOf { it.heightPx.toDouble() }.toFloat()).toDp() } + addRowHeight

    val maxLeftUs = max(0L, displayProject.durationUs - viewport.pxToDurationUs(viewport.widthPx * 0.5f))

    // Follow the playhead while playing.
    val currentPlayhead by rememberUpdatedState(playheadUs)
    val currentMaxLeft by rememberUpdatedState(maxLeftUs)
    LaunchedEffect(isPlaying, viewport) {
        if (isPlaying) snapshotFlow { currentPlayhead }.collect { viewport.follow(it, currentMaxLeft + viewport.pxToDurationUs(viewport.widthPx)) }
    }

    Column(modifier.background(c.surface)) {
        Row(Modifier.fillMaxWidth().height(metrics.rulerHeight)) {
            Box(
                Modifier.width(metrics.headerWidth).fillMaxHeight().background(c.surface).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (!metrics.compactHeaders) {
                    Text(
                        Timecode.format(playheadUs, project.settings.frameRate),
                        style = TimecodeStyle.copy(fontSize = 12.sp),
                        color = c.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            Ruler(
                project = displayProject,
                playheadUs = playheadUs,
                viewport = viewport,
                maxLeftUs = maxLeftUs,
                onSeek = onSeek,
                onScrubbing = onScrubbing,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize().verticalScroll(vScroll, enabled = false)) {
                TrackHeaders(
                    rows = liveRows,
                    metrics = metrics,
                    onToggleMute = onToggleTrackMute,
                    onToggleLock = onToggleTrackLock,
                    onAddAudioTrack = onAddAudioTrack,
                    addRowHeight = addRowHeight,
                )
                TrackArea(
                    project = project,
                    displayProject = displayProject,
                    rows = liveRows,
                    playheadUs = playheadUs,
                    selectedClipIds = selectedClipIds,
                    missingAssetIds = missingAssetIds,
                    snappingEnabled = snappingEnabled,
                    viewport = viewport,
                    media = media,
                    vScroll = vScroll,
                    drag = drag,
                    onDragChange = { drag = it },
                    maxLeftUs = maxLeftUs,
                    onSelect = onSelect,
                    onEdit = onEdit,
                    modifier = Modifier.weight(1f).height(contentHeight),
                )
            }
            if (project.sequence.allClips.isEmpty()) {
                Text(
                    "Import media, then tap + to place it on the timeline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textTertiary,
                    modifier = Modifier.align(Alignment.Center).padding(start = metrics.headerWidth),
                )
            }
        }
    }
}

@Composable
private fun TrackHeaders(
    rows: List<TrackRow>,
    metrics: TimelineMetrics,
    onToggleMute: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onAddAudioTrack: () -> Unit,
    addRowHeight: Dp,
) {
    val c = palette
    val density = LocalDensity.current
    Column(Modifier.width(metrics.headerWidth).background(c.surface)) {
        rows.forEach { row ->
            val track = row.track
            val h = with(density) { row.heightPx.toDp() }
            Row(
                Modifier.fillMaxWidth().height(h).padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when (track.kind) {
                    TrackKind.VIDEO -> ClipprIcons.Film
                    TrackKind.AUDIO -> ClipprIcons.Music
                    TrackKind.TEXT -> ClipprIcons.Text
                }
                if (!metrics.compactHeaders) {
                    Icon(icon, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        track.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (track.muted) c.textTertiary else c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = if (metrics.compactHeaders) Modifier.fillMaxWidth() else Modifier,
                ) {
                    val compactStack = row.heightPx < with(density) { 56.dp.toPx() } && metrics.compactHeaders
                    if (track.kind != TrackKind.TEXT && !compactStack) {
                        HeaderToggle(
                            icon = if (track.muted) ClipprIcons.Mute else ClipprIcons.Volume,
                            description = if (track.muted) "Unmute ${track.name}" else "Mute ${track.name}",
                            active = track.muted,
                            onClick = { onToggleMute(track.id) },
                        )
                    }
                    if (!metrics.compactHeaders || compactStack || track.kind == TrackKind.TEXT) {
                        HeaderToggle(
                            icon = if (track.locked) ClipprIcons.Lock else ClipprIcons.Unlock,
                            description = if (track.locked) "Unlock ${track.name}" else "Lock ${track.name}",
                            active = track.locked,
                            onClick = { onToggleLock(track.id) },
                        )
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(addRowHeight)
                .clickable(onClickLabel = "Add audio track", onClick = onAddAudioTrack)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ClipprIcons.Plus, contentDescription = "Add audio track", tint = c.textTertiary, modifier = Modifier.size(16.dp))
            if (!metrics.compactHeaders) {
                Spacer(Modifier.width(6.dp))
                Text("Audio track", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            }
        }
    }
}

@Composable
private fun HeaderToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val c = palette
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) c.accentSoft else Color.Transparent)
            .clickable(onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) c.accent else c.textTertiary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Ruler(
    project: Project,
    playheadUs: Long,
    viewport: TimelineViewport,
    maxLeftUs: Long,
    onSeek: (Long) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val c = palette
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val seek by rememberUpdatedState(onSeek)
    val scrubbing by rememberUpdatedState(onScrubbing)
    val maxLeft by rememberUpdatedState(maxLeftUs)
    val labelStyle = TextStyle(color = c.textTertiary, fontSize = 10.sp, fontFeatureSettings = "tnum")
    Canvas(
        modifier
            .background(c.surface)
            .onSizeChanged { viewport.widthPx = it.width.toFloat() }
            .semantics { contentDescription = "Timeline ruler. Tap or drag to move the playhead." }
            .pointerInput(viewport) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    scrubbing(true)
                    seek(viewport.xToTime(down.position.x).coerceAtLeast(0))
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChanged()) {
                                change.consume()
                                seek(viewport.xToTime(change.position.x).coerceAtLeast(0))
                            }
                        }
                    } finally {
                        scrubbing(false)
                    }
                }
            }
            .pointerInput(viewport) { handleWheel(viewport) { maxLeft } },
    ) {
        val minLabel = 72.dp.toPx()
        val (step, minors) = RulerScale.steps(viewport.pxPerSecond, minLabel)
        val minorStep = step / minors
        val startUs = (viewport.leftUs.toLong() / minorStep) * minorStep
        val endUs = viewport.xToTime(size.width) + minorStep
        var t = startUs
        while (t <= endUs) {
            val x = viewport.timeToX(t)
            val major = t % step == 0L
            val tickH = if (major) size.height * 0.42f else size.height * 0.2f
            drawLine(
                if (major) c.textTertiary else c.outline,
                Offset(x, size.height - tickH),
                Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            if (major && x > -minLabel) {
                drawText(
                    measurer,
                    RulerScale.label(t, step),
                    topLeft = Offset(x + 4.dp.toPx(), 3.dp.toPx()),
                    style = labelStyle,
                    maxLines = 1,
                )
            }
            t += minorStep
        }
        // Content end marker.
        val endX = viewport.timeToX(project.durationUs)
        if (project.durationUs > 0 && endX in 0f..size.width) {
            drawLine(c.accent.copy(alpha = 0.5f), Offset(endX, 0f), Offset(endX, size.height), 1.dp.toPx())
        }
        // Playhead head.
        val px = viewport.timeToX(playheadUs)
        if (px in -20f..size.width + 20f) {
            val w = 12.dp.toPx()
            val h = size.height * 0.62f
            val path = Path().apply {
                moveTo(px - w / 2, 2.dp.toPx())
                lineTo(px + w / 2, 2.dp.toPx())
                lineTo(px + w / 2, h - w / 2)
                lineTo(px, h)
                lineTo(px - w / 2, h - w / 2)
                close()
            }
            drawPath(path, c.accent)
            drawLine(c.playhead, Offset(px, h), Offset(px, size.height), 2.dp.toPx())
        }
    }
}

/** Mouse/trackpad wheel: scroll horizontally, or zoom around the pointer with Ctrl. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.handleWheel(viewport: TimelineViewport, maxLeftUs: () -> Long) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            val delta = change.scrollDelta
            if (event.keyboardModifiers.isCtrlPressed) {
                val factor = if (delta.y < 0) 1.15f else 1f / 1.15f
                viewport.zoomBy(factor, change.position.x, maxLeftUs() + viewport.pxToDurationUs(viewport.widthPx))
            } else {
                val step = 56.dp.toPx()
                viewport.scrollBy((delta.y + delta.x) * step, maxLeftUs())
            }
            change.consume()
        }
    }
}

@Composable
private fun TrackArea(
    project: Project,
    displayProject: Project,
    rows: List<TrackRow>,
    playheadUs: Long,
    selectedClipIds: Set<String>,
    missingAssetIds: Set<String>,
    snappingEnabled: Boolean,
    viewport: TimelineViewport,
    media: TimelineMediaCache,
    vScroll: ScrollState,
    drag: DragOp?,
    onDragChange: (DragOp?) -> Unit,
    maxLeftUs: Long,
    onSelect: (String?, Boolean) -> Unit,
    onEdit: (EditCommand) -> Unit,
    modifier: Modifier,
) {
    val c = palette
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer(cacheSize = 128)

    val state = rememberUpdatedState(
        GestureInputs(project, rows, playheadUs, selectedClipIds, snappingEnabled, maxLeftUs, onSelect, onEdit, onDragChange),
    )
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier
            .pointerInput(viewport) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    flingJob?.cancel()
                    val s = state.value
                    val hit = hitTest(down.position, s, viewport, density)
                    val slop = viewConfiguration.touchSlop
                    val outcome: Outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var result: Outcome? = null
                        while (result == null) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } >= 2) {
                                result = Outcome.Pinch
                            } else {
                                val ch = ev.changes.firstOrNull { it.id == down.id }
                                result = when {
                                    ch == null -> Outcome.Cancel
                                    !ch.pressed -> Outcome.Tap(ch)
                                    (ch.position - down.position).getDistance() > slop -> Outcome.Drag(ch)
                                    else -> null
                                }
                            }
                        }
                        result
                    } ?: Outcome.LongPress

                    when (outcome) {
                        is Outcome.Tap -> {
                            val now = outcome.change.uptimeMillis
                            val isDouble = now - lastTapTime < viewConfiguration.doubleTapTimeoutMillis &&
                                (outcome.change.position - lastTapPos).getDistance() < slop * 2
                            lastTapTime = now
                            lastTapPos = outcome.change.position
                            val additive = currentEvent.keyboardModifiers.let { it.isShiftPressed || it.isCtrlPressed || it.isMetaPressed }
                            when (hit) {
                                is Hit.OnClip -> s.onSelect(hit.clip.id, additive)
                                is Hit.TrimHandle -> s.onSelect(hit.clip.id, additive)
                                is Hit.Empty -> if (isDouble) viewport.fit(s.project.durationUs) else s.onSelect(null, false)
                            }
                        }
                        Outcome.LongPress -> {
                            val target = (hit as? Hit.OnClip)?.takeIf { !it.row.track.locked }
                            if (target != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (target.clip.id !in s.selected) s.onSelect(target.clip.id, false)
                                moveLoop(down, target.clip, target.row, s, viewport, density, haptics)
                            }
                        }
                        is Outcome.Drag -> when {
                            hit is Hit.TrimHandle -> trimLoop(down, hit, s, viewport, density, haptics)
                            hit is Hit.OnClip && hit.clip.id in s.selected && !hit.row.track.locked ->
                                moveLoop(down, hit.clip, hit.row, s, viewport, density, haptics)
                            else -> {
                                val velocity = panLoop(down, outcome.change, viewport, vScroll, s.maxLeftUs)
                                if (abs(velocity) > 50f) {
                                    flingJob = scope.launch {
                                        var last = 0f
                                        AnimationState(0f, -velocity).animateDecay(splineBasedDecay(density)) {
                                            viewport.scrollBy(value - last, state.value.maxLeftUs)
                                            last = value
                                        }
                                    }
                                }
                            }
                        }
                        Outcome.Pinch -> pinchLoop(viewport, vScroll) { state.value.maxLeftUs }
                        Outcome.Cancel -> Unit
                    }
                }
            }
            .pointerInput(viewport) { handleWheel(viewport) { state.value.maxLeftUs } },
    ) {
        @Suppress("UNUSED_VARIABLE")
        val mediaVersion = media.version // read so newly loaded thumbnails redraw the canvas
        val dragging = drag
        val draggedId = when (dragging) {
            is DragOp.Move -> dragging.clipId
            is DragOp.TrimStart -> dragging.clipId
            is DragOp.TrimEnd -> dragging.clipId
            null -> null
        }
        rows.forEachIndexed { index, row ->
            if (index % 2 == 1) drawRect(c.background.copy(alpha = 0.35f), Offset(0f, row.topPx), Size(size.width, row.heightPx))
            drawLine(c.hairline, Offset(0f, row.topPx + row.heightPx), Offset(size.width, row.topPx + row.heightPx), 1f)
            if (row.track.locked) {
                drawRect(c.background.copy(alpha = 0.45f), Offset(0f, row.topPx), Size(size.width, row.heightPx))
            }
        }
        // Ghost of the original position while moving.
        if (dragging is DragOp.Move) {
            val original = project.sequence.clip(dragging.clipId)
            val originalRow = rows.firstOrNull { r -> project.sequence.trackOfClip(dragging.clipId)?.id == r.track.id }
            if (original != null && originalRow != null) {
                val x0 = viewport.timeToX(original.timelineStartUs)
                val x1 = viewport.timeToX(original.timelineEndUs)
                val inset = 4.dp.toPx()
                drawRoundRect(
                    c.textTertiary,
                    Offset(x0, originalRow.topPx + inset),
                    Size(x1 - x0, originalRow.heightPx - 2 * inset),
                    CornerRadius(6.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                )
            }
        }
        rows.forEach { row ->
            row.track.clips.forEach { clip ->
                drawClip(
                    clip = clip,
                    row = row,
                    project = displayProject,
                    selected = clip.id in selectedClipIds,
                    dragging = clip.id == draggedId,
                    missing = clip.assetId != null && clip.assetId in missingAssetIds,
                    viewport = viewport,
                    media = media,
                    colors = c,
                    measurer = measurer,
                )
            }
        }
        dragging?.snapLineUs?.let { snap ->
            val x = viewport.timeToX(snap)
            drawLine(
                c.snapLine,
                Offset(x, 0f),
                Offset(x, size.height),
                1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
            )
        }
        val px = viewport.timeToX(playheadUs)
        if (px in -4f..size.width + 4f) {
            drawLine(Color.Black.copy(alpha = 0.35f), Offset(px, 0f), Offset(px, size.height), 4.dp.toPx())
            drawLine(c.playhead, Offset(px, 0f), Offset(px, size.height), 2.dp.toPx(), cap = StrokeCap.Square)
        }
    }
}

/** Snapshot of everything gesture code needs, refreshed every composition. */
private data class GestureInputs(
    val project: Project,
    val rows: List<TrackRow>,
    val playheadUs: Long,
    val selected: Set<String>,
    val snapping: Boolean,
    val maxLeftUs: Long,
    val onSelect: (String?, Boolean) -> Unit,
    val onEdit: (EditCommand) -> Unit,
    val setDrag: (DragOp?) -> Unit,
)

private fun hitTest(pos: Offset, s: GestureInputs, viewport: TimelineViewport, density: androidx.compose.ui.unit.Density): Hit {
    val row = s.rows.firstOrNull { pos.y >= it.topPx && pos.y < it.topPx + it.heightPx } ?: return Hit.Empty(null)
    val handle = with(density) { 18.dp.toPx() }
    if (!row.track.locked) {
        for (clip in row.track.clips) {
            if (clip.id !in s.selected) continue
            val x0 = viewport.timeToX(clip.timelineStartUs)
            val x1 = viewport.timeToX(clip.timelineEndUs)
            val mid = (x0 + x1) / 2
            if (abs(pos.x - x0) <= handle && pos.x <= mid) return Hit.TrimHandle(clip, row, start = true)
            if (abs(pos.x - x1) <= handle && pos.x >= mid) return Hit.TrimHandle(clip, row, start = false)
        }
    }
    val clip = row.track.clips.firstOrNull {
        pos.x >= viewport.timeToX(it.timelineStartUs) && pos.x <= viewport.timeToX(it.timelineEndUs)
    }
    return if (clip != null) Hit.OnClip(clip, row) else Hit.Empty(row)
}

private fun compatible(track: Track, clip: Clip, project: Project): Boolean =
    if (clip.isText) track.kind == TrackKind.TEXT else track.accepts(project.asset(clip.assetId)?.type)

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.moveLoop(
    down: PointerInputChange,
    clip: Clip,
    startRow: TrackRow,
    s: GestureInputs,
    viewport: TimelineViewport,
    density: androidx.compose.ui.unit.Density,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    val grabOffsetUs = viewport.xToTime(down.position.x) - clip.timelineStartUs
    val targets = if (s.snapping) Snapping.targets(s.project.sequence, s.playheadUs, setOf(clip.id)) else emptyList()
    val threshold = viewport.pxToDurationUs(with(density) { 10.dp.toPx() })
    val edge = with(density) { 28.dp.toPx() }
    var trackId = startRow.track.id
    var op = DragOp.Move(clip.id, trackId, clip.timelineStartUs, null)
    s.setDrag(op)
    var lastSnap: Long? = null
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
        if (!ch.pressed) break
        ch.consume()
        val x = ch.position.x
        if (x < edge) viewport.scrollBy(-(edge - x) / 3f, s.maxLeftUs + clip.durationUs)
        if (x > viewport.widthPx - edge) viewport.scrollBy((x - (viewport.widthPx - edge)) / 3f, s.maxLeftUs + clip.durationUs)
        s.rows.firstOrNull { ch.position.y >= it.topPx && ch.position.y < it.topPx + it.heightPx }
            ?.takeIf { !it.track.locked && compatible(it.track, clip, s.project) }
            ?.let { trackId = it.track.id }
        val raw = (viewport.xToTime(x) - grabOffsetUs).coerceAtLeast(0)
        val snap = Snapping.snapRange(raw, clip.durationUs, targets, threshold)
        if (snap.snappedTo != null && snap.snappedTo != lastSnap) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lastSnap = snap.snappedTo
        op = DragOp.Move(clip.id, trackId, snap.timeUs.coerceAtLeast(0), snap.snappedTo)
        s.setDrag(op)
    }
    s.setDrag(null)
    if (op.startUs != clip.timelineStartUs || op.trackId != startRow.track.id) s.onEdit(op.command())
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.trimLoop(
    down: PointerInputChange,
    hit: Hit.TrimHandle,
    s: GestureInputs,
    viewport: TimelineViewport,
    density: androidx.compose.ui.unit.Density,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    val clip = hit.clip
    val edgeTime = if (hit.start) clip.timelineStartUs else clip.timelineEndUs
    val grabOffsetUs = viewport.xToTime(down.position.x) - edgeTime
    val targets = if (s.snapping) Snapping.targets(s.project.sequence, s.playheadUs, setOf(clip.id)) else emptyList()
    val threshold = viewport.pxToDurationUs(with(density) { 10.dp.toPx() })
    var op: DragOp = if (hit.start) DragOp.TrimStart(clip.id, edgeTime, null) else DragOp.TrimEnd(clip.id, edgeTime, null)
    s.setDrag(op)
    var lastSnap: Long? = null
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
        if (!ch.pressed) break
        ch.consume()
        val raw = viewport.xToTime(ch.position.x) - grabOffsetUs
        val snap = Snapping.snap(raw, targets, threshold)
        if (snap.snappedTo != null && snap.snappedTo != lastSnap) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lastSnap = snap.snappedTo
        op = if (hit.start) DragOp.TrimStart(clip.id, snap.timeUs, snap.snappedTo) else DragOp.TrimEnd(clip.id, snap.timeUs, snap.snappedTo)
        s.setDrag(op)
    }
    s.setDrag(null)
    s.onEdit(op.command())
}

/** Scrolls with a single finger and returns the horizontal release velocity (px/s). */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.panLoop(
    down: PointerInputChange,
    first: PointerInputChange,
    viewport: TimelineViewport,
    vScroll: ScrollState,
    maxLeftUs: Long,
): Float {
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var last = first.position
    tracker.addPosition(first.uptimeMillis, first.position)
    viewport.scrollBy(-(first.position.x - down.position.x), maxLeftUs)
    vScroll.dispatchRawDelta(-(first.position.y - down.position.y))
    first.consume()
    while (true) {
        val ev = awaitPointerEvent()
        if (ev.changes.count { it.pressed } >= 2) return 0f
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: return 0f
        if (!ch.pressed) break
        val d = ch.position - last
        last = ch.position
        tracker.addPosition(ch.uptimeMillis, ch.position)
        viewport.scrollBy(-d.x, maxLeftUs)
        vScroll.dispatchRawDelta(-d.y)
        ch.consume()
    }
    return tracker.calculateVelocity().x
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.pinchLoop(
    viewport: TimelineViewport,
    vScroll: ScrollState,
    maxLeftUs: () -> Long,
) {
    while (true) {
        val ev = awaitPointerEvent()
        if (ev.changes.none { it.pressed }) break
        val zoom = ev.calculateZoom()
        val pan = ev.calculatePan()
        val centroid = ev.calculateCentroid(useCurrent = true)
        if (zoom != 1f && centroid != Offset.Unspecified) {
            viewport.zoomBy(zoom, centroid.x, maxLeftUs() + viewport.pxToDurationUs(viewport.widthPx))
        }
        viewport.scrollBy(-pan.x, maxLeftUs())
        vScroll.dispatchRawDelta(-pan.y)
        ev.changes.forEach { if (it.positionChanged()) it.consume() }
    }
}

private fun DrawScope.drawClip(
    clip: Clip,
    row: TrackRow,
    project: Project,
    selected: Boolean,
    dragging: Boolean,
    missing: Boolean,
    viewport: TimelineViewport,
    media: TimelineMediaCache,
    colors: ClipprColors,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val x0 = viewport.timeToX(clip.timelineStartUs)
    val x1 = viewport.timeToX(clip.timelineEndUs)
    if (x1 < -2f || x0 > size.width + 2f) return
    val inset = 4.dp.toPx()
    val top = row.topPx + inset
    val h = row.heightPx - 2 * inset
    val w = max(x1 - x0, 2f)
    val radius = CornerRadius(6.dp.toPx())
    val asset = project.asset(clip.assetId)
    val kind = row.track.kind
    val (fill, edge) = when {
        missing -> colors.missingMedia to colors.danger.copy(alpha = 0.7f)
        kind == TrackKind.AUDIO -> colors.audioClip to colors.audioClipEdge
        kind == TrackKind.TEXT -> colors.textClip to colors.textClipEdge
        else -> colors.videoClip to colors.videoClipEdge
    }
    val alpha = if (row.track.locked) 0.55f else 1f

    if (dragging) {
        drawRoundRect(Color.Black.copy(alpha = 0.4f), Offset(x0 + 2.dp.toPx(), top + 3.dp.toPx()), Size(w, h), radius)
    }
    drawRoundRect(fill.copy(alpha = alpha), Offset(x0, top), Size(w, h), radius)

    clipRect(left = max(x0, 0f), top = top, right = min(x0 + w, size.width), bottom = top + h) {
        if (!missing && asset != null && kind == TrackKind.VIDEO) {
            drawThumbnails(clip, asset, x0, top, w, h, viewport, media, alpha)
            drawRect(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), startY = top, endY = top + h * 0.55f),
                Offset(x0, top),
                Size(w, h * 0.55f),
            )
        }
        if (!missing && asset != null && kind == TrackKind.AUDIO) {
            drawWaveform(clip, asset, x0, top, w, h, viewport, media, colors, alpha)
        }
        // Fades
        val fadeColor = Color.Black.copy(alpha = 0.35f)
        if (clip.audio.fadeInUs > 0) {
            val fw = viewport.durationToPx(clip.audio.fadeInUs)
            val p = Path().apply { moveTo(x0, top); lineTo(x0 + fw, top); lineTo(x0, top + h); close() }
            drawPath(p, fadeColor)
            drawLine(Color.White.copy(alpha = 0.6f), Offset(x0, top + h), Offset(x0 + fw, top), 1.dp.toPx())
        }
        if (clip.audio.fadeOutUs > 0) {
            val fw = viewport.durationToPx(clip.audio.fadeOutUs)
            val p = Path().apply { moveTo(x0 + w, top); lineTo(x0 + w - fw, top); lineTo(x0 + w, top + h); close() }
            drawPath(p, fadeColor)
            drawLine(Color.White.copy(alpha = 0.6f), Offset(x0 + w - fw, top), Offset(x0 + w, top + h), 1.dp.toPx())
        }
        // Label
        val label = when {
            missing -> "Missing: ${asset?.displayName ?: "media"}"
            clip.isText -> clip.text?.text?.lines()?.firstOrNull()?.ifBlank { "Text" } ?: "Text"
            else -> asset?.displayName ?: "Unknown media"
        }
        val pad = 6.dp.toPx()
        val labelX = max(x0, 0f) + pad
        val availableW = (x0 + w - labelX - pad).toInt()
        if (availableW > 12) {
            val textColor = when {
                missing -> colors.danger
                kind == TrackKind.TEXT -> colors.textClipLabel
                else -> colors.textPrimary
            }
            drawText(
                measurer,
                label,
                topLeft = Offset(labelX, top + 3.dp.toPx()),
                style = TextStyle(color = textColor.copy(alpha = alpha), fontSize = 11.sp, fontWeight = FontWeight.Medium),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                size = Size(availableW.toFloat(), 14.sp.toPx() * 1.4f),
            )
            val badges = buildList {
                if (clip.speed != 1f) add("${trimFloat(clip.speed)}×")
                if (clip.audio.muted) add("muted")
                if (w > 140.dp.toPx() || selected) add(Timecode.formatDuration(clip.durationUs))
            }.joinToString("  ·  ")
            if (badges.isNotEmpty() && h > 30.dp.toPx() && availableW > 60) {
                drawText(
                    measurer,
                    badges,
                    topLeft = Offset(labelX, top + h - 16.dp.toPx()),
                    style = TextStyle(color = colors.textSecondary.copy(alpha = alpha), fontSize = 10.sp, fontFeatureSettings = "tnum"),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    size = Size(availableW.toFloat(), 12.sp.toPx() * 1.4f),
                )
            }
        }
    }

    val borderWidth = if (selected) 2.dp.toPx() else 1.dp.toPx()
    drawRoundRect(
        if (selected) colors.accent else edge.copy(alpha = alpha),
        Offset(x0 + borderWidth / 2, top + borderWidth / 2),
        Size(w - borderWidth, h - borderWidth),
        radius,
        style = Stroke(borderWidth),
    )
    if (selected && !row.track.locked) {
        val hw = 9.dp.toPx()
        listOf(x0, x0 + w - hw).forEach { hx ->
            drawRoundRect(colors.accent, Offset(hx, top), Size(hw, h), CornerRadius(4.dp.toPx()))
            val cx = hx + hw / 2
            drawLine(colors.onAccent, Offset(cx - 1.5f, top + h * 0.36f), Offset(cx - 1.5f, top + h * 0.64f), 1.5f)
            drawLine(colors.onAccent, Offset(cx + 1.5f, top + h * 0.36f), Offset(cx + 1.5f, top + h * 0.64f), 1.5f)
        }
    }
}

private fun DrawScope.drawThumbnails(
    clip: Clip,
    asset: app.clippr.core.model.MediaAsset,
    x0: Float,
    top: Float,
    w: Float,
    h: Float,
    viewport: TimelineViewport,
    media: TimelineMediaCache,
    alpha: Float,
) {
    val aspect = if (asset.displayHeight > 0) (asset.displayWidth.toFloat() / asset.displayHeight).coerceIn(0.5f, 2.4f) else 16f / 9f
    val tileW = max(h * aspect, 8f)
    // Request heights in 16px steps so neighbouring zoom levels share cache entries.
    val reqH = (ceil(h / 16f) * 16f).toInt().coerceAtLeast(16)
    val firstTile = floor(max(0f, -x0) / tileW).toInt()
    val lastTile = ceil((min(size.width, x0 + w) - x0) / tileW).toInt()
    for (i in firstTile until max(firstTile, lastTile)) {
        val tx = x0 + i * tileW
        val centerTimeline = viewport.xToTime(tx + tileW / 2).coerceIn(clip.timelineStartUs, clip.timelineEndUs - 1)
        val sourceTime = if (asset.type == MediaType.IMAGE) 0L else clip.sourceTimeAt(centerTimeline)
        val img = media.thumbnail(asset, sourceTime, reqH) ?: continue
        val scale = h / img.height
        val dw = img.width * scale
        drawImage(
            img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(tx.roundToInt(), top.roundToInt()),
            dstSize = IntSize(min(dw, tileW).roundToInt() + 1, h.roundToInt()),
            alpha = alpha,
        )
    }
}

private fun DrawScope.drawWaveform(
    clip: Clip,
    asset: app.clippr.core.model.MediaAsset,
    x0: Float,
    top: Float,
    w: Float,
    h: Float,
    viewport: TimelineViewport,
    media: TimelineMediaCache,
    colors: ClipprColors,
    alpha: Float,
) {
    val wf = media.waveform(asset) ?: return
    val step = 2.dp.toPx()
    val mid = top + h * 0.56f
    val amp = h * 0.40f
    val gain = if (clip.audio.muted) 0.08f else clip.audio.volume.coerceIn(0.08f, 1f)
    var x = max(x0, 0f)
    val end = min(x0 + w, size.width)
    val color = colors.waveform.copy(alpha = 0.85f * alpha)
    while (x < end) {
        val t0 = viewport.xToTime(x).coerceIn(clip.timelineStartUs, clip.timelineEndUs)
        val t1 = viewport.xToTime(x + step).coerceIn(clip.timelineStartUs, clip.timelineEndUs)
        val peak = wf.peakBetween(clip.sourceTimeAt(t0), clip.sourceTimeAt(t1)) * gain
        val half = max(0.75f, peak * amp)
        drawLine(color, Offset(x, mid - half), Offset(x, mid + half), step * 0.6f, cap = StrokeCap.Round)
        x += step
    }
}

private fun trimFloat(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
