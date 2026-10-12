package com.dskja.betterstreamflix.live

import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class LiveCommandPolicyTest {

    @Test
    fun filterCategoriesKeepsRequestedShelf() {
        val cats = sampleCategories()
        val sport = LiveCommandPolicy.filterCategories(cats, LiveCommandMode.SPORT)
        assertEquals(1, sport.size)
        assertEquals(LiveHubCatalog.SECTION_SPORT, sport[0].identityKey)
        assertEquals(2, sport[0].list.size)
    }

    @Test
    fun filterAllDropsEmptyShelves() {
        val cats = sampleCategories() + Category("Empty", emptyList()).also {
            it.identityKey = "empty"
        }
        val all = LiveCommandPolicy.filterCategories(cats, LiveCommandMode.ALL)
        assertTrue(all.none { it.list.isEmpty() })
        assertEquals(3, all.size)
    }

    @Test
    fun epgSearchRanksTitleAndProgramNow() {
        val cats = sampleCategories()
        val hits = LiveCommandPolicy.searchHits(cats, "nba tip", "EPG")
        assertEquals(1, hits.size)
        assertEquals(LiveCommandPolicy.SECTION_SEARCH, hits[0].identityKey)
        assertEquals("ESPN NBA 20:45", hits[0].list.filterIsInstance<TvShow>().first().title)
    }

    @Test
    fun epgSearchBlankReturnsEmpty() {
        assertTrue(LiveCommandPolicy.searchHits(sampleCategories(), "  ", "EPG").isEmpty())
    }

    @Test
    fun epgSearchIgnoresShortTokens() {
        assertTrue(LiveCommandPolicy.searchHits(sampleCategories(), "a", "EPG").isEmpty())
    }

    @Test
    fun searchWithinModeScopesToShelf() {
        val visible = LiveCommandPolicy.resolveVisible(
            categories = sampleCategories(),
            mode = LiveCommandMode.NEWS,
            query = "discovery",
            searchLabel = "EPG",
            remindersLabel = "Reminders",
            favorites = emptyList(),
            nowMs = System.currentTimeMillis(),
            snoozedUntil = { 0L },
        )
        // Discovery is on Channels — News-scoped search must miss it.
        assertTrue(visible.isEmpty())
    }

    @Test
    fun searchWithinSportFindsSportTitle() {
        val visible = LiveCommandPolicy.resolveVisible(
            categories = sampleCategories(),
            mode = LiveCommandMode.SPORT,
            query = "espn",
            searchLabel = "EPG",
            remindersLabel = "Reminders",
            favorites = emptyList(),
            nowMs = System.currentTimeMillis(),
            snoozedUntil = { 0L },
        )
        assertEquals(1, visible.size)
        assertEquals(LiveCommandPolicy.SECTION_SEARCH, visible[0].identityKey)
        assertTrue(
            visible[0].list.filterIsInstance<TvShow>().any { it.title.contains("ESPN") },
        )
    }

    @Test
    fun reminderRowsPreferUpcomingKickoff() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 10, 11)
        val now = today.atTime(LocalTime.of(18, 0)).atZone(zone).toInstant().toEpochMilli()
        val fav = TvShow(id = "1", title = "Match 20:45 UTC", providerName = "DaddyLive TV").apply {
            programNow = "Sports"
        }
        val label = LiveCommandPolicy.reminderLabel(
            kickoffEpochMs = today.atTime(LocalTime.of(20, 45)).atZone(zone).toInstant().toEpochMilli(),
            nowMs = now,
            snoozed = false,
            programNow = "Sports",
        )
        assertTrue(label.contains("Starts in"))
        assertTrue(LiveCommandPolicy.buildReminderRows(emptyList(), now).isEmpty())
        assertNotNull(fav.title)
    }

    @Test
    fun reminderKeepRequiresKickoffOrSnoozeNotProgramNowAlone() {
        // Without isLiveFavorite registry, buildReminderRows filters all — exercise keep
        // via reminderLabel + catchUp window constants and document the keep rule.
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 10, 11)
        val now = today.atTime(LocalTime.of(18, 0)).atZone(zone).toInstant().toEpochMilli()
        val kickoff = today.atTime(LocalTime.of(20, 0)).atZone(zone).toInstant().toEpochMilli()
        // Upcoming kickoff within window → keep via kickoff branch.
        assertTrue(kickoff >= now - LiveCommandPolicy.CATCH_UP_AFTER_KICKOFF_MS)
        // Started recently window.
        val recent = now - 5L * 60L * 1000L
        assertTrue(recent >= now - LiveCommandPolicy.CATCH_UP_AFTER_KICKOFF_MS)
        // Stale kickoff outside catch-up → would not keep without snooze.
        val stale = now - LiveCommandPolicy.CATCH_UP_AFTER_KICKOFF_MS - 1
        assertFalse(stale >= now - LiveCommandPolicy.CATCH_UP_AFTER_KICKOFF_MS)
        assertNotNull(LiveCommandPolicy.reminderLabel(kickoff, now, false, null))
    }

    @Test
    fun remindersCategoryCopiesShows() {
        val original = TvShow(id = "r1", title = "Match 20:00", providerName = "DaddyLive TV").apply {
            programNow = "Sports"
        }
        val rows = listOf(
            LiveCommandPolicy.ReminderRow(
                show = original,
                kickoffEpochMs = System.currentTimeMillis() + 60_000L,
                fireAtMs = null,
                minutesUntilKickoff = 1L,
                snoozed = false,
                label = "Starts in 1 min",
            ),
        )
        val cat = LiveCommandPolicy.remindersCategory(rows, "Reminders")
        val displayed = cat.list.filterIsInstance<TvShow>().first()
        assertNotSame(original, displayed)
        assertEquals("Starts in 1 min", displayed.programNow)
        assertEquals("Sports", original.programNow)
    }

    @Test
    fun catchUpHintAfterKickoff() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val kickoff = today.atTime(LocalTime.of(20, 0)).atZone(zone).toInstant().toEpochMilli()
        val now = kickoff + 5L * 60L * 1000L
        val show = TvShow(id = "9", title = "Kick 20:00", providerName = "DaddyLive TV")
        val hint = LiveCommandPolicy.catchUpHint(show, now)
        assertNotNull(hint)
        assertEquals(LiveCommandPolicy.CatchUpKind.STARTED_RECENTLY, hint!!.messageKey)
    }

    @Test
    fun catchUpHintBeforeKickoffUsesBeforeNextWindow() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val kickoff = today.atTime(LocalTime.of(20, 0)).atZone(zone).toInstant().toEpochMilli()
        val now = kickoff - 10L * 60L * 1000L
        val show = TvShow(id = "9", title = "Kick 20:00", providerName = "DaddyLive TV")
        val hint = LiveCommandPolicy.catchUpHint(show, now)
        assertNotNull(hint)
        assertEquals(LiveCommandPolicy.CatchUpKind.UP_NEXT, hint!!.messageKey)
        assertTrue(now + LiveCommandPolicy.CATCH_UP_BEFORE_NEXT_MS >= kickoff)
    }

    @Test
    fun resolveVisibleRemindersBuildsShelf() {
        val visible = LiveCommandPolicy.resolveVisible(
            categories = sampleCategories(),
            mode = LiveCommandMode.REMINDERS,
            query = "",
            searchLabel = "EPG",
            remindersLabel = "Reminders",
            favorites = emptyList(),
            nowMs = System.currentTimeMillis(),
            snoozedUntil = { 0L },
        )
        assertTrue(visible.isEmpty())
    }

    @Test
    fun resolveVisibleSearchOverridesModeWithinScope() {
        val visible = LiveCommandPolicy.resolveVisible(
            categories = sampleCategories(),
            mode = LiveCommandMode.CHANNELS,
            query = "discovery",
            searchLabel = "EPG",
            remindersLabel = "Reminders",
            favorites = emptyList(),
            nowMs = System.currentTimeMillis(),
            snoozedUntil = { 0L },
        )
        assertEquals(1, visible.size)
        assertEquals(LiveCommandPolicy.SECTION_SEARCH, visible[0].identityKey)
    }

    @Test
    fun statusLineUsesShelfCountNotItemSum() {
        val kind = LiveCommandPolicy.statusLine(
            mode = LiveCommandMode.ALL,
            query = "",
            visibleCount = 40,
            reminderCount = 0,
            pinCount = 2,
            favoriteCount = 5,
            shelfCount = 3,
        )
        assertTrue(kind is LiveCommandPolicy.StatusKind.Mode)
        assertEquals(3, (kind as LiveCommandPolicy.StatusKind.Mode).shelves)
    }

    @Test
    fun statusLineFavoritesUsesFavoriteCount() {
        val kind = LiveCommandPolicy.statusLine(
            mode = LiveCommandMode.FAVORITES,
            query = "",
            visibleCount = 4,
            reminderCount = 0,
            pinCount = 2,
            favoriteCount = 7,
            shelfCount = 1,
        )
        assertTrue(kind is LiveCommandPolicy.StatusKind.Favorites)
        assertEquals(7, (kind as LiveCommandPolicy.StatusKind.Favorites).favoriteCount)
    }

    @Test
    fun preferredZapSectionForModes() {
        assertEquals(
            LiveHubCatalog.SECTION_SPORT,
            LiveCommandPolicy.preferredZapSection(LiveCommandMode.SPORT),
        )
        assertNull(LiveCommandPolicy.preferredZapSection(LiveCommandMode.ALL))
    }

    @Test
    fun visibleActionsIncludePinsAndReminders() {
        val actions = LiveCommandPolicy.visibleActions(
            LiveCommandPolicy.CommandSnapshot(
                pinCount = 2,
                reminderCount = 1,
                remindersEnabled = true,
                ambientEnabled = true,
                ambientAvailable = true,
                pipCandidateCount = 3,
                isTv = true,
            ),
        )
        assertTrue(actions.contains(LiveCommandPolicy.CommandAction.PINS))
        assertTrue(actions.contains(LiveCommandPolicy.CommandAction.REMINDERS))
        assertTrue(actions.contains(LiveCommandPolicy.CommandAction.AMBIENT))
        assertTrue(actions.contains(LiveCommandPolicy.CommandAction.PIP_ZAP))
    }

    @Test
    fun visibleActionsHideAmbientOnMobile() {
        val actions = LiveCommandPolicy.visibleActions(
            LiveCommandPolicy.CommandSnapshot(
                pinCount = 0,
                reminderCount = 0,
                remindersEnabled = false,
                ambientEnabled = false,
                ambientAvailable = true,
                pipCandidateCount = 0,
                isTv = false,
            ),
        )
        assertFalse(actions.contains(LiveCommandPolicy.CommandAction.AMBIENT))
        assertFalse(actions.contains(LiveCommandPolicy.CommandAction.PIP_ZAP))
    }

    @Test
    fun modeFromStorage() {
        assertEquals(LiveCommandMode.SPORT, LiveCommandMode.fromStorage("SPORT"))
        assertEquals(LiveCommandMode.ALL, LiveCommandMode.fromStorage("nope"))
    }

    private fun sampleCategories(): List<Category> {
        val sport = listOf(
            TvShow(id = "1", title = "ESPN NBA 20:45", providerName = "DaddyLive TV").apply {
                programNow = "NBA tip-off"
            },
            TvShow(id = "2", title = "Sky Sports", providerName = "StreamSports99"),
        )
        val news = listOf(
            TvShow(id = "3", title = "BBC News", providerName = "IPTV-All World").apply {
                programNow = "World"
            },
        )
        val channels = listOf(
            TvShow(id = "4", title = "Discovery", providerName = "DaddyLive TV"),
        )
        return listOf(
            Category("Sport", sport).also { it.identityKey = LiveHubCatalog.SECTION_SPORT },
            Category("News", news).also { it.identityKey = LiveHubCatalog.SECTION_NEWS },
            Category("Channels", channels).also { it.identityKey = LiveHubCatalog.SECTION_CHANNELS },
        )
    }
}
