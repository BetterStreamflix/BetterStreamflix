package com.dskja.betterstreamflix.extractors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractorPermanentFailureTest {

    @Test
    fun recognizesSourceNotFound() {
        assertTrue(ExtractorFailureClassifier.isPermanent(Exception("Source not found")))
    }

    @Test
    fun recognizesUnpackFailed() {
        assertTrue(ExtractorFailureClassifier.isPermanent(Exception("Unpack failed")))
    }

    @Test
    fun recognizesVoe404() {
        assertTrue(ExtractorFailureClassifier.isPermanent(Exception("VOE source not found (404)")))
    }

    @Test
    fun transientNetworkIsNotPermanent() {
        assertFalse(ExtractorFailureClassifier.isPermanent(Exception("timeout connecting to host")))
    }
}
