package com.dskja.betterstreamflix.fragments.settings

import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.getAlpha
import com.dskja.betterstreamflix.utils.getRgb
import com.dskja.betterstreamflix.utils.setAlpha
import com.dskja.betterstreamflix.utils.setRgb

/**
 * Settings → Accessibility → Subtitle Studio: per-profile size / color /
 * background with an in-settings preview strip.
 */
object SubtitleStudioSettingsController {

    fun bind(fragment: PreferenceFragmentCompat) {
        val sizePref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_SIZE")
        val colorPref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_FONT_COLOR")
        val bgPref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_BACKGROUND")
        val previewPref = fragment.findPreference<SubtitleStudioPreviewPreference>("SUBTITLE_STUDIO_PREVIEW")
        if (sizePref == null && colorPref == null && bgPref == null) return

        fun refreshPreview() {
            previewPref?.refresh()
        }

        fun syncFromPrefs() {
            val size = UserPreferences.captionTextSize
            sizePref?.value = closestSizeValue(size)
            colorPref?.value = colorKey(UserPreferences.captionStyle.foregroundColor)
            bgPref?.value = closestBackgroundValue(
                UserPreferences.captionStyle.backgroundColor.getAlpha(),
            )
            refreshPreview()
        }

        sizePref?.setOnPreferenceChangeListener { _, newValue ->
            val parsed = (newValue as? String)?.toFloatOrNull()
                ?: return@setOnPreferenceChangeListener false
            UserPreferences.captionTextSize = parsed
            refreshPreview()
            true
        }
        colorPref?.setOnPreferenceChangeListener { _, newValue ->
            val color = colorFromKey(newValue as? String)
                ?: return@setOnPreferenceChangeListener false
            val current = UserPreferences.captionStyle
            UserPreferences.captionStyle = CaptionStyleCompat(
                current.foregroundColor.setRgb(color),
                current.backgroundColor,
                current.windowColor,
                current.edgeType,
                current.edgeColor,
                null,
            )
            refreshPreview()
            true
        }
        bgPref?.setOnPreferenceChangeListener { _, newValue ->
            val alpha = (newValue as? String)?.toIntOrNull()
                ?: return@setOnPreferenceChangeListener false
            val current = UserPreferences.captionStyle
            val bg = Color.BLACK.setAlpha(alpha.coerceIn(0, 255))
            UserPreferences.captionStyle = CaptionStyleCompat(
                current.foregroundColor,
                bg,
                current.windowColor,
                current.edgeType,
                current.edgeColor,
                null,
            )
            refreshPreview()
            true
        }

        syncFromPrefs()
    }

    private fun closestSizeValue(size: Float): String {
        val options = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val best = options.minByOrNull { kotlin.math.abs(it - size) } ?: 1f
        return if (best % 1f == 0f) best.toInt().toString() else best.toString()
    }

    private fun closestBackgroundValue(alpha: Int): String {
        val options = listOf(0, 128, 191, 255)
        return (options.minByOrNull { kotlin.math.abs(it - alpha) } ?: 128).toString()
    }

    private fun colorKey(argb: Int): String = when (argb.getRgb()) {
        Color.YELLOW.getRgb() -> "yellow"
        Color.CYAN.getRgb() -> "cyan"
        Color.GREEN.getRgb() -> "green"
        Color.BLACK.getRgb() -> "black"
        else -> "white"
    }

    private fun colorFromKey(key: String?): Int? = when (key) {
        "white" -> Color.WHITE
        "yellow" -> Color.YELLOW
        "cyan" -> Color.CYAN
        "green" -> Color.GREEN
        "black" -> Color.BLACK
        else -> null
    }
}
