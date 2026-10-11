package com.dskja.betterstreamflix.player

import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStudioTest {

    @Test
    fun presetsCoverSizeColorBackground() {
        val ids = SubtitleStudio.presets.map { it.id }
        assertTrue(ids.contains("classic"))
        assertTrue(ids.contains("yellow_large"))
        assertTrue(ids.contains("high_contrast"))
        SubtitleStudio.presets.forEach { preset ->
            assertTrue(preset.textSize > 0f)
            val alpha = (preset.backgroundColor ushr 24) and 0xFF
            assertTrue(alpha in 0..255)
        }
    }

    @Test
    fun highContrastUsesOutlineEdge() {
        assertEquals(
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            SubtitleStudio.HIGH_CONTRAST.edgeType,
        )
        assertTrue(SubtitleStudio.HIGH_CONTRAST.textSize >= 1.25f)
    }

    @Test
    fun previewCueAnchorsNearBottom() {
        val cue = SubtitleStudio.buildPreviewCue("Hello")
        assertEquals("Hello", cue.text?.toString())
        assertEquals(Cue.LINE_TYPE_FRACTION, cue.lineType)
        assertEquals(Cue.ANCHOR_TYPE_END, cue.lineAnchor)
        assertTrue(cue.line > 0.8f)
    }

    @Test
    fun studioSettingNamesMatchCaptionScreens() {
        assertTrue(SubtitleStudio.isStudioSettingName("CAPTION_STYLE"))
        assertTrue(SubtitleStudio.isStudioSettingName("CAPTION_STYLE_FONT_COLOR"))
        assertTrue(SubtitleStudio.isStudioSettingName("CAPTION_STYLE_TEXT_SIZE"))
        assertTrue(SubtitleStudio.isStudioSettingName("CAPTION_STYLE_BACKGROUND_COLOR"))
        assertFalse(SubtitleStudio.isStudioSettingName("SUBTITLES"))
        assertFalse(SubtitleStudio.isStudioSettingName("MAIN"))
    }

    @Test
    fun yellowLargeBiggerThanClassic() {
        assertTrue(SubtitleStudio.YELLOW_LARGE.textSize > SubtitleStudio.CLASSIC.textSize)
        assertEquals(0xFFFFFF00.toInt(), SubtitleStudio.YELLOW_LARGE.fontColor)
    }
}
