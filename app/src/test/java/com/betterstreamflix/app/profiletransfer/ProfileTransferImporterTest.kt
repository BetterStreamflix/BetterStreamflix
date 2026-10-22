package com.betterstreamflix.app.profiletransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-safe import gates (full import needs Android/Room).
 */
class ProfileTransferImporterTest {

    @Test
    fun kidsPersonaPayloadDecodesWithIntegrity() {
        val payload = ProfileTransferPayload(
            v = ProfileTransferPayload.VERSION,
            kind = ProfileTransferPayload.KIND,
            exportedAt = 1L,
            sourceProfileId = "default",
            persona = ProfileTransferPayload.Persona(
                displayName = "Kid",
                avatarKey = "ocean",
                isKids = true,
                maxAgeRating = 12,
            ),
            watchlist = listOf(
                ProfileTransferPayload.WatchlistItem(
                    provider = "SerienStream",
                    mediaType = "movie",
                    mediaId = "/m/1",
                    title = "Restricted",
                ),
            ),
        )
        val json = ProfileTransferCodec.encodeFileJson(payload)
        val decoded = ProfileTransferCodec.decodeFileJson(json)
        assertTrue(decoded?.persona?.isKids == true)
        assertEquals(12, decoded?.persona?.maxAgeRating)
    }

    @Test
    fun legacyImportVersionAcceptedByImporterGate() {
        val versions = setOf(ProfileTransferPayload.VERSION, ProfileTransferPayload.VERSION_LEGACY)
        assertTrue(ProfileTransferPayload.VERSION_LEGACY in versions)
        assertTrue(ProfileTransferPayload.VERSION in versions)
    }

    @Test
    fun forQrStripsKidsNotesAndAgeCeiling() {
        val payload = ProfileTransferPayload(
            v = ProfileTransferPayload.VERSION,
            kind = ProfileTransferPayload.KIND,
            exportedAt = 1L,
            sourceProfileId = "default",
            persona = ProfileTransferPayload.Persona(
                displayName = "Kid",
                isKids = true,
                maxAgeRating = 12,
            ),
            settings = ProfileTransferPayload.SettingsSnapshot(
                parentalControlMaxAge = 12,
                spoilerShieldEnabled = true,
            ),
            notes = listOf(
                ProfileTransferPayload.NoteItem(episodeId = "e1", text = "spoiler note"),
            ),
        )
        val qr = payload.forQr()
        assertTrue(qr.notes.isEmpty())
        assertTrue(qr.persona?.isKids == false)
        assertTrue(qr.persona?.maxAgeRating == null)
        assertTrue(qr.settings.parentalControlMaxAge == null)
        assertTrue(qr.settings.spoilerShieldEnabled == true)
    }
}
