package com.dskja.betterstreamflix.profiles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.dskja.betterstreamflix.R

/**
 * Premium illustrated orb: multi-stop gradient, vignette, rim light, silhouette motif + monogram.
 */
class ProfileAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val orb = MotifOrbView(context)
    private val monogram = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(0xFFFFFFFF.toInt())
        typeface = ResourcesCompat.getFont(context, R.font.syne)
            ?: Typeface.create("sans-serif-medium", Typeface.BOLD)
        setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
    }

    private var avatarKey: String = ProfileManager.avatarKeys.first()
    private var displayName: String = ""

    init {
        addView(orb, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(monogram, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    fun bind(profile: UserProfile, textSizeSp: Float = 28f) {
        bind(profile.avatarKey, profile.displayName, textSizeSp)
    }

    fun bind(avatarKey: String, displayName: String, textSizeSp: Float = 28f) {
        this.avatarKey = avatarKey
        this.displayName = displayName
        val palette = ProfileAvatarStyle.paletteFor(avatarKey)
        orb.palette = palette
        orb.invalidate()
        monogram.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        monogram.setTextColor(palette.onOrb)
        monogram.text = ProfileAvatarStyle.initialFor(displayName)
        background = null
    }

    /** Compact binder for chips / list rows without a custom view hierarchy. */
    companion object {
        fun gradientDrawable(avatarKey: String): GradientDrawable {
            val palette = ProfileAvatarStyle.paletteFor(avatarKey)
            return GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(palette.start, palette.end),
            ).apply {
                shape = GradientDrawable.OVAL
            }
        }

        fun layerOrb(avatarKey: String): LayerDrawable {
            val palette = ProfileAvatarStyle.paletteFor(avatarKey)
            val colors = if (palette.mid != 0) {
                intArrayOf(palette.start, palette.mid, palette.end)
            } else {
                intArrayOf(palette.start, palette.end)
            }
            val base = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                colors,
            ).apply { shape = GradientDrawable.OVAL }
            val gloss = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(palette.highlight, 0x00000000),
            ).apply { shape = GradientDrawable.OVAL }
            return LayerDrawable(arrayOf(base, gloss))
        }
    }

    private class MotifOrbView(context: Context) : android.view.View(context) {
        var palette: ProfileAvatarStyle.Palette = ProfileAvatarStyle.paletteFor("crimson")

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val motifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val silhouettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(w, h) / 2f

            val mid = if (palette.mid != 0) palette.mid else blend(palette.start, palette.end, 0.45f)
            fillPaint.shader = android.graphics.LinearGradient(
                0f, 0f, w * 0.92f, h,
                intArrayOf(palette.start, mid, palette.end),
                floatArrayOf(0f, 0.42f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, fillPaint)

            // Soft top gloss.
            glossPaint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h * 0.55f,
                intArrayOf(palette.highlight, 0x22000000, 0x00000000),
                floatArrayOf(0f, 0.35f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, glossPaint)

            // Inner vignette for depth.
            vignettePaint.shader = RadialGradient(
                cx, cy * 0.92f, r * 1.05f,
                intArrayOf(0x00000000, 0x00000000, 0x55000000),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, vignettePaint)

            // Inner shadow ring.
            shadowPaint.strokeWidth = r * 0.08f
            shadowPaint.color = 0x33000000
            canvas.drawCircle(cx, cy, r * 0.94f, shadowPaint)

            // Rim light (sweep accent).
            rimPaint.strokeWidth = r * 0.045f
            rimPaint.shader = SweepGradient(
                cx, cy,
                intArrayOf(0x00FFFFFF, lighten(palette.highlight, 0.55f), 0x00FFFFFF, 0x22FFFFFF),
                floatArrayOf(0f, 0.18f, 0.55f, 1f),
            )
            canvas.drawCircle(cx, cy, r * 0.97f, rimPaint)
            rimPaint.shader = null

            drawMotif(canvas, cx, cy, r)
        }

        private fun drawMotif(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            motifPaint.color = palette.onOrb and 0x00FFFFFF or 0x42000000
            motifPaint.strokeWidth = r * 0.07f
            motifPaint.style = Paint.Style.STROKE
            silhouettePaint.color = palette.onOrb and 0x00FFFFFF or 0x2E000000
            path.reset()

            when (palette.motif) {
                ProfileAvatarStyle.Motif.FACE -> drawFace(canvas, cx, cy, r)
                ProfileAvatarStyle.Motif.PORTRAIT -> drawPortrait(canvas, cx, cy, r)
                ProfileAvatarStyle.Motif.ARC -> {
                    canvas.drawArc(
                        cx - r * 0.82f, cy - r * 0.82f,
                        cx + r * 0.82f, cy + r * 0.82f,
                        205f, 130f, false, motifPaint,
                    )
                }
                ProfileAvatarStyle.Motif.RING -> {
                    canvas.drawCircle(cx, cy, r * 0.72f, motifPaint)
                    canvas.drawCircle(cx, cy, r * 0.48f, motifPaint)
                }
                ProfileAvatarStyle.Motif.DIAMOND -> {
                    path.moveTo(cx, cy - r * 0.68f)
                    path.lineTo(cx + r * 0.5f, cy)
                    path.lineTo(cx, cy + r * 0.68f)
                    path.lineTo(cx - r * 0.5f, cy)
                    path.close()
                    canvas.drawPath(path, motifPaint)
                }
                ProfileAvatarStyle.Motif.BARS -> {
                    val gap = r * 0.22f
                    canvas.drawLine(cx - gap, cy - r * 0.52f, cx - gap, cy + r * 0.52f, motifPaint)
                    canvas.drawLine(cx, cy - r * 0.62f, cx, cy + r * 0.62f, motifPaint)
                    canvas.drawLine(cx + gap, cy - r * 0.52f, cx + gap, cy + r * 0.52f, motifPaint)
                }
                ProfileAvatarStyle.Motif.SPARK -> {
                    canvas.drawLine(cx, cy - r * 0.62f, cx, cy + r * 0.62f, motifPaint)
                    canvas.drawLine(cx - r * 0.55f, cy, cx + r * 0.55f, cy, motifPaint)
                    canvas.drawLine(
                        cx - r * 0.4f, cy - r * 0.4f,
                        cx + r * 0.4f, cy + r * 0.4f, motifPaint,
                    )
                    canvas.drawLine(
                        cx + r * 0.4f, cy - r * 0.4f,
                        cx - r * 0.4f, cy + r * 0.4f, motifPaint,
                    )
                }
                ProfileAvatarStyle.Motif.CHEVRON -> {
                    path.moveTo(cx - r * 0.48f, cy - r * 0.18f)
                    path.lineTo(cx, cy - r * 0.55f)
                    path.lineTo(cx + r * 0.48f, cy - r * 0.18f)
                    canvas.drawPath(path, motifPaint)
                    path.reset()
                    path.moveTo(cx - r * 0.48f, cy + r * 0.22f)
                    path.lineTo(cx, cy - r * 0.15f)
                    path.lineTo(cx + r * 0.48f, cy + r * 0.22f)
                    canvas.drawPath(path, motifPaint)
                }
                ProfileAvatarStyle.Motif.WAVE -> {
                    path.moveTo(cx - r * 0.62f, cy)
                    path.cubicTo(
                        cx - r * 0.3f, cy - r * 0.45f,
                        cx + r * 0.05f, cy + r * 0.45f,
                        cx + r * 0.62f, cy,
                    )
                    canvas.drawPath(path, motifPaint)
                    path.reset()
                    path.moveTo(cx - r * 0.62f, cy + r * 0.22f)
                    path.cubicTo(
                        cx - r * 0.3f, cy - r * 0.2f,
                        cx + r * 0.05f, cy + r * 0.65f,
                        cx + r * 0.62f, cy + r * 0.22f,
                    )
                    canvas.drawPath(path, motifPaint)
                }
                ProfileAvatarStyle.Motif.ORBIT -> {
                    canvas.drawCircle(cx, cy, r * 0.58f, motifPaint)
                    canvas.drawCircle(cx + r * 0.42f, cy - r * 0.28f, r * 0.12f, motifPaint)
                    canvas.drawCircle(cx - r * 0.38f, cy + r * 0.34f, r * 0.09f, motifPaint)
                }
            }
        }

        /** Stylized head + shoulders silhouette (Netflix-like illustrated avatar). */
        private fun drawFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val headCy = cy - r * 0.18f
            val headR = r * 0.32f
            // Soft halo behind head.
            silhouettePaint.color = palette.onOrb and 0x00FFFFFF or 0x18000000
            canvas.drawCircle(cx, headCy, headR * 1.18f, silhouettePaint)
            silhouettePaint.color = palette.onOrb and 0x00FFFFFF or 0x36000000
            canvas.drawCircle(cx, headCy, headR, silhouettePaint)

            // Shoulders / torso ellipse clipped by bottom of orb.
            path.reset()
            path.addOval(
                cx - r * 0.62f,
                cy + r * 0.18f,
                cx + r * 0.62f,
                cy + r * 1.05f,
                Path.Direction.CW,
            )
            canvas.drawPath(path, silhouettePaint)

            // Subtle neck bridge.
            canvas.drawRoundRect(
                cx - r * 0.12f,
                headCy + headR * 0.65f,
                cx + r * 0.12f,
                cy + r * 0.28f,
                r * 0.06f,
                r * 0.06f,
                silhouettePaint,
            )

            // Rim accent around head.
            motifPaint.strokeWidth = r * 0.035f
            motifPaint.color = palette.onOrb and 0x00FFFFFF or 0x55000000
            canvas.drawCircle(cx, headCy, headR * 1.05f, motifPaint)
        }

        /** Abstract portrait: offset bust + contour, reads as a character tile. */
        private fun drawPortrait(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val headCx = cx - r * 0.06f
            val headCy = cy - r * 0.22f
            val headR = r * 0.28f

            silhouettePaint.color = palette.onOrb and 0x00FFFFFF or 0x14000000
            canvas.drawCircle(cx + r * 0.08f, cy + r * 0.1f, r * 0.72f, silhouettePaint)

            silhouettePaint.color = palette.onOrb and 0x00FFFFFF or 0x3A000000
            canvas.drawCircle(headCx, headCy, headR, silhouettePaint)

            path.reset()
            path.moveTo(cx - r * 0.55f, cy + r * 0.95f)
            path.quadTo(cx - r * 0.5f, cy + r * 0.22f, headCx - headR * 0.85f, headCy + headR * 0.7f)
            path.lineTo(headCx + headR * 0.9f, headCy + headR * 0.75f)
            path.quadTo(cx + r * 0.58f, cy + r * 0.28f, cx + r * 0.62f, cy + r * 0.95f)
            path.close()
            canvas.drawPath(path, silhouettePaint)

            motifPaint.strokeWidth = r * 0.04f
            motifPaint.color = palette.onOrb and 0x00FFFFFF or 0x48000000
            canvas.drawArc(
                headCx - headR * 1.35f,
                headCy - headR * 1.35f,
                headCx + headR * 1.35f,
                headCy + headR * 1.35f,
                200f, 110f, false, motifPaint,
            )
        }

        private fun blend(a: Int, b: Int, t: Float): Int {
            val ar = (a shr 16) and 0xFF
            val ag = (a shr 8) and 0xFF
            val ab = a and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            val r = (ar + ((br - ar) * t)).toInt().coerceIn(0, 255)
            val g = (ag + ((bg - ag) * t)).toInt().coerceIn(0, 255)
            val bl = (ab + ((bb - ab) * t)).toInt().coerceIn(0, 255)
            return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
        }

        private fun lighten(color: Int, amount: Float): Int {
            val a = (color ushr 24) and 0xFF
            val r = ((color shr 16) and 0xFF)
            val g = ((color shr 8) and 0xFF)
            val b = (color and 0xFF)
            val nr = (r + ((255 - r) * amount)).toInt().coerceIn(0, 255)
            val ng = (g + ((255 - g) * amount)).toInt().coerceIn(0, 255)
            val nb = (b + ((255 - b) * amount)).toInt().coerceIn(0, 255)
            return (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }
}
