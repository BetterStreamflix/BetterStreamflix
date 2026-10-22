package com.dskja.betterstreamflix.fragments.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.TextView
import androidx.media3.ui.CaptionStyleCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.player.SubtitleStudio
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.getAlpha
import com.dskja.betterstreamflix.utils.getRgb

/** In-settings preview strip for Subtitle Studio light. */
class SubtitleStudioPreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_subtitle_studio_preview
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val window = holder.findViewById(R.id.ll_subtitle_studio_window)
        val tv = holder.findViewById(R.id.tv_subtitle_studio_preview) as? TextView
        paint(tv)
        paintWindow(window)
    }

    fun refresh() {
        notifyChanged()
    }

    companion object {
        fun paint(tv: TextView?) {
            tv ?: return
            val style = UserPreferences.captionStyle
            val sizeSp = (16f * UserPreferences.captionTextSize).coerceIn(12f, 36f)
            tv.text = SubtitleStudio.previewSampleText(tv.context)
            tv.setTextColor(style.foregroundColor)
            tv.textSize = sizeSp
            tv.typeface = UserPreferences.captionTypefaceOrDefault()
                ?: android.graphics.Typeface.DEFAULT
            // ST-015: dark text on fully transparent bg is unreadable — floor alpha.
            var bgColor = style.backgroundColor
            if (bgColor.getAlpha() == 0 && isDarkForeground(style.foregroundColor)) {
                bgColor = (0x66 shl 24) or (0xFFFFFF and Color.WHITE)
            }
            val bg = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 6.dp(tv.context).toFloat()
                // ST-006: hint EDGE_TYPE_OUTLINE (HIGH_CONTRAST) with a stroke.
                if (style.edgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE) {
                    setStroke(2.dp(tv.context), style.edgeColor)
                }
            }
            tv.background = bg
            // Drop-shadow edges preview best via a real shadow layer.
            if (style.edgeType == CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW) {
                tv.setShadowLayer(6f, 1.5f, 1.5f, style.edgeColor)
            } else {
                tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
            tv.setPadding(
                10.dp(tv.context),
                4.dp(tv.context),
                10.dp(tv.context),
                4.dp(tv.context),
            )
        }

        /** Window color = the full-line box the player draws behind cue text. */
        fun paintWindow(window: android.view.View?) {
            window ?: return
            val style = UserPreferences.captionStyle
            val stage = GradientDrawable().apply {
                setColor(
                    if (style.windowColor.getAlpha() > 0) {
                        style.windowColor
                    } else {
                        window.context.getColor(R.color.cinema_ink)
                    },
                )
                cornerRadius = 10.dp(window.context).toFloat()
            }
            window.background = stage
        }

        private fun isDarkForeground(argb: Int): Boolean {
            val rgb = argb.getRgb()
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            // Relative luminance threshold ~0.2
            return (0.299 * r + 0.587 * g + 0.114 * b) < 51
        }
    }
}
