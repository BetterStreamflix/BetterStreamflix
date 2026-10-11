package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeNotesStoreTest {

    @Test
    fun sanitizeCollapsesWhitespaceAndCapsLength() {
        val raw = "  Left off   after  the\nkitchen  scene  "
        assertEquals("Left off after the kitchen scene", EpisodeNotesStore.sanitize(raw))

        val long = "a".repeat(EpisodeNotesStore.MAX_CHARS + 40)
        val cleaned = EpisodeNotesStore.sanitize(long)
        assertEquals(EpisodeNotesStore.MAX_CHARS, cleaned.length)
    }

    @Test
    fun sanitizeEmptyClears() {
        assertEquals("", EpisodeNotesStore.sanitize("   \n\t  "))
        assertEquals("", EpisodeNotesStore.sanitize(null))
    }

    @Test
    fun previewEllipsizes() {
        val text = "Stopped right before they leave the cabin"
        val short = EpisodeNotesStore.preview(text, maxChars = 12)
        assertTrue(short.endsWith("…"))
        assertTrue(short.length <= 13)
        assertEquals(text, EpisodeNotesStore.preview(text, maxChars = 80))
    }
}
