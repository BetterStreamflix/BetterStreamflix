package com.dskja.betterstreamflix.watchlistweek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class WatchlistWeekRulesTest {

    @Test
    fun weekBoundsMondayFirstForGermany() {
        // Wednesday 2026-10-07 → week Mon 5 … Sun 11
        val week = WatchlistWeekRules.weekBounds(LocalDate.of(2026, 10, 7), Locale.GERMANY)
        assertEquals(LocalDate.of(2026, 10, 5), week.start)
        assertEquals(LocalDate.of(2026, 10, 11), week.endInclusive)
    }

    @Test
    fun weekBoundsSundayFirstForUs() {
        // Wednesday 2026-10-07 → week Sun 4 … Sat 10
        val week = WatchlistWeekRules.weekBounds(LocalDate.of(2026, 10, 7), Locale.US)
        assertEquals(LocalDate.of(2026, 10, 4), week.start)
        assertEquals(LocalDate.of(2026, 10, 10), week.endInclusive)
    }

    @Test
    fun parseAirDateAcceptsIsoAndRejectsJunk() {
        assertEquals(LocalDate.of(2026, 10, 8), WatchlistWeekRules.parseAirDate("2026-10-08"))
        assertEquals(LocalDate.of(2026, 10, 8), WatchlistWeekRules.parseAirDate("2026-10-08T20:00:00Z"))
        assertNull(WatchlistWeekRules.parseAirDate(null))
        assertNull(WatchlistWeekRules.parseAirDate(""))
        assertNull(WatchlistWeekRules.parseAirDate("not-a-date"))
    }

    @Test
    fun bucketIncludesEdgesAndExcludesOutside() {
        val week = WatchlistWeekRules.weekBounds(LocalDate.of(2026, 10, 7), Locale.GERMANY)
        val tips = listOf(
            tip("A", LocalDate.of(2026, 10, 5), season = 1, episode = 1), // Mon edge
            tip("B", LocalDate.of(2026, 10, 11), season = 1, episode = 2), // Sun edge
            tip("C", LocalDate.of(2026, 10, 4), season = 1, episode = 3), // outside
            tip("D", LocalDate.of(2026, 10, 12), season = 1, episode = 4), // outside
        )
        val days = WatchlistWeekRules.bucket(tips, week, Locale.GERMANY)
        assertEquals(7, days.size)
        val flat = WatchlistWeekRules.flatten(days)
        assertEquals(2, flat.size)
        assertTrue(flat.any { it.showTitle == "A" })
        assertTrue(flat.any { it.showTitle == "B" })
        assertFalse(flat.any { it.showTitle == "C" })
        assertFalse(flat.any { it.showTitle == "D" })
    }

    @Test
    fun bucketDedupesSameEpisodeAndSortsDayMajor() {
        val week = LocalDate.of(2026, 10, 5)..LocalDate.of(2026, 10, 11)
        val tips = listOf(
            tip("Zebra", LocalDate.of(2026, 10, 6), season = 2, episode = 1),
            tip("Alpha", LocalDate.of(2026, 10, 6), season = 1, episode = 2),
            tip("Alpha", LocalDate.of(2026, 10, 6), season = 1, episode = 2), // dup
            tip("Mid", LocalDate.of(2026, 10, 5), season = 1, episode = 1),
        )
        val flat = WatchlistWeekRules.flatten(
            WatchlistWeekRules.bucket(tips, week, Locale.US),
        )
        assertEquals(3, flat.size)
        assertEquals("Mid", flat[0].showTitle)
        assertEquals("Alpha", flat[1].showTitle)
        assertEquals("Zebra", flat[2].showTitle)
    }

    @Test
    fun emptyWeekYieldsEmptyFlatten() {
        val week = LocalDate.of(2026, 10, 5)..LocalDate.of(2026, 10, 11)
        val flat = WatchlistWeekRules.flatten(
            WatchlistWeekRules.bucket(emptyList(), week, Locale.GERMANY),
        )
        assertTrue(flat.isEmpty())
    }

    @Test
    fun shortDayLabelTitlecases() {
        val label = WatchlistWeekRules.shortDayLabel(LocalDate.of(2026, 10, 5), Locale.US)
        assertEquals("Mon", label)
    }

    private fun tip(
        title: String,
        air: LocalDate,
        season: Int,
        episode: Int,
    ) = WatchlistWeekRules.AirTip(
        showKey = "p|$title",
        showTitle = title,
        providerShowId = title.lowercase(Locale.US),
        season = season,
        episode = episode,
        airDate = air,
        episodeId = "$title-S${season}E$episode",
    )
}
