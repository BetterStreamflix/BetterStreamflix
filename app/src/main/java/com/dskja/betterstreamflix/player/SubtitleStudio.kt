package com.dskja.betterstreamflix.player

import android.content.Context
import android.graphics.Color
import android.text.Layout
import android.view.View
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.getAlpha
import com.dskja.betterstreamflix.utils.getRgb

/**
 * Subtitle Studio light — per-profile size / color / background with a live
 * player preview. Wraps the existing caption prefs without replacing the full
 * edge/window editor.
 *
 * Kids profiles use the same caption defaults as adult (ST-016) — no separate
 * kids caption seed; per-profile scoped prefs still isolate customizations.
 */
object SubtitleStudio {

    /** English sample line; used when locale language is `en` (ST-007). */
    const val PREVIEW_SAMPLE_EN: String = "Subtitle Studio — sample line"

    /** Preview text: English constant for `en*`, otherwise localized string. */
    fun previewSampleText(context: Context): CharSequence {
        val lang = java.util.Locale.getDefault().language
        return if (lang.equals("en", ignoreCase = true)) {
            PREVIEW_SAMPLE_EN
        } else {
            context.getString(R.string.subtitle_studio_preview_sample)
        }
    }

    /** ARGB helpers that stay unit-test friendly (no android.graphics.Color mocks). */
    private fun argb(alpha: Int, rgb: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)

    data class LightPreset(
        val id: String,
        val titleRes: Int,
        val textSize: Float,
        val fontColor: Int,
        val backgroundColor: Int,
        val edgeType: Int = CaptionStyleCompat.EDGE_TYPE_NONE,
        val edgeColor: Int = 0xFF000000.toInt(),
        val windowColor: Int = Color.TRANSPARENT,
        val typefaceIndex: Int = 0,
    )

    val CLASSIC = LightPreset(
        id = "classic",
        titleRes = com.dskja.betterstreamflix.R.string.subtitle_studio_preset_classic,
        textSize = 1f,
        fontColor = 0xFFFFFFFF.toInt(),
        backgroundColor = argb(128, 0x000000),
    )

    val YELLOW_LARGE = LightPreset(
        id = "yellow_large",
        titleRes = com.dskja.betterstreamflix.R.string.subtitle_studio_preset_yellow_large,
        textSize = 1.25f,
        fontColor = 0xFFFFFF00.toInt(),
        backgroundColor = argb(180, 0x000000),
    )

    val HIGH_CONTRAST = LightPreset(
        id = "high_contrast",
        titleRes = com.dskja.betterstreamflix.R.string.subtitle_studio_preset_high_contrast,
        textSize = 1.5f,
        fontColor = 0xFFFFFFFF.toInt(),
        backgroundColor = argb(220, 0x000000),
        edgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE,
        edgeColor = 0xFF000000.toInt(),
    )

    /** Broadcast look — clean white with a soft drop shadow, no boxes. */
    val CINEMA = LightPreset(
        id = "cinema",
        titleRes = com.dskja.betterstreamflix.R.string.subtitle_studio_preset_cinema,
        textSize = 1f,
        fontColor = 0xFFFFFFFF.toInt(),
        backgroundColor = Color.TRANSPARENT,
        edgeType = CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
        edgeColor = 0xC0000000.toInt(),
    )

    /** Anime / fast-cut readability — bright text, hard outline, no background. */
    val OUTLINE = LightPreset(
        id = "outline",
        titleRes = com.dskja.betterstreamflix.R.string.subtitle_studio_preset_outline,
        textSize = 1.1f,
        fontColor = 0xFFFFFFFF.toInt(),
        backgroundColor = Color.TRANSPARENT,
        edgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE,
        edgeColor = 0xFF000000.toInt(),
    )

    /** Long-form readability — serif face, cream ink, deep translucent box. */
    val SERIF = LightPreset(
        id = "serif",
        titleRes = com.dskja.betterstreamflix.R.string.subtitle_studio_preset_serif,
        textSize = 1f,
        fontColor = 0xFFF5E6D3.toInt(),
        backgroundColor = argb(200, 0x0A0A0C),
        typefaceIndex = 2,
    )

    /** Dark-room viewing — dim amber, no box, barely lifts the black floor. */
    val NIGHT = LightPreset(
        id = "night",
        titleRes = com.dskja.betterstreamflix.R.string.subtitle_studio_preset_night,
        textSize = 0.9f,
        fontColor = 0xC0FFC9A0.toInt(),
        backgroundColor = Color.TRANSPARENT,
        edgeType = CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
        edgeColor = 0x99000000.toInt(),
    )

    /** Bright scenes / HDR — fully opaque black box for guaranteed separation. */
    val BOXED = LightPreset(
        id = "boxed",
        titleRes = com.dskja.betterstreamflix.R.string.subtitle_studio_preset_boxed,
        textSize = 1f,
        fontColor = 0xFFFFFFFF.toInt(),
        backgroundColor = 0xFF000000.toInt(),
    )

    /** Low-vision readability — largest size, warm yellow, hard outline. */
    val ACCESS = LightPreset(
        id = "access",
        titleRes = com.dskja.betterstreamflix.R.string.subtitle_studio_preset_access,
        textSize = 1.75f,
        fontColor = 0xFFFFF176.toInt(),
        backgroundColor = argb(160, 0x000000),
        edgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE,
        edgeColor = 0xFF000000.toInt(),
    )

    val presets: List<LightPreset> = listOf(
        CLASSIC, YELLOW_LARGE, HIGH_CONTRAST, CINEMA, OUTLINE, SERIF,
        NIGHT, BOXED, ACCESS,
    )

    fun matchesCurrent(preset: LightPreset): Boolean {
        val style = UserPreferences.captionStyle
        return UserPreferences.captionTextSize == preset.textSize &&
            style.foregroundColor.getRgb() == preset.fontColor.getRgb() &&
            style.foregroundColor.getAlpha() == preset.fontColor.getAlpha() &&
            style.backgroundColor == preset.backgroundColor &&
            style.windowColor == preset.windowColor &&
            style.edgeType == preset.edgeType &&
            style.edgeColor == preset.edgeColor &&
            UserPreferences.captionTypefaceIndex == preset.typefaceIndex
    }

    fun applyPreset(preset: LightPreset) {
        UserPreferences.captionTextSize = preset.textSize
        UserPreferences.captionTypefaceIndex = preset.typefaceIndex
        UserPreferences.captionStyle = CaptionStyleCompat(
            preset.fontColor,
            preset.backgroundColor,
            preset.windowColor,
            preset.edgeType,
            preset.edgeColor,
            UserPreferences.captionTypefaceOrDefault(),
        )
    }

    fun applyToView(view: SubtitleView) {
        view.setStyle(UserPreferences.captionStyle)
        view.setFractionalTextSize(
            SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize,
        )
        view.setPadding(0, 0, 0, UserPreferences.captionMargin.dp(view.context))
    }

    fun buildPreviewCue(sampleText: CharSequence): Cue =
        Cue.Builder()
            .setText(sampleText)
            .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLine(0.92f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_END)
            .setPosition(0.5f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .build()

    fun showPreview(view: SubtitleView, sampleText: CharSequence) {
        applyToView(view)
        view.setCues(listOf(buildPreviewCue(sampleText)))
        view.visibility = View.VISIBLE
        view.alpha = 1f
    }

    fun hidePreview(view: SubtitleView) {
        view.setCues(emptyList())
        view.visibility = View.GONE
    }

    /** Caption-style editor screens that should keep the player preview up (ST-014). */
    fun isStudioSettingName(name: String): Boolean =
        name.startsWith("CAPTION_STYLE") || name.startsWith("SUBTITLE_STUDIO")
}
