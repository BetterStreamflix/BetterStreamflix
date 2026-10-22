package com.betterstreamflix.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTextTest {

    @Test
    fun fuzzyCloseEnough_acceptsSingleTypo() {
        assertTrue(FuzzyText.fuzzyCloseEnough("inception", "incepton"))
        assertTrue(FuzzyText.fuzzyCloseEnough("Matrix", "Matris"))
    }

    @Test
    fun fuzzyCloseEnough_rejectsFarMiss() {
        assertFalse(FuzzyText.fuzzyCloseEnough("inception", "completely"))
    }

    @Test
    fun typoBudget_scalesWithLength() {
        assertEquals(1, FuzzyText.typoBudget("ab"))
        assertEquals(2, FuzzyText.typoBudget("matrix"))
        assertEquals(3, FuzzyText.typoBudget("inception movie"))
    }

    @Test
    fun distance_exactIsZero() {
        assertEquals(0, FuzzyText.distance(" Dark ", "dark"))
    }
}
