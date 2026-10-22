package com.betterstreamflix.app.profiletransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure helper covering the EI-010 provider-name extraction rules used by
 * [com.betterstreamflix.app.utils.UserPreferences.providersWithFavoriteLayout].
 */
class ProfileTransferFavoriteLayoutProvidersTest {

    @Test
    fun extractProviders_fromFavoriteLayoutKeys() {
        val keys = listOf(
            "FAVORITE_SORT_MODE_serienstream",
            "FAVORITE_ITEM_ORDER_tmdb_movies",
            "FAVORITE_ITEM_ORDER_tmdb_tv_shows",
            "FAVORITE_CATEGORY_ORDER_streamingcommunity",
            "FAVORITE_SORT_MODE_other_p_kids01", // other profile — ignored when bare
            "SELECTED_THEME",
        )
        val out = extractProviders(keys, profileSuffix = "")
        assertEquals(
            setOf("serienstream", "tmdb", "streamingcommunity"),
            out,
        )
    }

    @Test
    fun extractProviders_honorsProfileSuffix() {
        val keys = listOf(
            "FAVORITE_SORT_MODE_serienstream_p_kids01",
            "FAVORITE_SORT_MODE_tmdb", // default profile — ignored
            "FAVORITE_ITEM_ORDER_flixhq_movies_p_kids01",
        )
        val out = extractProviders(keys, profileSuffix = "_p_kids01")
        assertEquals(setOf("serienstream", "flixhq"), out)
    }

    @Test
    fun extractProviders_ignoresBlank() {
        assertTrue(extractProviders(emptyList(), "").isEmpty())
    }

    /** Mirrors the bare-key parsing in UserPreferences.providersWithFavoriteLayout. */
    private fun extractProviders(keys: List<String>, profileSuffix: String): Set<String> {
        val out = linkedSetOf<String>()
        for (key in keys) {
            val bare = when {
                profileSuffix.isEmpty() -> {
                    if ("_p_" in key) continue
                    key
                }
                key.endsWith(profileSuffix) -> key.removeSuffix(profileSuffix)
                else -> continue
            }
            when {
                bare.startsWith("FAVORITE_SORT_MODE_") ->
                    out += bare.removePrefix("FAVORITE_SORT_MODE_")
                bare.startsWith("FAVORITE_CATEGORY_ORDER_") ->
                    out += bare.removePrefix("FAVORITE_CATEGORY_ORDER_")
                bare.startsWith("FAVORITE_ITEM_ORDER_") -> {
                    val rest = bare.removePrefix("FAVORITE_ITEM_ORDER_")
                    when {
                        rest.endsWith("_movies") -> out += rest.removeSuffix("_movies")
                        rest.endsWith("_tv_shows") -> out += rest.removeSuffix("_tv_shows")
                    }
                }
            }
        }
        return out.filter { it.isNotBlank() }.toSet()
    }
}
