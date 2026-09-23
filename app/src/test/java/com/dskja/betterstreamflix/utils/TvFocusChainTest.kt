package com.dskja.betterstreamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumentation-free smoke documentation for [TvFocusChain].
 *
 * Full DPAD focus linking needs Views (instrumentation). This unit test locks the
 * public API contract so Leanback callers keep a stable horizontal focus helper.
 *
 * ## Fire Stick / TV focus smoke paths (manual QA matrix)
 *
 * Run on a Fire Stick (or TV emulator with DPAD) after UI changes:
 * 1. **Home shelves → side nav**: focus first poster in a category row, press LEFT →
 *    [nav_main] receives focus (`item_category_tv` HorizontalGridView nextFocusLeft + focusOutFront).
 * 2. **Favorites grid**: focus leftmost poster, press LEFT → side nav; first row UP →
 *    rearrange / sort actions.
 * 3. **Detail actions**: Watch / Trailer / Download / Share / Favorite chain LEFT/RIGHT
 *    via [TvFocusChain.linkHorizontal] without trapping.
 * 4. **Player chrome**: Play/Pause, aspect, settings keep contentDescriptions; next-episode
 *    overlay action ↔ dismiss; Bypass QR dialog lands focus on Cancel, not the QR bitmap.
 * 5. **Support banner**: root + CTA + dismiss announce; DPAD activate opens Support hub.
 * 6. **Overscan**: Settings padding X/Y — focused shelf zoom must not clip at screen edges
 *    ([MainTvActivity.adjustLayoutDelta] clipChildren/clipToPadding false).
 */
class TvFocusChainTest {

    @Test
    fun tvFocusChainObjectExists() {
        assertNotNull(TvFocusChain)
    }

    @Test
    fun linkHorizontalIsPublicApi() {
        val method = TvFocusChain::class.java.methods.firstOrNull { it.name == "linkHorizontal" }
        assertNotNull("TvFocusChain.linkHorizontal must remain public", method)
        assertTrue(method!!.parameterTypes.isNotEmpty())
        assertEquals(Void.TYPE, method.returnType)
    }

    @Test
    fun tvFocusZoomGatesOnReduceHomeEffectsApi() {
        val method = TvFocusZoom::class.java.methods.firstOrNull { it.name == "apply" }
        assertNotNull(method)
        assertEquals(2, method!!.parameterTypes.size)
    }
}
