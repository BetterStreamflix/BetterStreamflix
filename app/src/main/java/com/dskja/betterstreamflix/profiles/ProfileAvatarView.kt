package com.dskja.betterstreamflix.profiles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
 * Premium gradient orb with geometric motif + monogram.
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
            val base = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(palette.start, palette.end),
            ).apply { shape = GradientDrawable.OVAL }
            val gloss = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(palette.highlight, 0x00000000),
            ).apply { shape = GradientDrawable.OVAL }
            return LayerDrawable(arrayOf(base, gloss))
        }
    }

    private class MotifOrbView(context: Context) : android.view.View(context) {
        var palette: ProfileAvatarStyle.Palette = ProfileAvatarStyle.paletteFor("copper")

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val motifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(w, h) / 2f

            fillPaint.shader = android.graphics.LinearGradient(
                0f, 0f, w, h,
                palette.start, palette.end,
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, fillPaint)

            glossPaint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h * 0.55f,
                palette.highlight, 0x00000000,
                android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, glossPaint)

            motifPaint.color = palette.onOrb and 0x00FFFFFF or 0x33000000
            motifPaint.strokeWidth = r * 0.06f
            path.reset()
            when (palette.motif) {
                ProfileAvatarStyle.Motif.ARC -> {
                    canvas.drawArc(
                        cx - r * 0.72f, cy - r * 0.72f,
                        cx + r * 0.72f, cy + r * 0.72f,
                        210f, 120f, false, motifPaint,
                    )
                }
                ProfileAvatarStyle.Motif.RING -> {
                    motifPaint.style = Paint.Style.STROKE
                    canvas.drawCircle(cx, cy, r * 0.62f, motifPaint)
                    motifPaint.style = Paint.Style.STROKE
                }
                ProfileAvatarStyle.Motif.DIAMOND -> {
                    path.moveTo(cx, cy - r * 0.55f)
                    path.lineTo(cx + r * 0.4f, cy)
                    path.lineTo(cx, cy + r * 0.55f)
                    path.lineTo(cx - r * 0.4f, cy)
                    path.close()
                    canvas.drawPath(path, motifPaint)
                }
                ProfileAvatarStyle.Motif.BARS -> {
                    val gap = r * 0.18f
                    canvas.drawLine(cx - gap, cy - r * 0.4f, cx - gap, cy + r * 0.4f, motifPaint)
                    canvas.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.5f, motifPaint)
                    canvas.drawLine(cx + gap, cy - r * 0.4f, cx + gap, cy + r * 0.4f, motifPaint)
                }
                ProfileAvatarStyle.Motif.SPARK -> {
                    canvas.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.5f, motifPaint)
                    canvas.drawLine(cx - r * 0.45f, cy, cx + r * 0.45f, cy, motifPaint)
                    canvas.drawLine(
                        cx - r * 0.32f, cy - r * 0.32f,
                        cx + r * 0.32f, cy + r * 0.32f, motifPaint,
                    )
                }
                ProfileAvatarStyle.Motif.CRESCENT -> {
                    canvas.drawArc(
                        cx - r * 0.55f, cy - r * 0.55f,
                        cx + r * 0.35f, cy + r * 0.55f,
                        300f, 220f, false, motifPaint,
                    )
                }
            }
        }
    }
}
