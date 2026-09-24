package com.dskja.betterstreamflix.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailRatingTest {

    @Test
    fun format_keepsTypicalTmdbScore() {
        assertEquals("8.7", DetailRating.format(8.7))
        assertEquals("7.0", DetailRating.format(7.0))
    }

    @Test
    fun format_convertsPercentageStyle() {
        assertEquals("8.7", DetailRating.format(87.0))
        assertEquals("10.0", DetailRating.format(100.0))
    }

    @Test
    fun format_rejectsVoteCountOrPopularity() {
        assertNull(DetailRating.format(141.0))
        assertNull(DetailRating.format(2500.0))
    }

    @Test
    fun format_rejectsMissingOrNonPositive() {
        assertNull(DetailRating.format(null))
        assertNull(DetailRating.format(0.0))
        assertNull(DetailRating.format(-1.0))
        assertNull(DetailRating.format(Double.NaN))
    }
}
