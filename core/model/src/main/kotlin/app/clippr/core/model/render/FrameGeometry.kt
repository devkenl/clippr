package app.clippr.core.model.render

import app.clippr.core.model.Crop
import app.clippr.core.model.FitMode
import app.clippr.core.model.Transform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A 2D affine transform mapping `(x, y)` to `(a*x + b*y + c, d*x + e*y + f)`.
 * [values] uses the row-major 3x3 order expected by `android.graphics.Matrix.setValues`.
 */
data class Affine(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 0f,
    val e: Float = 1f,
    val f: Float = 0f,
) {
    /** Returns `this ∘ other`: [other] is applied first. */
    operator fun times(other: Affine): Affine = Affine(
        a = a * other.a + b * other.d,
        b = a * other.b + b * other.e,
        c = a * other.c + b * other.f + c,
        d = d * other.a + e * other.d,
        e = d * other.b + e * other.e,
        f = d * other.c + e * other.f + f,
    )

    fun mapX(x: Float, y: Float): Float = a * x + b * y + c
    fun mapY(x: Float, y: Float): Float = d * x + e * y + f

    val values: FloatArray get() = floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f)

    val isIdentity: Boolean get() = this == IDENTITY

    companion object {
        val IDENTITY = Affine()
        fun scale(sx: Float, sy: Float) = Affine(a = sx, e = sy)
        fun translate(tx: Float, ty: Float) = Affine(c = tx, f = ty)

        /** Counter-clockwise rotation by [degrees] in a y-up coordinate system. */
        fun rotateCcw(degrees: Float): Affine {
            val r = Math.toRadians(degrees.toDouble())
            val cs = cos(r).toFloat()
            val sn = sin(r).toFloat()
            return Affine(a = cs, b = -sn, d = sn, e = cs)
        }
    }
}

/** A rotated rectangle in canvas pixels (y down). [rotationDegrees] is clockwise. */
data class ClipFrame(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotationDegrees: Float,
) {
    /** Whether the canvas point lies inside the rotated rectangle. */
    fun contains(x: Float, y: Float): Boolean {
        val r = Math.toRadians(-rotationDegrees.toDouble())
        val dx = x - centerX
        val dy = y - centerY
        val lx = (dx * cos(r) - dy * sin(r)).toFloat()
        val ly = (dx * sin(r) + dy * cos(r)).toFloat()
        return abs(lx) <= width / 2 && abs(ly) <= height / 2
    }
}

/**
 * Geometry shared by the renderer (which needs a matrix in normalized device coordinates) and the
 * editor overlay (which draws selection handles in canvas pixels). Both are derived from the same
 * [Transform], so the handles always sit exactly on the rendered frame.
 *
 * Rendering model for a visual clip, in order:
 * 1. Crop the source by [Crop] fractions.
 * 2. Fit (letterbox) or fill (crop to canvas aspect) the result into the canvas.
 * 3. Flip, scale about the canvas centre, rotate clockwise, then translate by the normalized position.
 */
object FrameGeometry {

    /**
     * Matrix to apply in NDC (x right, y up, both in `[-1, 1]`) after step 2 above.
     * [canvasAspect] is width / height. Rotation is aspect-corrected so that frames rotate
     * rigidly in pixel space instead of shearing.
     */
    fun ndcMatrix(transform: Transform, canvasAspect: Float): Affine {
        if (transform.isIdentityPlacement) return Affine.IDENTITY
        val s = transform.scale
        // Flips mirror the frame about its own centre, before rotation and positioning.
        val fx = if (transform.flipHorizontal) -1f else 1f
        val fy = if (transform.flipVertical) -1f else 1f
        return Affine.translate(transform.positionX, transform.positionY) *
            Affine.scale(1f / canvasAspect, 1f) *
            Affine.rotateCcw(-transform.rotationDegrees) *
            Affine.scale(canvasAspect, 1f) *
            Affine.scale(s * fx, s * fy)
    }

    /** Size of a source frame after cropping. */
    fun croppedSize(sourceWidth: Int, sourceHeight: Int, crop: Crop): Pair<Float, Float> {
        val c = crop.sanitized()
        return sourceWidth * (1f - c.left - c.right) to sourceHeight * (1f - c.top - c.bottom)
    }

    /**
     * Rectangle covered by a visual clip on a [canvasWidth] x [canvasHeight] canvas. For
     * [FitMode.FILL] the frame is cropped to the canvas aspect, so the rectangle is the canvas.
     */
    fun clipFrame(
        sourceWidth: Int,
        sourceHeight: Int,
        transform: Transform,
        canvasWidth: Float,
        canvasHeight: Float,
    ): ClipFrame {
        val (cw, ch) = croppedSize(max(sourceWidth, 1), max(sourceHeight, 1), transform.crop)
        val (w, h) = when (transform.fit) {
            FitMode.FILL -> canvasWidth to canvasHeight
            FitMode.FIT -> {
                val k = min(canvasWidth / cw, canvasHeight / ch)
                cw * k to ch * k
            }
        }
        return ClipFrame(
            centerX = canvasWidth / 2f * (1f + transform.positionX),
            centerY = canvasHeight / 2f * (1f - transform.positionY),
            width = w * transform.scale,
            height = h * transform.scale,
            rotationDegrees = transform.rotationDegrees,
        )
    }

    /** Normalized position for a frame centre at canvas pixel coordinates. */
    fun positionForCenter(centerX: Float, centerY: Float, canvasWidth: Float, canvasHeight: Float): Pair<Float, Float> =
        (centerX / (canvasWidth / 2f) - 1f) to (1f - centerY / (canvasHeight / 2f))

    /** Crop rectangle in NDC (left, right, bottom, top), the convention of Media3's Crop effect. */
    fun cropNdc(crop: Crop): FloatArray {
        val c = crop.sanitized()
        return floatArrayOf(-1f + 2f * c.left, 1f - 2f * c.right, -1f + 2f * c.bottom, 1f - 2f * c.top)
    }
}
