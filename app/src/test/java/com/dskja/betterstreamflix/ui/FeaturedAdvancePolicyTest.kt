package com.dskja.betterstreamflix.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the FeaturedAdvancePolicy contract for pure predicates that do not
 * need Android services. Context-bound checks are covered by integration/manual QA.
 */
class FeaturedAdvancePolicyTest {

    @Test
    fun shouldAutoAdvance_allClear_returnsTrue() {
        assertTrue(
            FeaturedAdvancePolicy.shouldAutoAdvance(
                touchExploration = false,
                reduceMotion = false,
                reduceHomeEffects = false,
            ),
        )
    }

    @Test
    fun shouldAutoAdvance_touchExploration_returnsFalse() {
        assertFalse(
            FeaturedAdvancePolicy.shouldAutoAdvance(
                touchExploration = true,
                reduceMotion = false,
                reduceHomeEffects = false,
            ),
        )
    }

    @Test
    fun shouldAutoAdvance_reduceHomeEffects_returnsFalse() {
        assertFalse(
            FeaturedAdvancePolicy.shouldAutoAdvance(
                touchExploration = false,
                reduceMotion = false,
                reduceHomeEffects = true,
            ),
        )
    }

    @Test
    fun shouldScheduleFeaturedAdvance_defersWhenPinnedOrChromeFocused() {
        assertTrue(
            FeaturedAdvancePolicy.shouldScheduleFeaturedAdvance(
                shouldAutoAdvance = true,
                isBackgroundPinned = false,
                featuredChromeFocused = false,
            ),
        )
        assertFalse(
            FeaturedAdvancePolicy.shouldScheduleFeaturedAdvance(
                shouldAutoAdvance = true,
                isBackgroundPinned = true,
                featuredChromeFocused = false,
            ),
        )
        assertFalse(
            FeaturedAdvancePolicy.shouldScheduleFeaturedAdvance(
                shouldAutoAdvance = false,
                isBackgroundPinned = false,
                featuredChromeFocused = false,
            ),
        )
    }

    @Test
    fun payloadRotateConstantStable() {
        assertTrue(FeaturedHeroController.PAYLOAD_ROTATE.isNotBlank())
        assertTrue(FeaturedHeroController.MAX_FEATURED_BITMAP_BUDGET >= 12)
    }

    @Test
    fun featuredTvRotationStillWraps() {
        assertTrue(FeaturedTvRotation.nextIndex(2, 3) == 0)
        assertFalse(FeaturedTvRotation.nextIndex(0, 1) != 0)
    }
}
