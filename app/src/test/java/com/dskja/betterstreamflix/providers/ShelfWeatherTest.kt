package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfWeatherTest {

    @Test
    fun clearWhenNoProviderStamps() {
        val category = Category(
            name = "Trending",
            list = listOf(movie("A", provider = null)),
        )
        assertEquals(ShelfWeather.Level.CLEAR, ShelfWeather.forecast(category).level)
    }

    @Test
    fun stormForQuarantinedSource() {
        val category = Category(
            name = "Popular",
            list = listOf(movie("A", provider = "Fanpelis")),
        )
        val forecast = ShelfWeather.forecast(category)
        assertEquals(ShelfWeather.Level.STORM, forecast.level)
        assertEquals(ShelfWeather.Reason.QUARANTINED, forecast.reason)
        assertEquals("Fanpelis", forecast.providerName)
        assertTrue(forecast.shouldWarn)
        assertEquals(ShelfWeather.STORM_ALPHA, forecast.contentAlpha, 0.001f)
    }

    @Test
    fun stormForParkedDefault() {
        val forecast = ShelfWeather.forecastForProvider("StreamingIta")
        assertEquals(ShelfWeather.Level.STORM, forecast.level)
        assertEquals(ShelfWeather.Reason.PARKED, forecast.reason)
    }

    @Test
    fun hazeForOpenCircuit() {
        val name = "ShelfWeatherCircuit"
        ProviderSmoke.noteHomeSuccess(name)
        repeat(ProviderSmoke.CIRCUIT_FAILURE_THRESHOLD) {
            ProviderSmoke.noteHomeFailure(name)
        }
        try {
            val forecast = ShelfWeather.forecastForProvider(name)
            assertEquals(ShelfWeather.Level.HAZE, forecast.level)
            assertEquals(ShelfWeather.Reason.CIRCUIT_OPEN, forecast.reason)
            assertEquals(ShelfWeather.HAZE_ALPHA, forecast.contentAlpha, 0.001f)
        } finally {
            ProviderSmoke.noteHomeSuccess(name)
        }
    }

    @Test
    fun hazeForFlakyWithoutOpenCircuit() {
        val name = "ShelfWeatherFlaky"
        ProviderSmoke.noteHomeSuccess(name)
        ProviderSmoke.noteHomeFailure(name)
        ProviderSmoke.noteHomeFailure(name)
        try {
            assertFalse(ProviderSmoke.peekHomeCircuitOpen(name))
            val forecast = ShelfWeather.forecastForProvider(name)
            assertEquals(ShelfWeather.Level.HAZE, forecast.level)
            assertEquals(ShelfWeather.Reason.FLAKY, forecast.reason)
        } finally {
            ProviderSmoke.noteHomeSuccess(name)
        }
    }

    @Test
    fun mixedShelfIgnoresSingleBadStamp() {
        val category = Category(
            name = Category.CONTINUE_WATCHING,
            list = listOf(
                movie("Healthy", provider = "SerienStream"),
                tv("Bad", provider = "AnyMovie"),
            ),
        )
        // 1:1 — no strict majority, no unique dominant → clear (not worst-of-one).
        assertEquals(ShelfWeather.Level.CLEAR, ShelfWeather.forecast(category).level)
    }

    @Test
    fun mixedShelfStormsWhenDominantUnhealthy() {
        val category = Category(
            name = Category.CONTINUE_WATCHING,
            list = listOf(
                movie("A", provider = "AnyMovie"),
                movie("B", provider = "AnyMovie"),
                tv("C", provider = "SerienStream"),
            ),
        )
        val forecast = ShelfWeather.forecast(category)
        assertEquals(ShelfWeather.Level.STORM, forecast.level)
        assertEquals("AnyMovie", forecast.providerName)
    }

    @Test
    fun featuredSkipped() {
        val category = Category(
            name = Category.FEATURED,
            list = listOf(movie("A", provider = "Fanpelis")),
        )
        assertFalse(ShelfWeather.appliesTo(category))
        assertEquals(ShelfWeather.Level.CLEAR, ShelfWeather.forecast(category).level)
    }

    @Test
    fun identityKeyFallbackOnlyForKnownProvider() {
        val known = Category(
            name = "Searching…",
            list = listOf(movie("A", provider = null)),
        ).also { it.identityKey = "Kidraz" }
        val forecast = ShelfWeather.forecast(known)
        assertEquals(ShelfWeather.Level.STORM, forecast.level)
        assertEquals("Kidraz", forecast.providerName)

        val shelfId = Category(
            name = "Continue Watching",
            list = listOf(movie("A", provider = null)),
        ).also { it.identityKey = "home_downloads" }
        assertEquals(ShelfWeather.Level.CLEAR, ShelfWeather.forecast(shelfId).level)
    }

    @Test
    fun healthyProviderStaysClear() {
        val forecast = ShelfWeather.forecastForProvider("SerienStream")
        assertEquals(ShelfWeather.CLEAR, forecast)
        assertFalse(forecast.shouldWarn)
    }

    private fun movie(title: String, provider: String?): Movie =
        Movie(
            id = title,
            title = title,
            providerName = provider,
        ).also { it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM }

    private fun tv(title: String, provider: String?): TvShow =
        TvShow(
            id = title,
            title = title,
            providerName = provider,
        ).also { it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM }
}
