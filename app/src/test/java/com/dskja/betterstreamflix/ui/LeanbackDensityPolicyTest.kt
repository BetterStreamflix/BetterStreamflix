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
        assertEquals(LeanbackDensityPolicy.Mode.COMFORT, LeanbackDensityPolicy.resolve("typo"))
    }

    @Test
    fun compact_hasMoreColumnsThanComfort() {
        val compact = LeanbackDensityPolicy.Mode.COMPACT
        val comfort = LeanbackDensityPolicy.Mode.COMFORT
        assertTrue(
            LeanbackDensityPolicy.posterGridColumns(compact, largeText = false) >
                LeanbackDensityPolicy.posterGridColumns(comfort, largeText = false),
        )
        assertTrue(
            LeanbackDensityPolicy.searchResultColumns(compact, largeText = false) >
                LeanbackDensityPolicy.searchResultColumns(comfort, largeText = false),
        )
        assertTrue(
            LeanbackDensityPolicy.downloadsLibraryColumns(compact) >
                LeanbackDensityPolicy.downloadsLibraryColumns(comfort),
        )
        assertTrue(
            LeanbackDensityPolicy.searchGenreColumns(compact) >
                LeanbackDensityPolicy.searchGenreColumns(comfort),
        )
    }

    @Test
    fun largeText_capsCompactColumns() {
        val compact = LeanbackDensityPolicy.Mode.COMPACT
        assertEquals(7, LeanbackDensityPolicy.posterGridColumns(compact, largeText = false))
        assertEquals(6, LeanbackDensityPolicy.posterGridColumns(compact, largeText = true))
        assertEquals(5, LeanbackDensityPolicy.posterGridColumns(LeanbackDensityPolicy.Mode.COMFORT, largeText = true))
    }

    @Test
    fun comfort_hasTallerShelfPosters() {
        assertTrue(
            LeanbackDensityPolicy.shelfPosterHeightDp(LeanbackDensityPolicy.Mode.COMFORT) >
                LeanbackDensityPolicy.shelfPosterHeightDp(LeanbackDensityPolicy.Mode.COMPACT),
        )
    }

    @Test
    fun ambientStrip_includesMetaBudget() {
        val comfort = LeanbackDensityPolicy.Mode.COMFORT
        val compact = LeanbackDensityPolicy.Mode.COMPACT
        assertEquals(
            LeanbackDensityPolicy.shelfPosterHeightDp(comfort) +
                LeanbackDensityPolicy.ambientContinueMetaBudgetDp(comfort),
            LeanbackDensityPolicy.ambientContinueStripHeightDp(comfort),
        )
        assertEquals(
            LeanbackDensityPolicy.ambientContinueStripHeightDp(comfort),
            LeanbackDensityPolicy.ambientStripHeightDp(comfort),
        )
        assertTrue(
            LeanbackDensityPolicy.ambientContinueStripHeightDp(comfort) >
                LeanbackDensityPolicy.ambientContinueStripHeightDp(compact),
        )
        assertTrue(LeanbackDensityPolicy.ambientContinueStripHeightDp(compact) > 148)
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
    fun shelfPosterOverride_differsAtDensityOne() {
        val compact = LeanbackDensityPolicy.Mode.COMPACT
        val comfort = LeanbackDensityPolicy.Mode.COMFORT
        val (_, hCompact) = LeanbackDensityPolicy.shelfPosterOverridePx(1f, compact)
        val (_, hComfort) = LeanbackDensityPolicy.shelfPosterOverridePx(1f, comfort)
        assertTrue(hComfort > hCompact)
        assertEquals(LeanbackDensityPolicy.shelfPosterHeightDp(compact), hCompact)
        assertEquals(LeanbackDensityPolicy.shelfPosterHeightDp(comfort), hComfort)
    }

    @Test
    fun gridPosterOverride_followsMode() {
        val (_, hCompact) = LeanbackDensityPolicy.gridPosterOverridePx(2f, LeanbackDensityPolicy.Mode.COMPACT)
        val (_, hComfort) = LeanbackDensityPolicy.gridPosterOverridePx(2f, LeanbackDensityPolicy.Mode.COMFORT)
        assertTrue(hComfort > hCompact)
    }

    @Test
    fun itemSpacing_tighterInCompact() {
        assertTrue(
            LeanbackDensityPolicy.itemSpacingDp(LeanbackDensityPolicy.Mode.COMFORT) >
                LeanbackDensityPolicy.itemSpacingDp(LeanbackDensityPolicy.Mode.COMPACT),
        )
        assertTrue(
            LeanbackDensityPolicy.catalogItemSpacingDp(LeanbackDensityPolicy.Mode.COMFORT) >
                LeanbackDensityPolicy.catalogItemSpacingDp(LeanbackDensityPolicy.Mode.COMPACT),
        )
    }

    @Test
    fun overlayChrome_tighterInCompact() {
        val compact = LeanbackDensityPolicy.Mode.COMPACT
        val comfort = LeanbackDensityPolicy.Mode.COMFORT
        assertEquals(12, LeanbackDensityPolicy.overlayChromePadDp(compact))
        assertEquals(16, LeanbackDensityPolicy.overlayChromePadDp(comfort))
        assertTrue(
            LeanbackDensityPolicy.overlayChromePadDp(comfort) >
                LeanbackDensityPolicy.overlayChromePadDp(compact),
        )
        assertEquals(
            LeanbackDensityPolicy.itemSpacingDp(compact),
            LeanbackDensityPolicy.overlayStackGapDp(compact),
        )
        assertEquals(
            LeanbackDensityPolicy.itemSpacingDp(comfort),
            LeanbackDensityPolicy.overlayStackGapDp(comfort),
        )
        assertTrue(
            LeanbackDensityPolicy.overlayStackGapDp(comfort) >
                LeanbackDensityPolicy.overlayStackGapDp(compact),
        )
    }

    @Test
    fun liveShelf_denserInCompact() {
        val compact = LeanbackDensityPolicy.Mode.COMPACT
        val comfort = LeanbackDensityPolicy.Mode.COMFORT
        assertEquals(150, LeanbackDensityPolicy.liveShelfPosterHeightDp(compact))
        assertEquals(
            LeanbackDensityPolicy.shelfPosterHeightDp(comfort),
            LeanbackDensityPolicy.liveShelfPosterHeightDp(comfort),
        )
        assertTrue(
            LeanbackDensityPolicy.liveShelfPosterHeightDp(compact) <
                LeanbackDensityPolicy.shelfPosterHeightDp(compact),
        )
        assertTrue(
            LeanbackDensityPolicy.liveShelfPosterHeightDp(comfort) >
                LeanbackDensityPolicy.liveShelfPosterHeightDp(compact),
        )
    }

    @Test
    fun focusZoom_smallerInCompact() {
        assertTrue(
            LeanbackDensityPolicy.focusZoomScale(LeanbackDensityPolicy.Mode.COMFORT) >
                LeanbackDensityPolicy.focusZoomScale(LeanbackDensityPolicy.Mode.COMPACT),
        )
    }

    @Test
    fun defaultIsComfortHighEnd() {
        assertEquals(LeanbackDensityPolicy.COMFORT, LeanbackDensityPolicy.DEFAULT)
        assertEquals(5, LeanbackDensityPolicy.posterGridColumns(LeanbackDensityPolicy.Mode.COMFORT, largeText = false))
        assertEquals(7, LeanbackDensityPolicy.posterGridColumns(LeanbackDensityPolicy.Mode.COMPACT, largeText = false))
    }
}
