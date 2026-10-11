package com.dskja.betterstreamflix.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeanbackDensityPolicyTest {

    @Test
    fun resolve_defaultsUnknownToComfort() {
        assertEquals(LeanbackDensityPolicy.Mode.COMFORT, LeanbackDensityPolicy.resolve(null))
        assertEquals(LeanbackDensityPolicy.Mode.COMFORT, LeanbackDensityPolicy.resolve(""))
        assertEquals(LeanbackDensityPolicy.Mode.COMFORT, LeanbackDensityPolicy.resolve("comfort"))
        assertEquals(LeanbackDensityPolicy.Mode.COMPACT, LeanbackDensityPolicy.resolve("compact"))
        assertEquals(LeanbackDensityPolicy.Mode.COMPACT, LeanbackDensityPolicy.resolve("COMPACT"))
    }

    @Test
    fun compact_hasMoreColumnsThanComfort() {
        val compact = LeanbackDensityPolicy.Mode.COMPACT
        val comfort = LeanbackDensityPolicy.Mode.COMFORT
        assertTrue(
            LeanbackDensityPolicy.posterGridColumns(compact) >
                LeanbackDensityPolicy.posterGridColumns(comfort),
        )
        assertTrue(
            LeanbackDensityPolicy.searchResultColumns(compact) >
                LeanbackDensityPolicy.searchResultColumns(comfort),
        )
        assertTrue(
            LeanbackDensityPolicy.downloadsLibraryColumns(compact) >
                LeanbackDensityPolicy.downloadsLibraryColumns(comfort),
        )
    }

    @Test
    fun comfort_hasTallerShelfPosters() {
        assertTrue(
            LeanbackDensityPolicy.shelfPosterHeightDp(LeanbackDensityPolicy.Mode.COMFORT) >
                LeanbackDensityPolicy.shelfPosterHeightDp(LeanbackDensityPolicy.Mode.COMPACT),
        )
    }

    @Test
    fun ambientStripHeight_matchesPosterPlusPadding() {
        val comfort = LeanbackDensityPolicy.Mode.COMFORT
        val compact = LeanbackDensityPolicy.Mode.COMPACT
        assertEquals(
            LeanbackDensityPolicy.shelfPosterHeightDp(comfort) + 8,
            LeanbackDensityPolicy.ambientStripHeightDp(comfort),
        )
        assertEquals(
            LeanbackDensityPolicy.shelfPosterHeightDp(compact) + 8,
            LeanbackDensityPolicy.ambientStripHeightDp(compact),
        )
        assertTrue(
            LeanbackDensityPolicy.ambientStripHeightDp(comfort) >
                LeanbackDensityPolicy.ambientStripHeightDp(compact),
        )
    }

    @Test
    fun featuredCardHeight_compactIsShorter() {
        assertTrue(
            LeanbackDensityPolicy.featuredCardHeightDp(LeanbackDensityPolicy.Mode.COMFORT) >
                LeanbackDensityPolicy.featuredCardHeightDp(LeanbackDensityPolicy.Mode.COMPACT),
        )
    }

    @Test
    fun favoritesColumnCount_scalesWithScreenAndMode() {
        val wide = LeanbackDensityPolicy.favoritesColumnCount(1920, LeanbackDensityPolicy.Mode.COMPACT)
        val comfort = LeanbackDensityPolicy.favoritesColumnCount(1920, LeanbackDensityPolicy.Mode.COMFORT)
        assertTrue(wide >= comfort)
        assertTrue(LeanbackDensityPolicy.favoritesColumnCount(200, LeanbackDensityPolicy.Mode.COMFORT) >= 3)
        assertTrue(LeanbackDensityPolicy.favoritesColumnCount(200, LeanbackDensityPolicy.Mode.COMPACT) >= 4)
    }

    @Test
    fun shelfPosterOverride_keepsPortraitRatio() {
        val comfort = LeanbackDensityPolicy.Mode.COMFORT
        val (w, h) = LeanbackDensityPolicy.shelfPosterOverridePx(2f, comfort)
        assertEquals(LeanbackDensityPolicy.shelfPosterHeightDp(comfort) * 2, h)
        assertTrue(w < h)
    }

    @Test
    fun defaultIsComfortHighEnd() {
        assertEquals(LeanbackDensityPolicy.COMFORT, LeanbackDensityPolicy.DEFAULT)
        assertEquals(5, LeanbackDensityPolicy.posterGridColumns(LeanbackDensityPolicy.Mode.COMFORT))
        assertEquals(7, LeanbackDensityPolicy.posterGridColumns(LeanbackDensityPolicy.Mode.COMPACT))
    }
}
