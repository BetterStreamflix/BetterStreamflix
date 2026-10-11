package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackVideoQualityTest {
    @Test
    fun fromKey_acceptsEnumNamesAndShorthand() {
        assertEquals(PlaybackVideoQuality.AUTO, PlaybackVideoQuality.fromKey(null))
        assertEquals(PlaybackVideoQuality.AUTO, PlaybackVideoQuality.fromKey("AUTO"))
        assertEquals(PlaybackVideoQuality.P720, PlaybackVideoQuality.fromKey("P720"))
        assertEquals(PlaybackVideoQuality.P720, PlaybackVideoQuality.fromKey("720p"))
        assertEquals(PlaybackVideoQuality.P1080, PlaybackVideoQuality.fromKey("1080"))
        assertEquals(PlaybackVideoQuality.AUTO, PlaybackVideoQuality.fromKey("unknown"))
    }

    @Test
    fun maxHeight_matchesRungs() {
        assertNull(PlaybackVideoQuality.AUTO.maxHeight)
        assertEquals(720, PlaybackVideoQuality.P720.maxHeight)
        assertEquals(1080, PlaybackVideoQuality.P1080.maxHeight)
    }

    @Test
    fun allowsHeight_respectsCap() {
        assertTrue(PlaybackVideoQuality.AUTO.allowsHeight(2160))
        assertTrue(PlaybackVideoQuality.P720.allowsHeight(720))
        assertFalse(PlaybackVideoQuality.P720.allowsHeight(1080))
        assertTrue(PlaybackVideoQuality.P1080.allowsHeight(1080))
        assertFalse(PlaybackVideoQuality.P1080.allowsHeight(1440))
    }

    @Test
    fun cappedForDataSaver_autoCapsTo720() {
        assertEquals(PlaybackVideoQuality.AUTO, PlaybackVideoQuality.AUTO.cappedForDataSaver(false))
        assertEquals(PlaybackVideoQuality.P1080, PlaybackVideoQuality.P1080.cappedForDataSaver(false))
        assertEquals(PlaybackVideoQuality.P720, PlaybackVideoQuality.AUTO.cappedForDataSaver(true))
        assertEquals(PlaybackVideoQuality.P720, PlaybackVideoQuality.P1080.cappedForDataSaver(true))
        assertEquals(PlaybackVideoQuality.P720, PlaybackVideoQuality.P720.cappedForDataSaver(true))
    }
}
