package com.dskja.betterstreamflix.home

import com.dskja.betterstreamflix.models.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRemixTest {

    private fun cat(name: String, identityKey: String? = null) =
        Category(name = name, list = emptyList()).also { it.identityKey = identityKey }

    @Test
    fun parseOrder_defaultsAndFillsMissing() {
        assertEquals(HomeRemix.DEFAULT_ORDER, HomeRemix.parseOrder(null))
        assertEquals(HomeRemix.DEFAULT_ORDER, HomeRemix.parseOrder(""))
        assertEquals(
            listOf(
                HomeRemix.Block.LIVE,
                HomeRemix.Block.CONTINUE,
                HomeRemix.Block.COLLECTIONS,
            ),
            HomeRemix.parseOrder("live,continue"),
        )
    }

    @Test
    fun serialize_roundTrip() {
        val order = listOf(
            HomeRemix.Block.COLLECTIONS,
            HomeRemix.Block.LIVE,
            HomeRemix.Block.CONTINUE,
        )
        assertEquals(order, HomeRemix.parseOrder(HomeRemix.serializeOrder(order)))
    }

    @Test
    fun move_swapsBlocks() {
        val moved = HomeRemix.move(HomeRemix.DEFAULT_ORDER, from = 0, to = 2)
        assertEquals(
            listOf(
                HomeRemix.Block.LIVE,
                HomeRemix.Block.COLLECTIONS,
                HomeRemix.Block.CONTINUE,
            ),
            moved,
        )
    }

    @Test
    fun blockOf_mapsStableNames() {
        assertEquals(
            HomeRemix.Block.CONTINUE,
            HomeRemix.blockOf(cat(Category.CONTINUE_WATCHING)),
        )
        assertEquals(
            HomeRemix.Block.CONTINUE,
            HomeRemix.blockOf(cat(Category.FAMILY_FRIDAY)),
        )
        assertEquals(
            HomeRemix.Block.CONTINUE,
            HomeRemix.blockOf(cat("New this week", Category.NEW_THIS_WEEK)),
        )
        assertEquals(
            HomeRemix.Block.COLLECTIONS,
            HomeRemix.blockOf(cat(Category.SMART_CHILL_EVENING)),
        )
        assertEquals(
            HomeRemix.Block.LIVE,
            HomeRemix.blockOf(cat(Category.HOME_LIVE, Category.HOME_LIVE_ID)),
        )
        assertNull(HomeRemix.blockOf(cat(Category.FEATURED)))
        assertNull(HomeRemix.blockOf(cat(Category.FAVORITE_MOVIES)))
    }

    @Test
    fun apply_keepsFeaturedFirstAndRestAfter() {
        val categories = listOf(
            cat(Category.FEATURED),
            cat(Category.CONTINUE_WATCHING),
            cat(Category.HOME_LIVE, Category.HOME_LIVE_ID),
            cat(Category.SMART_WITH_KIDS),
            cat(Category.FAVORITE_MOVIES),
            cat("Provider Row"),
        )
        val order = listOf(
            HomeRemix.Block.COLLECTIONS,
            HomeRemix.Block.LIVE,
            HomeRemix.Block.CONTINUE,
        )
        val names = HomeRemix.apply(categories, order).map { it.name }
        assertEquals(Category.FEATURED, names.first())
        assertEquals(
            listOf(
                Category.FEATURED,
                Category.SMART_WITH_KIDS,
                Category.HOME_LIVE,
                Category.CONTINUE_WATCHING,
                Category.FAVORITE_MOVIES,
                "Provider Row",
            ),
            names,
        )
        assertTrue(names.indexOf(Category.SMART_WITH_KIDS) < names.indexOf(Category.HOME_LIVE))
        assertTrue(names.indexOf(Category.HOME_LIVE) < names.indexOf(Category.CONTINUE_WATCHING))
    }
}
