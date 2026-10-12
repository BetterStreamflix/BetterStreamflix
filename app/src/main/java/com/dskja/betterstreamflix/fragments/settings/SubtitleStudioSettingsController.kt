package com.dskja.betterstreamflix.fragments.settings

import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.player.SubtitleStudio
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlin.math.abs
import com.dskja.betterstreamflix.utils.getAlpha
import com.dskja.betterstreamflix.utils.getRgb
import com.dskja.betterstreamflix.utils.setAlpha
import com.dskja.betterstreamflix.utils.setRgb

/**
 * Settings → Accessibility → Subtitle Studio: per-profile size / color /
 * background with an in-settings preview strip.
 */
object SubtitleStudioSettingsController {

    private val sizeOptions = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    private val backgroundOptions = listOf(0, 128, 191, 255)

    fun bind(fragment: PreferenceFragmentCompat) {
        val context = fragment.requireContext()
        val presetPref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_PRESET")
        val sizePref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_SIZE")
        val colorPref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_FONT_COLOR")
        val bgPref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_BACKGROUND")
        val previewPref = fragment.findPreference<SubtitleStudioPreviewPreference>("SUBTITLE_STUDIO_PREVIEW")
        if (sizePref == null && colorPref == null && bgPref == null && presetPref == null) return

        fun refreshPreview() {
            previewPref?.refresh()
        }

        fun syncFromPrefs() {
            val matchedPreset = SubtitleStudio.presets.firstOrNull { SubtitleStudio.matchesCurrent(it) }
            if (matchedPreset != null) {
                presetPref?.value = matchedPreset.id
                presetPref?.summary = context.getString(matchedPreset.titleRes)
            } else {
                presetPref?.value = null
                presetPref?.summary = context.getString(R.string.subtitle_studio_preset_hint)
            }
            val size = UserPreferences.captionTextSize
            if (sizeOptions.any { abs(it - size) < 0.02f }) {
                sizePref?.value = closestSizeValue(size)
                sizePref?.summary = null
            } else {
                sizePref?.value = null
                sizePref?.summary = context.getString(R.string.subtitle_studio_custom_value, size)
            }
            val fg = UserPreferences.captionStyle.foregroundColor
            val mappedKey = colorKey(fg)
            val matchesPalette = colorFromKey(mappedKey)?.getRgb() == fg.getRgb()
            if (matchesPalette) {
                colorPref?.value = mappedKey
                colorPref?.summary = null
            } else {
                colorPref?.value = null
                colorPref?.summary = context.getString(
                    R.string.subtitle_studio_custom_value,
                    "#%06X".format(0xFFFFFF and fg.getRgb()),
                )
            }
            val alpha = UserPreferences.captionStyle.backgroundColor.getAlpha()
            if (backgroundOptions.any { abs(it - alpha) <= 8 }) {
                bgPref?.value = closestBackgroundValue(alpha)
                bgPref?.summary = null
            } else {
                bgPref?.value = null
                bgPref?.summary = context.getString(R.string.subtitle_studio_custom_value, alpha)
            }
            refreshPreview()
        }

        presetPref?.setOnPreferenceChangeListener { _, newValue ->
            val id = newValue as? String ?: return@setOnPreferenceChangeListener false
            val preset = SubtitleStudio.presets.firstOrNull { it.id == id }
                ?: return@setOnPreferenceChangeListener false
            SubtitleStudio.applyPreset(preset)
            syncFromPrefs()
            true
        }
        sizePref?.setOnPreferenceChangeListener { _, newValue ->
            val parsed = (newValue as? String)?.toFloatOrNull()
                ?: return@setOnPreferenceChangeListener false
            UserPreferences.captionTextSize = parsed
            syncFromPrefs()
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
            syncFromPrefs()
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
            syncFromPrefs()
            true
        }

        syncFromPrefs()
    }

    /** Re-sync ListPreferences + preview after returning from the player editor. */
    fun refresh(fragment: PreferenceFragmentCompat) {
        bind(fragment)
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
