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
        paint(holder.findViewById(R.id.tv_subtitle_studio_preview) as? TextView)
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
            // ST-015: dark text on fully transparent bg is unreadable — floor alpha.
            var bgColor = style.backgroundColor
            if (bgColor.getAlpha() == 0 && isDarkForeground(style.foregroundColor)) {
                bgColor = (0x66 shl 24) or (0xFFFFFF and Color.WHITE)
            }
            val bg = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 10.dp(tv.context).toFloat()
                // ST-006: hint EDGE_TYPE_OUTLINE (HIGH_CONTRAST) with a stroke.
                if (style.edgeType == CaptionStyleCompat.EDGE_TYPE_OUTLINE) {
                    setStroke(2.dp(tv.context), style.edgeColor)
                }
            }
            tv.background = bg
            tv.setPadding(
                16.dp(tv.context),
                18.dp(tv.context),
                16.dp(tv.context),
                18.dp(tv.context),
            )
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
