package app.clippr.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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

    private class Layouts(val fill: StaticLayout, val stroke: StaticLayout?, val pad: Int)

    private fun basePaint(style: TextStyle, canvasHeight: Int, scale: Float, fonts: FontLibrary?) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = max(1f, style.sizeFraction * canvasHeight * scale)
        if (fonts != null) {
            typeface = fonts.typeface(style.fontId, style.bold)
            isFakeBoldText = style.bold && !fonts.hasRealBold(style.fontId)
        } else {
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (style.bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun build(style: TextStyle, canvasWidth: Int, canvasHeight: Int, scale: Float, fonts: FontLibrary?): Layouts {
        val fill = basePaint(style, canvasHeight, scale, fonts).apply {
            color = style.colorArgb
            if (style.shadow) setShadowLayer(textSize * 0.08f, 0f, textSize * 0.05f, 0xB3000000.toInt())
        }
        val strokeWidthPx = style.strokeWidth * fill.textSize
        val strokePaint = if (strokeWidthPx > 0f) {
            basePaint(style, canvasHeight, scale, fonts).apply {
                this.style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeWidth = strokeWidthPx
                color = style.strokeColorArgb
            }
        } else {
            null
        }
        val maxWidth = max(1, (canvasWidth * MAX_WIDTH_FRACTION * scale).toInt())
        val natural = style.text.lines().maxOfOrNull { ceil(fill.measureText(it)).toInt() } ?: 1
        val width = natural.coerceIn(1, maxWidth)
        val align = when (style.alignment) {
            TextAlignment.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        fun layout(p: TextPaint) = StaticLayout.Builder.obtain(style.text, 0, style.text.length, p, width)
            .setAlignment(align)
            .setIncludePad(true)
            .build()
        val hasBackground = style.backgroundArgb ushr 24 != 0
        val effectsPad = ceil(strokeWidthPx / 2f + (if (style.shadow) fill.textSize * 0.16f else 0f)).toInt()
        val bgPad = if (hasBackground) (fill.textSize * 0.35f).toInt() else 0
        return Layouts(layout(fill), strokePaint?.let(::layout), max(2, max(bgPad, effectsPad + 2)))
    }

    fun measure(style: TextStyle, canvasWidth: Int, canvasHeight: Int, scale: Float = 1f, fonts: FontLibrary? = null): Measured {
        if (style.text.isEmpty()) return Measured(1, 1)
        val l = build(style, canvasWidth, canvasHeight, scale, fonts)
        return Measured(l.fill.width + 2 * l.pad, l.fill.height + 2 * l.pad)
    }

    fun render(style: TextStyle, canvasWidth: Int, canvasHeight: Int, scale: Float = 1f, fonts: FontLibrary? = null): Bitmap {
        val l = build(style, canvasWidth, canvasHeight, scale, fonts)
        val bitmap = Bitmap.createBitmap(l.fill.width + 2 * l.pad, l.fill.height + 2 * l.pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (style.backgroundArgb ushr 24 != 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.backgroundArgb }
            val r = l.pad.toFloat()
            canvas.drawRoundRect(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), r, r, bg)
        }
        canvas.translate(l.pad.toFloat(), l.pad.toFloat())
        l.stroke?.draw(canvas)
        l.fill.draw(canvas)
        return bitmap
    }

    /** Text wraps at this fraction of the canvas width. */
    private const val MAX_WIDTH_FRACTION = 0.9f
}
