package app.clippr.core.model.edit

import app.clippr.core.model.Transform
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Magnetic snapping for on-canvas transforms: while dragging, pinching or twisting a clip in
 * the preview, values close to "neat" ones lock onto them.
 *
 * - Rotation snaps to 0°, 90°, 180° and −90° within [ROTATION_DEG].
 * - Scale snaps to 100 % within [SCALE].
 * - Position snaps to the canvas centre lines within [POSITION] (normalized units).
 *
 * The gesture keeps an unsnapped value and passes it through [snap] on every move, so the user
 * can always pull a clip out of a snap by moving further.
 */
object TransformSnapping {

    const val ROTATION_DEG = 5f
    const val SCALE = 0.04f
    const val POSITION = 0.03f

    data class Result(
        val transform: Transform,
        val rotation: Boolean,
        val scale: Boolean,
        val centerX: Boolean,
        val centerY: Boolean,
    ) {
        val any: Boolean get() = rotation || scale || centerX || centerY
    }

    fun snap(raw: Transform): Result {
        val nearestQuarter = (raw.rotationDegrees / 90f).roundToInt() * 90f
        val snapRotation = abs(raw.rotationDegrees - nearestQuarter) <= ROTATION_DEG
        val snapScale = abs(raw.scale - 1f) <= SCALE
        val snapX = abs(raw.positionX) <= POSITION
        val snapY = abs(raw.positionY) <= POSITION
        val t = raw.copy(
            rotationDegrees = if (snapRotation) Transform.normalizeDegrees(nearestQuarter) else raw.rotationDegrees,
            scale = if (snapScale) 1f else raw.scale,
            positionX = if (snapX) 0f else raw.positionX,
            positionY = if (snapY) 0f else raw.positionY,
        ).sanitized()
        return Result(t, snapRotation, snapScale, snapX, snapY)
    }
}
