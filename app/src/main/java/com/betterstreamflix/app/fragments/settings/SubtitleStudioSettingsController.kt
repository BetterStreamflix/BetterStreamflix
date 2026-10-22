package com.betterstreamflix.app.fragments.settings

import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.betterstreamflix.app.R
import com.betterstreamflix.app.player.SubtitleStudio
import com.betterstreamflix.app.utils.UserPreferences
import kotlin.math.abs
import com.betterstreamflix.app.utils.getAlpha
import com.betterstreamflix.app.utils.getRgb
import com.betterstreamflix.app.utils.setAlpha
import com.betterstreamflix.app.utils.setRgb

/**
 * Settings → Accessibility → Subtitle Studio: per-profile size / color /
 * background with an in-settings preview strip.
 */
object SubtitleStudioSettingsController {

    private val sizeOptions = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
    private val backgroundOptions = listOf(0, 128, 191, 255)

    fun bind(fragment: PreferenceFragmentCompat) {
        val context = fragment.requireContext()
        val presetPref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_PRESET")
        val sizePref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_SIZE")
        val colorPref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_FONT_COLOR")
        val bgPref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_BACKGROUND")
        val textAlphaPref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_TEXT_OPACITY")
        val edgePref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_EDGE")
        val fontPref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_FONT")
        val windowPref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_WINDOW")
        val marginPref = fragment.findPreference<ListPreference>("SUBTITLE_STUDIO_MARGIN")
        val previewPref = fragment.findPreference<SubtitleStudioPreviewPreference>("SUBTITLE_STUDIO_PREVIEW")
        if (sizePref == null && colorPref == null && bgPref == null && presetPref == null) return

        fun refreshPreview() {
            previewPref?.refresh()
        }

        fun syncFromPrefs() {
            val matchedPreset = SubtitleStudio.presets.firstOrNull { SubtitleStudio.matchesCurrent(it) }
            if (matchedPreset != null) {
                presetPref?.value = matchedPreset.id
                presetPref?.setSummaryText(context.getString(matchedPreset.titleRes))
            } else {
                presetPref?.value = null
                presetPref?.setSummaryText(context.getString(R.string.subtitle_studio_preset_hint))
            }
            val size = UserPreferences.captionTextSize
            if (sizeOptions.any { abs(it - size) < 0.02f }) {
                sizePref?.value = closestSizeValue(size)
                sizePref?.setSummaryText(sizePref?.entry)
            } else {
                sizePref?.value = null
                sizePref?.setSummaryText(
                    context.getString(R.string.subtitle_studio_custom_value, size),
                )
            }
            val fg = UserPreferences.captionStyle.foregroundColor
            val mappedKey = colorKey(fg)
            val matchesPalette = colorFromKey(mappedKey)?.getRgb() == fg.getRgb()
            if (matchesPalette) {
                colorPref?.value = mappedKey
                colorPref?.setSummaryText(colorPref?.entry)
            } else {
                colorPref?.value = null
                colorPref?.setSummaryText(
                    context.getString(
                        R.string.subtitle_studio_custom_value,
                        "#%06X".format(0xFFFFFF and fg.getRgb()),
                    ),
                )
            }
            val alpha = UserPreferences.captionStyle.backgroundColor.getAlpha()
            if (backgroundOptions.any { abs(it - alpha) <= 8 }) {
                bgPref?.value = closestBackgroundValue(alpha)
                bgPref?.setSummaryText(bgPref?.entry)
            } else {
                bgPref?.value = null
                bgPref?.setSummaryText(
                    context.getString(R.string.subtitle_studio_custom_value, alpha),
                )
            }
            textAlphaPref?.value = closestAlphaValue(
                UserPreferences.captionStyle.foregroundColor.getAlpha(),
            )
            textAlphaPref?.setSummaryText(textAlphaPref?.entry)
            val style = UserPreferences.captionStyle
            edgePref?.value = style.edgeType.toString()
            edgePref?.setSummaryText(edgePref?.entry)
            fontPref?.value = UserPreferences.captionTypefaceIndex.toString()
            fontPref?.setSummaryText(fontPref?.entry)
            windowPref?.value = closestBackgroundValue(style.windowColor.getAlpha())
            windowPref?.setSummaryText(windowPref?.entry)
            marginPref?.value = closestMarginValue(UserPreferences.captionMargin)
            marginPref?.setSummaryText(marginPref?.entry)
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
                UserPreferences.captionTypefaceOrDefault(),
            )
            syncFromPrefs()
            true
        }
        textAlphaPref?.setOnPreferenceChangeListener { _, newValue ->
            val alpha = (newValue as? String)?.toIntOrNull()
                ?: return@setOnPreferenceChangeListener false
            val current = UserPreferences.captionStyle
            UserPreferences.captionStyle = CaptionStyleCompat(
                current.foregroundColor.setAlpha(alpha.coerceIn(0, 255)),
                current.backgroundColor,
                current.windowColor,
                current.edgeType,
                current.edgeColor,
                UserPreferences.captionTypefaceOrDefault(),
            )
            syncFromPrefs()
            true
        }
        edgePref?.setOnPreferenceChangeListener { _, newValue ->
            val edge = (newValue as? String)?.toIntOrNull()
                ?: return@setOnPreferenceChangeListener false
            val current = UserPreferences.captionStyle
            UserPreferences.captionStyle = CaptionStyleCompat(
                current.foregroundColor,
                current.backgroundColor,
                current.windowColor,
                edge.coerceIn(0, 4),
                if (edge == CaptionStyleCompat.EDGE_TYPE_NONE) current.edgeColor else Color.BLACK,
                UserPreferences.captionTypefaceOrDefault(),
            )
            syncFromPrefs()
            true
        }
        fontPref?.setOnPreferenceChangeListener { _, newValue ->
            val index = (newValue as? String)?.toIntOrNull()
                ?: return@setOnPreferenceChangeListener false
            UserPreferences.captionTypefaceIndex = index
            // captionStyle re-reads the persisted index — push typeface into the live style.
            val current = UserPreferences.captionStyle
            UserPreferences.captionStyle = CaptionStyleCompat(
                current.foregroundColor,
                current.backgroundColor,
                current.windowColor,
                current.edgeType,
                current.edgeColor,
                UserPreferences.captionTypefaceOrDefault(),
            )
            syncFromPrefs()
            true
        }
        windowPref?.setOnPreferenceChangeListener { _, newValue ->
            val alpha = (newValue as? String)?.toIntOrNull()
                ?: return@setOnPreferenceChangeListener false
            val current = UserPreferences.captionStyle
            UserPreferences.captionStyle = CaptionStyleCompat(
                current.foregroundColor,
                current.backgroundColor,
                Color.BLACK.setAlpha(alpha.coerceIn(0, 255)),
                current.edgeType,
                current.edgeColor,
                UserPreferences.captionTypefaceOrDefault(),
            )
            syncFromPrefs()
            true
        }
        marginPref?.setOnPreferenceChangeListener { _, newValue ->
            val margin = (newValue as? String)?.toIntOrNull()
                ?: return@setOnPreferenceChangeListener false
            UserPreferences.captionMargin = margin.coerceIn(0, 200)
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
        val options = sizeOptions
        val best = options.minByOrNull { kotlin.math.abs(it - size) } ?: 1f
        return if (best % 1f == 0f) best.toInt().toString() else best.toString()
    }

    private fun closestBackgroundValue(alpha: Int): String {
        val options = listOf(0, 128, 191, 255)
        return (options.minByOrNull { kotlin.math.abs(it - alpha) } ?: 128).toString()
    }

    private fun closestAlphaValue(alpha: Int): String {
        val options = listOf(255, 217, 179, 128)
        return (options.minByOrNull { kotlin.math.abs(it - alpha) } ?: 255).toString()
    }

    private fun closestMarginValue(margin: Int): String {
        val options = listOf(8, 24, 48, 80)
        return (options.minByOrNull { kotlin.math.abs(it - margin) } ?: 24).toString()
    }

    private fun colorKey(argb: Int): String = when (argb.getRgb()) {
        Color.YELLOW.getRgb() -> "yellow"
        Color.CYAN.getRgb() -> "cyan"
        Color.GREEN.getRgb() -> "green"
        Color.BLUE.getRgb() -> "blue"
        Color.MAGENTA.getRgb() -> "magenta"
        Color.RED.getRgb() -> "red"
        Color.BLACK.getRgb() -> "black"
        else -> "white"
    }

    private fun colorFromKey(key: String?): Int? = when (key) {
        "white" -> Color.WHITE
        "yellow" -> Color.YELLOW
        "cyan" -> Color.CYAN
        "green" -> Color.GREEN
        "blue" -> Color.BLUE
        "magenta" -> Color.MAGENTA
        "red" -> Color.RED
        "black" -> Color.BLACK
        else -> null
    }
}
