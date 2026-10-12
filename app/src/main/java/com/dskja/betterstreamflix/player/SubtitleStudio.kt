package com.dskja.betterstreamflix.player

import android.text.Layout
import android.view.View
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.getAlpha
import com.dskja.betterstreamflix.utils.getRgb

/**
 * Subtitle Studio light — per-profile size / color / background with a live
 * player preview. Wraps the existing caption prefs without replacing the full
 * edge/window editor.
 */
object SubtitleStudio {

    /** Default sample line shown while Caption style screens are open. */
    const val PREVIEW_SAMPLE_EN: String = "Subtitle Studio — sample line"

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

    val presets: List<LightPreset> = listOf(CLASSIC, YELLOW_LARGE, HIGH_CONTRAST)

    fun matchesCurrent(preset: LightPreset): Boolean {
        val style = UserPreferences.captionStyle
        return UserPreferences.captionTextSize == preset.textSize &&
            style.foregroundColor.getRgb() == preset.fontColor.getRgb() &&
            style.foregroundColor.getAlpha() == preset.fontColor.getAlpha() &&
            style.backgroundColor == preset.backgroundColor &&
            style.edgeType == preset.edgeType &&
            style.edgeColor == preset.edgeColor
    }

    fun applyPreset(preset: LightPreset) {
        val windowColor = UserPreferences.captionStyle.windowColor
        UserPreferences.captionTextSize = preset.textSize
        UserPreferences.captionStyle = CaptionStyleCompat(
            preset.fontColor,
            preset.backgroundColor,
            windowColor,
            preset.edgeType,
            preset.edgeColor,
            null,
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

    /** Caption-style editor screens that should keep the player preview up. */
    fun isStudioSettingName(name: String): Boolean =
        name.startsWith("CAPTION_STYLE")
}
