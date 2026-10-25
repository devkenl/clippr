package app.clippr.feature.editor

import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.clippr.core.model.edit.TransformSnapping
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.clippr.core.model.Animation
import app.clippr.core.model.AspectRatioPreset
import app.clippr.core.model.Clip
import app.clippr.core.model.Project
import app.clippr.core.model.TrackKind
import app.clippr.core.model.Transform
import app.clippr.core.model.render.ClipFrame
import app.clippr.core.model.render.FrameGeometry
import app.clippr.core.render.FontLibrary
import app.clippr.core.render.PreviewEngine
import app.clippr.core.render.TextRenderer
import app.clippr.core.ui.icons.ClipprIcons
import app.clippr.core.ui.theme.Spacing
import app.clippr.core.ui.theme.palette

/** Overlay guides drawn over the preview. */
enum class GuideMode(val label: String) {
    OFF("Guides off"),
    SAFE_AREAS("Safe areas"),
    VERTICAL("9:16 guide"),
    SQUARE("1:1 guide"),
    PORTRAIT("4:5 guide"),
    ;

    fun next(): GuideMode = entries[(ordinal + 1) % entries.size]

    val preset: AspectRatioPreset?
        get() = when (this) {
            VERTICAL -> AspectRatioPreset.PORTRAIT_9_16
            SQUARE -> AspectRatioPreset.SQUARE_1_1
            PORTRAIT -> AspectRatioPreset.PORTRAIT_4_5
            else -> null
        }
}

/**
 * The preview monitor: the player's SurfaceView at the project's aspect ratio, with selection
 * handles and guides drawn on top.
 *
 * Direct manipulation: touching any visible clip (text or video under the playhead) selects it;
 * dragging moves it, pinching scales and twisting rotates. Double-tap resets its placement. With
 * keyframes, edits update the keyframe at the playhead (see EditorViewModel.liveTransform).
 */
@Composable
fun PreviewMonitor(
    project: Project,
    engine: PreviewEngine,
    playheadUs: Long,
    selectedClip: Clip?,
    lockedTrackIds: Set<String>,
    guides: GuideMode,
    fonts: FontLibrary?,
    onLiveTransform: (clipId: String, Transform) -> Unit,
    onCommit: () -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val c = palette
    val aspect = project.settings.aspectRatio
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val containerAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else aspect
        Box(
            Modifier
                .aspectRatio(aspect, matchHeightConstraintsFirst = containerAspect > aspect)
                .semantics { contentDescription = "Preview monitor" },
        ) {
            // A new player gets a new SurfaceView (see PreviewEngine.surfaceGeneration).
            val surfaceGeneration by engine.surfaceGeneration.collectAsState()
            key(surfaceGeneration) {
                AndroidView(
                    factory = { ctx -> SurfaceView(ctx).also(engine::attachSurface) },
                    onRelease = { view -> engine.detachSurface(view) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val activeClip = selectedClip?.takeIf { it.contains(playheadUs) && isVisual(project, it) }
            val editable = activeClip != null && project.sequence.trackOfClip(activeClip.id)?.id !in lockedTrackIds

            val snapGuides = remember { mutableStateOf<TransformSnapping.Result?>(null) }
            if (interactive) {
                TransformLayer(
                    project = project,
                    playheadUs = playheadUs,
                    selectedClip = activeClip,
                    lockedTrackIds = lockedTrackIds,
                    fonts = fonts,
                    onLiveTransform = onLiveTransform,
                    onCommit = onCommit,
                    onSelect = onSelect,
                    snapGuides = snapGuides,
                )
            }

            Canvas(Modifier.fillMaxSize()) {
                drawGuides(guides, project, c.textPrimary)
                snapGuides.value?.let { drawSnapGuides(it, c.snapLine) }
                if (activeClip != null) {
                    frameFor(project, activeClip, playheadUs, size, fonts)?.let { drawSelection(it, c.accent, editable) }
                }
            }

            if (project.sequence.allClips.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(ClipprIcons.Film, contentDescription = null, tint = c.textTertiary)
                        Text("Your preview appears here", style = MaterialTheme.typography.bodyMedium, color = c.textTertiary)
                    }
                }
            }
        }
    }
}

private fun isVisual(project: Project, clip: Clip): Boolean {
    if (clip.isText) return true
    val track = project.sequence.trackOfClip(clip.id) ?: return false
    return track.kind == TrackKind.VIDEO
}

/** The rotated rectangle a clip occupies at [timeUs] in a view of [size] showing the whole canvas. */
private fun frameFor(project: Project, clip: Clip, timeUs: Long, size: Size, fonts: FontLibrary?): ClipFrame? {
    if (size.width <= 0f || size.height <= 0f) return null
    val t = Animation.transformAtTimeline(clip, timeUs)
    val style = clip.text
    if (style != null) {
        val canvasW = project.settings.width
        val canvasH = project.settings.height
        val m = TextRenderer.measure(style, canvasW, canvasH, t.scale, fonts)
        val k = size.width / canvasW
        return ClipFrame(
            centerX = size.width / 2f * (1f + t.positionX.coerceIn(-1f, 1f)),
            centerY = size.height / 2f * (1f - t.positionY.coerceIn(-1f, 1f)),
            width = m.widthPx * k,
            height = m.heightPx * k,
            rotationDegrees = t.rotationDegrees,
        )
    }
    val asset = project.asset(clip.assetId) ?: return null
    return FrameGeometry.clipFrame(asset.displayWidth, asset.displayHeight, t, size.width, size.height)
}

@Composable
private fun TransformLayer(
    project: Project,
    playheadUs: Long,
    selectedClip: Clip?,
    lockedTrackIds: Set<String>,
    fonts: FontLibrary?,
    onLiveTransform: (String, Transform) -> Unit,
    onCommit: () -> Unit,
    onSelect: (String?) -> Unit,
    snapGuides: MutableState<TransformSnapping.Result?>,
) {
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(TransformInputs(project, playheadUs, selectedClip, lockedTrackIds, fonts, onLiveTransform, onCommit, onSelect))
    var lastTap by remember { mutableLongStateOf(0L) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val inputs = latest
                    val viewSize = Size(size.width.toFloat(), size.height.toFloat())
                    fun editable(c: Clip) = inputs.project.sequence.trackOfClip(c.id)?.id !in inputs.lockedTrackIds
                    val selected = inputs.selectedClip?.takeIf { editable(it) }
                    val selectedFrame = selected?.let { frameFor(inputs.project, it, inputs.playheadUs, viewSize, inputs.fonts) }
                    val hit = hitClip(inputs.project, inputs.playheadUs, down.position, viewSize, inputs.fonts)?.takeIf { editable(it) }
                    // Prefer the selected clip when the finger is on it; otherwise grab what is under the finger.
                    var target: Clip? = when {
                        selectedFrame?.contains(down.position.x, down.position.y) == true -> selected
                        hit != null -> hit
                        else -> null
                    }
                    // The unsnapped value follows the fingers; what is shown is snapped (see
                    // TransformSnapping), so pulling further always breaks out of a snap.
                    var transform = target?.let { Animation.transformAtTimeline(it, inputs.playheadUs) }
                    var lastSnapped = false
                    var moved = false
                    var announced = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        if (target == null && event.changes.count { it.pressed } >= 2) {
                            // Two fingers anywhere: work on the selected clip, or the video under the playhead.
                            target = selected ?: inputs.project.sequence.mainVideoTrack
                                ?.takeIf { it.id !in inputs.lockedTrackIds }?.clipAt(inputs.playheadUs)
                            transform = target?.let { Animation.transformAtTimeline(it, inputs.playheadUs) }
                        }
                        val clip = target ?: continue
                        val t = transform ?: continue
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        val rotation = event.calculateRotation()
                        if (pan == Offset.Zero && zoom == 1f && rotation == 0f) continue
                        if (!announced && clip.id != inputs.selectedClip?.id) {
                            inputs.onSelect(clip.id)
                        }
                        announced = true
                        moved = true
                        val next = t.copy(
                            positionX = t.positionX + pan.x / (viewSize.width / 2f),
                            positionY = t.positionY - pan.y / (viewSize.height / 2f),
                            scale = t.scale * zoom,
                            rotationDegrees = t.rotationDegrees + rotation,
                        )
                        transform = next
                        val snapped = TransformSnapping.snap(next)
                        // A light tick when something locks into place.
                        if (snapped.any && !lastSnapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lastSnapped = snapped.any
                        snapGuides.value = snapped
                        inputs.onLiveTransform(clip.id, snapped.transform)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    snapGuides.value = null
                    if (moved) {
                        inputs.onCommit()
                    } else {
                        // A tap: double-tap resets placement, single tap selects what is under it.
                        val now = System.currentTimeMillis()
                        val tapped = if (selectedFrame?.contains(down.position.x, down.position.y) == true) selected else hit
                        if (tapped != null && tapped.id == inputs.selectedClip?.id && now - lastTap < viewConfiguration.doubleTapTimeoutMillis) {
                            inputs.onLiveTransform(tapped.id, Transform(crop = tapped.transform.crop, fit = tapped.transform.fit))
                            inputs.onCommit()
                            lastTap = 0L
                        } else {
                            lastTap = now
                            inputs.onSelect(hitClip(inputs.project, inputs.playheadUs, down.position, viewSize, inputs.fonts)?.id)
                        }
                    }
                }
            },
    )
}

private data class TransformInputs(
    val project: Project,
    val playheadUs: Long,
    val selectedClip: Clip?,
    val lockedTrackIds: Set<String>,
    val fonts: FontLibrary?,
    val onLiveTransform: (String, Transform) -> Unit,
    val onCommit: () -> Unit,
    val onSelect: (String?) -> Unit,
)

/** Topmost visible clip at [point]: text overlays first, then video layers from the top down. */
private fun hitClip(project: Project, timeUs: Long, point: Offset, size: Size, fonts: FontLibrary?): Clip? {
    val textHit = project.sequence.tracks
        .filter { it.kind == TrackKind.TEXT }
        .mapNotNull { it.clipAt(timeUs) }
        .lastOrNull { frameFor(project, it, timeUs, size, fonts)?.contains(point.x, point.y) == true }
    if (textHit != null) return textHit
    return project.sequence.videoTracks
        .filter { !it.hidden }
        .firstNotNullOfOrNull { track ->
            track.clipAt(timeUs)?.takeIf { frameFor(project, it, timeUs, size, fonts)?.contains(point.x, point.y) == true }
        }
}

private fun DrawScope.drawSelection(frame: ClipFrame, accent: Color, editable: Boolean) {
    val stroke = 1.5.dp.toPx()
    val handle = 8.dp.toPx()
    rotate(frame.rotationDegrees, pivot = Offset(frame.centerX, frame.centerY)) {
        val tl = Offset(frame.centerX - frame.width / 2, frame.centerY - frame.height / 2)
        drawRect(
            accent,
            topLeft = tl,
            size = Size(frame.width, frame.height),
            style = Stroke(stroke, pathEffect = if (editable) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
        )
        if (editable) {
            listOf(
                tl,
                Offset(tl.x + frame.width, tl.y),
                Offset(tl.x, tl.y + frame.height),
                Offset(tl.x + frame.width, tl.y + frame.height),
            ).forEach { p ->
                drawCircle(Color.White, handle / 2 + stroke, p)
                drawCircle(accent, handle / 2, p)
            }
        }
    }
}

/** Centre lines while a clip is snapped to the centre while dragging. */
private fun DrawScope.drawSnapGuides(snap: TransformSnapping.Result, color: Color) {
    val stroke = 1.dp.toPx()
    if (snap.centerX) drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke)
    if (snap.centerY) drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke)
}

private fun DrawScope.drawGuides(mode: GuideMode, project: Project, color: Color) {
    if (mode == GuideMode.OFF) return
    val line = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    val guideColor = color.copy(alpha = 0.55f)
    if (mode == GuideMode.SAFE_AREAS) {
        // Action-safe (90%) and title-safe (80%) areas, plus a centre cross.
        listOf(0.9f, 0.8f).forEach { f ->
            val w = size.width * f
            val h = size.height * f
            drawRect(guideColor, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h), style = line)
        }
        val cx = size.width / 2
        val cy = size.height / 2
        val arm = 10.dp.toPx()
        drawLine(guideColor, Offset(cx - arm, cy), Offset(cx + arm, cy), 1.dp.toPx())
        drawLine(guideColor, Offset(cx, cy - arm), Offset(cx, cy + arm), 1.dp.toPx())
        return
    }
    val preset = mode.preset ?: return
    val target = preset.width.toFloat() / preset.height
    val canvasAspect = project.settings.aspectRatio
    val (w, h) = if (target < canvasAspect) size.height * target to size.height else size.width to size.width / target
    val left = (size.width - w) / 2
    val top = (size.height - h) / 2
    val shade = Color.Black.copy(alpha = 0.45f)
    if (left > 0f) {
        drawRect(shade, Offset.Zero, Size(left, size.height))
        drawRect(shade, Offset(left + w, 0f), Size(size.width - left - w, size.height))
    }
    if (top > 0f) {
        drawRect(shade, Offset.Zero, Size(size.width, top))
        drawRect(shade, Offset(0f, top + h), Size(size.width, size.height - top - h))
    }
    drawRect(guideColor, Offset(left, top), Size(w, h), style = line)
}
