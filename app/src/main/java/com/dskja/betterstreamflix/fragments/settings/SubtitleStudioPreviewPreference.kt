package com.dskja.betterstreamflix.fragments.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp

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
            tv.text = tv.context.getString(R.string.subtitle_studio_preview_sample)
            tv.setTextColor(style.foregroundColor)
            tv.textSize = sizeSp
            val bg = GradientDrawable().apply {
                setColor(style.backgroundColor)
                cornerRadius = 10.dp(tv.context).toFloat()
            }
            tv.background = bg
            tv.setPadding(
                16.dp(tv.context),
                18.dp(tv.context),
                16.dp(tv.context),
                18.dp(tv.context),
            )
        }
    }
}
