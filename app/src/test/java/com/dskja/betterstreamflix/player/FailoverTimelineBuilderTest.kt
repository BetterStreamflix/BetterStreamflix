package com.dskja.betterstreamflix.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverTimelineBuilderTest {

    @Test
    fun mapsTimeoutAndCaptcha() {
        assertEquals(
            FailoverStepKind.TIMEOUT,
            FailoverTimelineBuilder.kindFromError(Exception("SerienStream timed out after 40000ms")),
        )
        assertEquals(
            FailoverStepKind.CAPTCHA,
            FailoverTimelineBuilder.kindFromError(
                Exception("SerienStream Cloudflare captcha challenge"),
            ),
        )
    }

    @Test
    fun successPathFormatsMiniTimeline() {
        val attempts = listOf(
            FailoverAttempt(1, "A", FailoverStepKind.TIMEOUT),
            FailoverAttempt(2, "B", FailoverStepKind.CAPTCHA),
            FailoverAttempt(3, "C", FailoverStepKind.OK),
        )
        assertTrue(FailoverTimelineBuilder.shouldShowAfterSuccess(attempts))
        assertEquals(
            "S1:TIMEOUT → S2:CAPTCHA → S3:OK",
            FailoverTimelineBuilder.joinWire(FailoverTimelineBuilder.buildSteps(attempts)),
        )
    }

    @Test
    fun singleSuccessWithoutFailuresHidden() {
        val attempts = listOf(FailoverAttempt(1, "A", FailoverStepKind.OK))
        assertFalse(FailoverTimelineBuilder.shouldShowAfterSuccess(attempts))
        assertFalse(FailoverTimelineBuilder.shouldShowOnFailure(attempts))
    }

    @Test
    fun exhaustedFailuresShowOnFailurePath() {
        val attempts = listOf(
            FailoverAttempt(1, "A", FailoverStepKind.TIMEOUT),
            FailoverAttempt(2, "B", FailoverStepKind.GENERIC),
        )
        assertTrue(FailoverTimelineBuilder.shouldShowOnFailure(attempts))
        assertFalse(FailoverTimelineBuilder.shouldShowAfterSuccess(attempts))
    }

    @Test
    fun collapsesDuplicateConsecutiveSteps() {
        val attempts = listOf(
            FailoverAttempt(1, "A", FailoverStepKind.TIMEOUT),
            FailoverAttempt(1, "A", FailoverStepKind.TIMEOUT),
            FailoverAttempt(2, "B", FailoverStepKind.OK),
        )
        assertEquals(
            listOf("S1:TIMEOUT", "S2:OK"),
            FailoverTimelineBuilder.wireLabels(FailoverTimelineBuilder.buildSteps(attempts)),
        )
    }

    @Test
    fun capsLongChainsWithEllipsis() {
        val attempts = (1..6).map { n ->
            FailoverAttempt(n, "S$n", if (n == 6) FailoverStepKind.OK else FailoverStepKind.GENERIC)
        }
        val labels = FailoverTimelineBuilder.wireLabels(FailoverTimelineBuilder.buildSteps(attempts))
        assertEquals(4, labels.size)
        assertEquals("…", labels[2])
        assertEquals("S6:OK", labels.last())
    }

    @Test
    fun softwareAndExternalStagesWireWithoutServerOrdinal() {
        val attempts = listOf(
            FailoverAttempt(1, "A", FailoverStepKind.GENERIC),
            FailoverAttempt(0, "", FailoverStepKind.SOFTWARE_DECODER),
            FailoverAttempt(0, "", FailoverStepKind.EXTERNAL_PLAYER),
        )
        assertEquals(
            listOf("S1:GENERIC", "SW", "EXT"),
            FailoverTimelineBuilder.wireLabels(FailoverTimelineBuilder.buildSteps(attempts)),
        )
    }
}
