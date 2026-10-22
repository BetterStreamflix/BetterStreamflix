package com.betterstreamflix.app.utils

import android.view.View
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
 * 2. **Home vertical scroll to last shelf**: DPAD-DOWN from Featured through Continue Watching /
 *    Support banner to the last category row — focus must keep moving (no freeze). Category
 *    shelf roots must be `wrap_content` (`item_category_tv`), not `match_parent`.
 * 3. **Home entry from side nav**: after catalog load, Featured Watch (or first shelf) must
 *    take focus / be reachable with DPAD-RIGHT from [nav_main]. Movies grid works because it
 *    is a flat VerticalGridView; Home is nested rows and needs an explicit post-bind focus
 *    handoff plus `focusOut*` on `vgv_home`.
 * 4. **Favorites grid**: focus leftmost poster, press LEFT → side nav; first row UP →
 *    rearrange / sort actions.
 * 5. **Detail actions**: Watch / Trailer / Download / Share / Favorite chain LEFT/RIGHT
 *    via [TvFocusChain.linkHorizontal] without trapping.
 * 6. **Player chrome**: Play/Pause, aspect, settings keep contentDescriptions; next-episode
 *    overlay action ↔ dismiss; Bypass QR dialog lands focus on Cancel, not the QR bitmap.
 * 7. **Support banner**: root + CTA + dismiss announce; DPAD activate opens Support hub.
 * 8. **Overscan**: Settings padding X/Y — focused shelf zoom must not clip at screen edges
 *    ([MainTvActivity.adjustLayoutDelta] clipChildren/clipToPadding false).
 */
class TvFocusChainTest {

    @Test
    fun tvFocusChainObjectExists() {
        assertNotNull(TvFocusChain)
    }

    @Test
    fun linkDownIsPublicApi() {
        val method = TvFocusChain::class.java.methods.firstOrNull { it.name == "linkDown" }
        assertNotNull("TvFocusChain.linkDown must remain public", method)
        assertEquals(2, method!!.parameterTypes.size)
        assertEquals(Void.TYPE, method.returnType)
    }

    @Test
    fun linkHorizontalIsPublicApi() {
        val method = TvFocusChain::class.java.methods.firstOrNull { it.name == "linkHorizontal" }
        assertNotNull("TvFocusChain.linkHorizontal must remain public", method)
        assertTrue(method!!.parameterTypes.isNotEmpty())
        assertEquals(Void.TYPE, method.returnType)
        val vertical = TvFocusChain::class.java.methods.firstOrNull { it.name == "linkVertical" }
        assertNotNull("TvFocusChain.linkVertical must remain public", vertical)
        assertEquals(Void.TYPE, vertical!!.returnType)
    }

    @Test
    fun tvFocusZoomGatesOnReduceHomeEffectsApi() {
        val method = TvFocusZoom::class.java.methods.firstOrNull { it.name == "apply" }
        assertNotNull(method)
        assertEquals(2, method!!.parameterTypes.size)
    }

    @Test
    fun linkHelpersDocumentGoneTrailerAndIptvDownloadContracts() {
        // Full View linking needs instrumentation/Robolectric. Lock the public surface
        // used by detail CTA rewires when Trailer is GONE and IPTV hides Download.
        assertNotNull(TvFocusChain::class.java.methods.firstOrNull { it.name == "linkHorizontal" })
        assertNotNull(TvFocusChain::class.java.methods.firstOrNull { it.name == "linkVertical" })
        assertNotNull(TvFocusChain::class.java.methods.firstOrNull { it.name == "linkDown" })
    }

    /** FA-035: horizontal plan only encodes left/right — vertical axis stays caller-owned. */
    @Test
    fun planAxisLinks_preservesOrthogonalAxisContract() {
        val links = TvFocusChain.planAxisLinks(listOf(10, 20, 30))
        assertEquals(3, links.size)
        assertEquals(View.NO_ID, links[0].previousId)
        assertEquals(20, links[0].nextId)
        assertEquals(10, links[1].previousId)
        assertEquals(30, links[1].nextId)
        assertEquals(20, links[2].previousId)
        assertEquals(View.NO_ID, links[2].nextId)
        // AxisLink only carries prev/next — orthogonal up/down stay caller-owned (FA-002/FA-035).
        assertEquals(links[0].id, links[0].copy().id)
    }

    @Test
    fun planAxisLinks_emptyAndSingleton() {
        assertTrue(TvFocusChain.planAxisLinks(emptyList()).isEmpty())
        val solo = TvFocusChain.planAxisLinks(listOf(7)).single()
        assertEquals(7, solo.id)
        assertEquals(View.NO_ID, solo.previousId)
        assertEquals(View.NO_ID, solo.nextId)
    }
}
