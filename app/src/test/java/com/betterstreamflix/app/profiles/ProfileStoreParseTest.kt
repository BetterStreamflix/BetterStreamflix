package com.betterstreamflix.app.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStoreParseTest {

    @Test
    fun parseProfilesJson_readsArrayWithoutTypeToken() {
        val raw = """
            [
              {
                "id": "default",
                "displayName": "Default",
                "avatarKey": "crimson",
                "isKids": false,
                "createdAtMillis": 1,
                "updatedAtMillis": 2,
                "enabledIntegrations": ["trakt", "tmdb"]
              },
              {
                "id": "kids01",
                "displayName": "Kids",
                "avatarKey": "mint",
                "isKids": true,
                "maxAgeRating": 12,
                "createdAtMillis": 3,
                "updatedAtMillis": 4,
                "enabledIntegrations": []
              }
            ]
        """.trimIndent()

        val profiles = ProfileStore.parseProfilesJson(raw)
        assertEquals(2, profiles.size)
        assertEquals("default", profiles[0].id)
        assertEquals(setOf("trakt", "tmdb"), profiles[0].enabledIntegrations)
        assertTrue(profiles[1].isKids)
        assertEquals(12, profiles[1].maxAgeRating)
    }

    @Test
    fun parseProfilesJson_skipsBlankIds() {
        val raw = """[{"id":"","displayName":"x","avatarKey":"crimson","createdAtMillis":1,"updatedAtMillis":1}]"""
        assertTrue(ProfileStore.parseProfilesJson(raw).isEmpty())
    }

    @Test
    fun parseProfilesJson_readsGuestFields() {
        val raw = """
            [{
              "id": "guest_1a2b",
              "displayName": "Guest",
              "avatarKey": "slate",
              "isKids": false,
              "isGuest": true,
              "expiresAtMillis": 7200000,
              "createdAtMillis": 1,
              "updatedAtMillis": 2,
              "enabledIntegrations": []
            }]
        """.trimIndent()
        val profiles = ProfileStore.parseProfilesJson(raw)
        assertEquals(1, profiles.size)
        assertTrue(profiles[0].isGuest)
        assertEquals(7_200_000L, profiles[0].expiresAtMillis)
    }
}
