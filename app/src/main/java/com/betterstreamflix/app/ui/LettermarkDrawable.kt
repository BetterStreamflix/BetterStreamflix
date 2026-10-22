package com.betterstreamflix.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * Deterministic lettermark fallback: rounded plate tinted by name hash with the
 * first letter centered. Used for channels and providers without a usable logo.
 */
class LettermarkDrawable(name: String) : Drawable() {

    private val initial: String =
        name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paletteColor(name)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val radius = bounds.height() * 0.22f
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        val size = bounds.height() * 0.52f
        textPaint.textSize = size
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initial, cx, cy, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        textPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Muted dark plate derived from the name — stable across calls. */
        fun paletteColor(name: String): Int {
            val hue = (name.fold(0) { acc, c -> acc * 31 + c.code } and 0x7FFFFFFF) % 360
            return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.42f, 0.32f))
        }
    }
}
