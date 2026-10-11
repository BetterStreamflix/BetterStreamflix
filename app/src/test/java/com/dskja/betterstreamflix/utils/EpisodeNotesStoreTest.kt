package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun encodeDecodeRoundTripWithClock() {
        val record = EpisodeNotesStore.NoteRecord(text = "Left after kitchen", updatedAtMillis = 12345L)
        val encoded = EpisodeNotesStore.encode(record)
        assertTrue(EpisodeNotesStore.isJsonEnvelope(encoded))
        val decoded = EpisodeNotesStore.decode(encoded)!!
        assertEquals(record.text, decoded.text)
        assertEquals(record.updatedAtMillis, decoded.updatedAtMillis)
    }

    @Test
    fun decodePlainStringLegacy_dualRead() {
        val decoded = EpisodeNotesStore.decode("plain legacy note")!!
        assertEquals("plain legacy note", decoded.text)
        assertEquals(0L, decoded.updatedAtMillis)
        assertFalse(EpisodeNotesStore.isJsonEnvelope("plain legacy note"))
    }

    @Test
    fun decodeTombstoneKeepsEmptyTextAndClock() {
        val encoded = EpisodeNotesStore.encode(EpisodeNotesStore.NoteRecord("", 99L))
        val decoded = EpisodeNotesStore.decode(encoded)!!
        assertEquals("", decoded.text)
        assertEquals(99L, decoded.updatedAtMillis)
    }
}
