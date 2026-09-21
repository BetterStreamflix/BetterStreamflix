package com.dskja.betterstreamflix.extractors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorFailureClassifierTest {

    @Test
    fun noSourceFoundIsPermanent() {
        assertTrue(ExtractorFailureClassifier.isPermanent(Exception("No source found")))
    }

    @Test
    fun http503IsExpectedNoise() {
        assertTrue(
            ExtractorFailureClassifier.isExpectedStreamNoise(
                Exception("HTTP Client Error with status code: 503"),
            ),
        )
    }

    @Test
    fun exoSourceErrorIsExpectedNoise() {
        assertTrue(
            ExtractorFailureClassifier.isExpectedStreamNoise(
                Exception("Source error"),
            ),
        )
    }

    @Test
    fun unrelatedErrorIsNotNoise() {
        assertFalse(
            ExtractorFailureClassifier.isExpectedStreamNoise(
                NullPointerException("boom"),
            ),
        )
    }
}
