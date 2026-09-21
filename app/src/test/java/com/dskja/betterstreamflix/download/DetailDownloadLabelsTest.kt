package com.dskja.betterstreamflix.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailDownloadLabelsTest {

    @Test
    fun seriesCoverage_emptyWhenNoSeasons() {
        val coverage = DetailDownloadLabels.SeriesCoverage(
            downloaded = 0,
            total = 0,
            seasonsWithPack = 0,
        )
        assertFalse(coverage.hasAny)
        assertEquals(0, coverage.total)
    }

    @Test
    fun seriesCoverage_hasAnyWhenEpisodesSaved() {
        val coverage = DetailDownloadLabels.SeriesCoverage(
            downloaded = 3,
            total = 12,
            seasonsWithPack = 0,
        )
        assertTrue(coverage.hasAny)
    }
}
