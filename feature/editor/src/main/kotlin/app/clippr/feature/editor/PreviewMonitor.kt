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
import androidx.compose.runtime.getValue
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
import app.clippr.core.model.AspectRatioPreset
import app.clippr.core.model.Clip
import app.clippr.core.model.Project
import app.clippr.core.model.TrackKind
import app.clippr.core.model.Transform
import app.clippr.core.model.render.ClipFrame
import app.clippr.core.model.render.FrameGeometry
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
 * handles and guides drawn on top. With a visual clip selected under the playhead, drag moves
 * it, pinch scales, twist rotates and double-tap resets its placement.
 */
@Composable
fun PreviewMonitor(
    project: Project,
    engine: PreviewEngine,
    playheadUs: Long,
    selectedClip: Clip?,
    lockedTrackIds: Set<String>,
    guides: GuideMode,
    onLiveTransform: (clipId: String, Transform) -> Unit,
    onCommit: () -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
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
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).also(engine::attachSurface) },
                onRelease = { view -> engine.detachSurface(view) },
                modifier = Modifier.fillMaxSize(),
            )

            val activeClip = selectedClip?.takeIf { it.contains(playheadUs) && isVisual(project, it) }
            val editable = activeClip != null && project.sequence.trackOfClip(activeClip.id)?.id !in lockedTrackIds

            TransformLayer(
                project = project,
                playheadUs = playheadUs,
                activeClip = if (editable) activeClip else null,
                onLiveTransform = onLiveTransform,
                onCommit = onCommit,
                onSelect = onSelect,
            )

            Canvas(Modifier.fillMaxSize()) {
                drawGuides(guides, project, c.textPrimary)
                if (activeClip != null) {
                    frameFor(project, activeClip, size)?.let { drawSelection(it, c.accent, editable) }
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

/** The rotated rectangle a clip occupies in a view of [size] showing the whole canvas. */
private fun frameFor(project: Project, clip: Clip, size: Size): ClipFrame? {
    if (size.width <= 0f || size.height <= 0f) return null
    val t = clip.transform
    val style = clip.text
    if (style != null) {
        val canvasW = project.settings.width
        val canvasH = project.settings.height
        val m = TextRenderer.measure(style, canvasW, canvasH, t.scale)
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
    activeClip: Clip?,
    onLiveTransform: (String, Transform) -> Unit,
    onCommit: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    val latest by rememberUpdatedState(TransformInputs(project, playheadUs, activeClip, onLiveTransform, onCommit, onSelect))
    var lastTap by remember { mutableLongStateOf(0L) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val inputs = latest
                    val viewSize = Size(size.width.toFloat(), size.height.toFloat())
                    val clip = inputs.activeClip
                    val frame = clip?.let { frameFor(inputs.project, it, viewSize) }
                    var transform = clip?.transform
                    var moved = false
                    var multiTouch = false
                    val startedInside = frame?.contains(down.position.x, down.position.y) == true
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        if (event.changes.count { it.pressed } >= 2) multiTouch = true
                        val t = transform
                        if (clip == null || t == null || !(startedInside || multiTouch)) continue
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        val rotation = event.calculateRotation()
                        if (pan == Offset.Zero && zoom == 1f && rotation == 0f) continue
                        moved = moved || event.changes.any { it.positionChanged() }
                        val next = t.copy(
                            positionX = t.positionX + pan.x / (viewSize.width / 2f),
                            positionY = t.positionY - pan.y / (viewSize.height / 2f),
                            scale = t.scale * zoom,
                            rotationDegrees = t.rotationDegrees + rotation,
                        ).sanitized()
                        transform = next
                        inputs.onLiveTransform(clip.id, next)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    if (moved) {
                        inputs.onCommit()
                    } else {
                        // A tap: double-tap resets placement, single tap selects what is under it.
                        val now = System.currentTimeMillis()
                        if (clip != null && startedInside && now - lastTap < viewConfiguration.doubleTapTimeoutMillis) {
                            inputs.onLiveTransform(clip.id, Transform(crop = clip.transform.crop, fit = clip.transform.fit))
                            inputs.onCommit()
                            lastTap = 0L
                        } else {
                            lastTap = now
                            inputs.onSelect(hitClip(inputs.project, inputs.playheadUs, down.position, viewSize)?.id)
                        }
                    }
                }
            },
    )
}

private data class TransformInputs(
    val project: Project,
    val playheadUs: Long,
    val activeClip: Clip?,
    val onLiveTransform: (String, Transform) -> Unit,
    val onCommit: () -> Unit,
    val onSelect: (String?) -> Unit,
)

/** Topmost visible clip at [point]: text overlays first, then the main video clip. */
private fun hitClip(project: Project, timeUs: Long, point: Offset, size: Size): Clip? {
    val textHit = project.sequence.tracks
        .filter { it.kind == TrackKind.TEXT }
        .mapNotNull { it.clipAt(timeUs) }
        .lastOrNull { frameFor(project, it, size)?.contains(point.x, point.y) == true }
    if (textHit != null) return textHit
    val video = project.sequence.mainVideoTrack?.clipAt(timeUs) ?: return null
    return video.takeIf { frameFor(project, it, size)?.contains(point.x, point.y) == true }
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
