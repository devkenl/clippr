package app.clippr.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.clippr.core.model.TextAlignment
import app.clippr.core.model.TextStyle
import kotlin.math.ceil
import kotlin.math.max

/**
 * Renders a [TextStyle] to a bitmap. The same renderer produces the overlay used by the preview
 * player, the exporter, and the size of the selection box in the editor, so text looks the same
 * everywhere.
 *
 * Sizes are relative to the canvas height ([TextStyle.sizeFraction]), so a title occupies the
 * same share of the frame at any preview or export resolution.
 */
object TextRenderer {

    data class Measured(val widthPx: Int, val heightPx: Int)

    private fun paint(style: TextStyle, canvasHeight: Int, scale: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = style.colorArgb
        textSize = max(1f, style.sizeFraction * canvasHeight * scale)
        typeface = Typeface.create(Typeface.DEFAULT, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(style: TextStyle, canvasWidth: Int, canvasHeight: Int, scale: Float): StaticLayout {
        val p = paint(style, canvasHeight, scale)
        val maxWidth = max(1, (canvasWidth * MAX_WIDTH_FRACTION * scale).toInt())
        val natural = style.text.lines().maxOfOrNull { ceil(p.measureText(it)).toInt() } ?: 1
        val width = natural.coerceIn(1, maxWidth)
        val align = when (style.alignment) {
            TextAlignment.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        return StaticLayout.Builder.obtain(style.text, 0, style.text.length, p, width)
            .setAlignment(align)
            .setIncludePad(true)
            .build()
    }

    private fun padding(style: TextStyle, canvasHeight: Int, scale: Float): Int =
        if (style.backgroundArgb ushr 24 != 0) (style.sizeFraction * canvasHeight * scale * 0.35f).toInt().coerceAtLeast(2) else 2

    fun measure(style: TextStyle, canvasWidth: Int, canvasHeight: Int, scale: Float = 1f): Measured {
        if (style.text.isEmpty()) return Measured(1, 1)
        val l = layout(style, canvasWidth, canvasHeight, scale)
        val pad = padding(style, canvasHeight, scale)
        return Measured(l.width + 2 * pad, l.height + 2 * pad)
    }

    fun render(style: TextStyle, canvasWidth: Int, canvasHeight: Int, scale: Float = 1f): Bitmap {
        val l = layout(style, canvasWidth, canvasHeight, scale)
        val pad = padding(style, canvasHeight, scale)
        val bitmap = Bitmap.createBitmap(l.width + 2 * pad, l.height + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (style.backgroundArgb ushr 24 != 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.backgroundArgb }
            val r = pad.toFloat()
            canvas.drawRoundRect(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), r, r, bg)
        }
        canvas.translate(pad.toFloat(), pad.toFloat())
        l.draw(canvas)
        return bitmap
    }

    /** Text wraps at this fraction of the canvas width. */
    private const val MAX_WIDTH_FRACTION = 0.9f
}
