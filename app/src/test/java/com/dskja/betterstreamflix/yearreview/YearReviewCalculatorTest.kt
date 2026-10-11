package com.dskja.betterstreamflix.yearreview

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YearReviewCalculatorTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun hoursFromMoviesAndEpisodes() {
        val movies = listOf(
            YearReviewMovieEvent("m1", "Film", 120, ms(2026, Calendar.MARCH, 1)),
            YearReviewMovieEvent("m2", "Short", null, ms(2026, Calendar.APRIL, 2)),
        )
        val episodes = listOf(
            ep("e1", "show:a", "Show A", 45, ms(2026, Calendar.MAY, 1)),
            ep("e2", "show:a", "Show A", 45, ms(2026, Calendar.MAY, 1, hour = 1)),
        )
        val stats = YearReviewCalculator.compute(
            year = 2026,
            profileName = "Alex",
            movies = movies,
            episodes = episodes,
            timeZone = utc,
        )
        // 120 + 100 (default movie) + 45 + 45 = 310 min → 5.2h
        assertEquals(5.2, stats.totalHours, 0.001)
        assertEquals(2, stats.movieCount)
        assertEquals(2, stats.episodeCount)
        assertTrue(stats.hasActivity)
    }

    @Test
    fun excludesOtherYears() {
        val movies = listOf(
            YearReviewMovieEvent("m1", "Old", 90, ms(2025, Calendar.DECEMBER, 31)),
            YearReviewMovieEvent("m2", "New", 90, ms(2026, Calendar.JANUARY, 1)),
        )
        val stats = YearReviewCalculator.compute(
            year = 2026,
            profileName = "Alex",
            movies = movies,
            episodes = emptyList(),
            timeZone = utc,
        )
        assertEquals(1, stats.movieCount)
        assertEquals(1.5, stats.totalHours, 0.001)
    }

    @Test
    fun topSeriesRanksByEpisodeCount() {
        val episodes = listOf(
            ep("1", "s:a", "Alpha", 40, ms(2026, Calendar.JANUARY, 1)),
            ep("2", "s:a", "Alpha", 40, ms(2026, Calendar.JANUARY, 2)),
            ep("3", "s:a", "Alpha", 40, ms(2026, Calendar.JANUARY, 3)),
            ep("4", "s:b", "Beta", 40, ms(2026, Calendar.FEBRUARY, 1)),
            ep("5", "s:b", "Beta", 40, ms(2026, Calendar.FEBRUARY, 2)),
            ep("6", "s:c", "Gamma", 40, ms(2026, Calendar.MARCH, 1)),
        )
        val top = YearReviewCalculator.rankTopSeries(episodes)
        assertEquals(3, top.size)
        assertEquals("Alpha", top[0].title)
        assertEquals(3, top[0].episodeCount)
        assertEquals("Beta", top[1].title)
        assertEquals("Gamma", top[2].title)
    }

    @Test
    fun longestBingeSplitsOnThreeHourGap() {
        val base = ms(2026, Calendar.JUNE, 10, hour = 18)
        val episodes = listOf(
            ep("1", "s:a", "Binge Show", 45, base),
            ep("2", "s:a", "Binge Show", 45, base + 30 * 60_000L),
            ep("3", "s:a", "Binge Show", 45, base + 60 * 60_000L),
            ep("4", "s:a", "Binge Show", 45, base + 5 * 60 * 60_000L), // new session
            ep("5", "s:a", "Binge Show", 45, base + 5 * 60 * 60_000L + 20 * 60_000L),
            ep("6", "s:b", "Other", 45, base),
            ep("7", "s:b", "Other", 45, base + 10 * 60_000L),
        )
        val binge = YearReviewCalculator.findLongestBinge(episodes)
        assertNotNull(binge)
        assertEquals("Binge Show", binge!!.title)
        assertEquals(3, binge.episodeCount)
    }

    @Test
    fun longestBingeNullWhenNoMultiEpisodeSession() {
        val episodes = listOf(
            ep("1", "s:a", "Solo", 45, ms(2026, Calendar.JULY, 1)),
            ep("2", "s:a", "Solo", 45, ms(2026, Calendar.JULY, 2)), // >3h gap next day alone? actually next day is big gap
        )
        // Two finishes a day apart → two sessions of size 1 → null
        val binge = YearReviewCalculator.findLongestBinge(episodes)
        assertNull(binge)
    }

    @Test
    fun inProgressContributesPositionMinutes() {
        val movies = listOf(
            YearReviewMovieEvent(
                id = "m1",
                title = "Halfway",
                runtimeMinutes = 120,
                watchedAtMs = ms(2026, Calendar.AUGUST, 1),
                inProgressPositionMs = 30 * 60_000L,
            ),
        )
        val stats = YearReviewCalculator.compute(
            year = 2026,
            profileName = "Alex",
            movies = movies,
            episodes = emptyList(),
            timeZone = utc,
        )
        assertEquals(0.5, stats.totalHours, 0.001)
        assertEquals(0, stats.movieCount) // in-progress not counted as finished movie
        assertTrue(stats.hasActivity)
    }

    private fun ep(
        id: String,
        showKey: String,
        title: String,
        runtime: Int,
        at: Long,
    ) = YearReviewEpisodeEvent(
        id = id,
        showKey = showKey,
        showTitle = title,
        showPoster = null,
        runtimeMinutes = runtime,
        watchedAtMs = at,
    )

    private fun ms(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
    ): Long = Calendar.getInstance(utc).apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour)
    }.timeInMillis
}
