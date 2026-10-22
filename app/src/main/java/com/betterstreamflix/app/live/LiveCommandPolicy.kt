package com.betterstreamflix.app.live

import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.notifications.LiveReminderPolicy
import com.betterstreamflix.app.utils.LiveHubCatalog
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pure Live Command rules — mode filter, EPG search, reminder rows, catch-up
 * hints, and command-action eligibility. JVM-safe for unit tests.
 */
object LiveCommandPolicy {

    const val SECTION_REMINDERS = "live_command_reminders"
    const val SECTION_SEARCH = "live_command_epg_search"
    const val MAX_SEARCH_HITS = 48
    const val MAX_REMINDER_ROWS = 24
    /** Kickoff within this window after start → catch-up hint. */
    const val CATCH_UP_AFTER_KICKOFF_MS = 20L * 60L * 1000L
    /** Kickoff within this window before start → up-next catch-up hint. */
    const val CATCH_UP_BEFORE_NEXT_MS = 15L * 60L * 1000L
    const val MIN_SEARCH_TOKEN_LEN = 2

    data class ReminderRow(
        val show: TvShow,
        val kickoffEpochMs: Long?,
        val fireAtMs: Long?,
        val minutesUntilKickoff: Long?,
        val snoozed: Boolean,
        val label: String,
    )

    data class CatchUpHint(
        val showKey: String,
        val messageKey: CatchUpKind,
        val detail: String?,
    )

    enum class CatchUpKind {
        STARTED_RECENTLY,
        UP_NEXT,
        LIVE_NOW,
    }

    enum class CommandAction {
        PINS,
        REMINDERS,
        AMBIENT,
        PIP_ZAP,
    }

    data class CommandSnapshot(
        val pinCount: Int,
        val reminderCount: Int,
        val remindersEnabled: Boolean,
        val ambientEnabled: Boolean,
        val ambientAvailable: Boolean,
        val pipCandidateCount: Int,
        val isTv: Boolean,
    )

    /**
     * Localized reminder row labels. JVM tests can pass English literals;
     * UI passes [android.content.Context.getString] results / format templates.
     */
    data class ReminderLabelResources(
        val snoozed: String,
        val live: String,
        /** Format with one long/int hours arg, e.g. "Starts in %1$d h". */
        val startsInHours: String,
        /** Format with one long/int minutes arg. */
        val startsInMinutes: String,
        /** Format with one long/int minutes-ago arg. */
        val startedMinutesAgo: String,
    ) {
        companion object {
            /** English defaults for JVM unit tests. */
            fun english() = ReminderLabelResources(
                snoozed = "Snoozed",
                live = "Live",
                startsInHours = "Starts in %1\$d h",
                startsInMinutes = "Starts in %1\$d min",
                startedMinutesAgo = "Started %1\$d min ago",
            )
        }
    }

    fun filterCategories(
        categories: List<Category>,
        mode: LiveCommandMode,
    ): List<Category> {
        val identity = mode.sectionIdentity ?: return categories.filter { it.list.isNotEmpty() }
        return categories.filter { it.identityKey == identity && it.list.isNotEmpty() }
    }

    /**
     * EPG search across title, provider, group, programNow / programNext.
     * Returns a single synthetic search shelf (empty list when query blank).
     */
    fun searchHits(
        categories: List<Category>,
        query: String,
        searchLabel: String,
        maxHits: Int = MAX_SEARCH_HITS,
    ): List<Category> {
        val locale = Locale.getDefault()
        val q = query.trim().lowercase(locale)
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(Regex("""\s+"""))
            .filter { it.length >= MIN_SEARCH_TOKEN_LEN }
        if (tokens.isEmpty()) return emptyList()

        val pool = LinkedHashMap<String, Pair<TvShow, Int>>()
        for (cat in categories) {
            if (cat.identityKey == SECTION_REMINDERS || cat.identityKey == SECTION_SEARCH) continue
            for (show in cat.list.filterIsInstance<TvShow>()) {
                val score = scoreShow(show, tokens) ?: continue
                val key = LiveHubCatalog.identityKey(show)
                val prev = pool[key]
                if (prev == null || score > prev.second) {
                    pool[key] = show to score
                }
            }
        }
        val ranked = pool.values
            .sortedByDescending { it.second }
            .take(maxHits)
            .map { it.first }
        if (ranked.isEmpty()) return emptyList()
        return listOf(
            Category("$searchLabel (${ranked.size})", ranked).also {
                it.identityKey = SECTION_SEARCH
            },
        )
    }

    fun scoreShow(show: TvShow, tokens: List<String>): Int? {
        val locale = Locale.getDefault()
        val title = show.title.lowercase(locale)
        val provider = show.providerName.orEmpty().lowercase(locale)
        val now = LiveHubCatalog.programNowOf(show).orEmpty().lowercase(locale)
        val next = LiveHubCatalog.programNextOf(show).orEmpty().lowercase(locale)
        val group = LiveHubCatalog.groupOf(show).orEmpty().lowercase(locale)
        val overview = show.overview.orEmpty().lowercase(locale)
        var score = 0
        for (token in tokens) {
            if (token.length < MIN_SEARCH_TOKEN_LEN) continue
            val hit = when {
                title == token -> 100
                title.startsWith(token) -> 80
                title.contains(token) -> 60
                now.contains(token) -> 55
                next.contains(token) -> 45
                // Group is often a shelf name (Sports/News) — do not over-rank as EPG.
                group.contains(token) -> 18
                provider.contains(token) -> 25
                overview.contains(token) -> 15
                else -> return null
            }
            score += hit
        }
        return score.takeIf { it > 0 }
    }

    fun buildReminderRows(
        favorites: List<TvShow>,
        nowMs: Long,
        snoozedUntil: (String) -> Long = { 0L },
        maxRows: Int = MAX_REMINDER_ROWS,
        labels: ReminderLabelResources = ReminderLabelResources.english(),
    ): List<ReminderRow> {
        val rows = ArrayList<ReminderRow>()
        for (show in favorites) {
            if (!LiveHubCatalog.isLiveFavorite(show)) continue
            val key = LiveHubCatalog.identityKey(show)
            val kickoff = LiveReminderPolicy.parseKickoffEpochMs(
                title = show.title,
                overview = show.overview,
                nowMs = nowMs,
            )
            val snoozed = snoozedUntil(key) > nowMs
            val fireAt = kickoff?.let { it - LiveReminderPolicy.LEAD_BEFORE_KICKOFF_MS }
            val minutes = kickoff?.let { ((it - nowMs).coerceAtLeast(0L) + 59_999L) / 60_000L }
            // Keep only parseable kickoff (upcoming or started-recently), or snoozed.
            val keep = when {
                kickoff != null && kickoff >= nowMs - CATCH_UP_AFTER_KICKOFF_MS -> true
                snoozed -> true
                else -> false
            }
            if (!keep) continue
            val label = reminderLabel(
                kickoffEpochMs = kickoff,
                nowMs = nowMs,
                snoozed = snoozed,
                programNow = LiveHubCatalog.programNowOf(show),
                labels = labels,
            )
            rows += ReminderRow(
                show = show,
                kickoffEpochMs = kickoff,
                fireAtMs = fireAt,
                minutesUntilKickoff = minutes,
                snoozed = snoozed,
                label = label,
            )
        }
        return rows
            .sortedWith(
                compareBy<ReminderRow> { it.snoozed }
                    .thenBy { it.kickoffEpochMs ?: Long.MAX_VALUE },
            )
            .take(maxRows)
    }

    fun remindersCategory(
        rows: List<ReminderRow>,
        label: String,
    ): Category {
        // Never mutate shared hub TvShow rows — copy for display overlay.
        val shows = rows.map { row ->
            row.show.copy().apply {
                programNow = row.label
                if (programNext.isNullOrBlank() && row.kickoffEpochMs != null) {
                    programNext = LiveHubCatalog.programNextOf(row.show)
                }
            }
        }
        return Category("$label (${shows.size})", shows).also {
            it.identityKey = SECTION_REMINDERS
        }
    }

    fun reminderLabel(
        kickoffEpochMs: Long?,
        nowMs: Long,
        snoozed: Boolean,
        programNow: String?,
        labels: ReminderLabelResources = ReminderLabelResources.english(),
    ): String {
        if (snoozed) return labels.snoozed
        if (kickoffEpochMs == null) {
            return programNow?.takeIf { it.isNotBlank() } ?: labels.live
        }
        val delta = kickoffEpochMs - nowMs
        return when {
            delta > TimeUnit.HOURS.toMillis(2) -> {
                val h = TimeUnit.MILLISECONDS.toHours(delta)
                String.format(Locale.getDefault(), labels.startsInHours, h)
            }
            delta > 0 -> {
                val m = (delta + 59_999L) / 60_000L
                String.format(Locale.getDefault(), labels.startsInMinutes, m)
            }
            delta > -CATCH_UP_AFTER_KICKOFF_MS -> {
                val m = ((-delta) + 59_999L) / 60_000L
                String.format(Locale.getDefault(), labels.startedMinutesAgo, m)
            }
            else -> programNow?.takeIf { it.isNotBlank() } ?: labels.live
        }
    }

    fun catchUpHint(show: TvShow, nowMs: Long): CatchUpHint? {
        val key = LiveHubCatalog.identityKey(show)
        val kickoff = LiveReminderPolicy.parseKickoffEpochMs(
            title = show.title,
            overview = show.overview,
            nowMs = nowMs,
        )
        if (kickoff != null) {
            val until = kickoff - nowMs
            if (until in 1..CATCH_UP_BEFORE_NEXT_MS) {
                val m = (until + 59_999L) / 60_000L
                return CatchUpHint(key, CatchUpKind.UP_NEXT, "${m}m")
            }
            val since = nowMs - kickoff
            if (since in 1 until CATCH_UP_AFTER_KICKOFF_MS) {
                val m = (since + 59_999L) / 60_000L
                return CatchUpHint(key, CatchUpKind.STARTED_RECENTLY, "${m}m")
            }
        }
        val next = LiveHubCatalog.programNextOf(show)
        if (!next.isNullOrBlank()) {
            return CatchUpHint(key, CatchUpKind.UP_NEXT, next)
        }
        val now = LiveHubCatalog.programNowOf(show)
        if (!now.isNullOrBlank()) {
            return CatchUpHint(key, CatchUpKind.LIVE_NOW, now)
        }
        return null
    }

    fun resolveVisible(
        categories: List<Category>,
        mode: LiveCommandMode,
        query: String,
        searchLabel: String,
        remindersLabel: String,
        favorites: List<TvShow>,
        nowMs: Long,
        snoozedUntil: (String) -> Long,
        labels: ReminderLabelResources = ReminderLabelResources.english(),
    ): List<Category> {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            // Search within the active mode shelves (All → all non-empty shelves).
            val scoped = when (mode) {
                LiveCommandMode.REMINDERS -> {
                    val favShelf = categories.filter {
                        it.identityKey == LiveHubCatalog.SECTION_FAVORITES && it.list.isNotEmpty()
                    }
                    favShelf.ifEmpty { categories.filter { it.list.isNotEmpty() } }
                }
                else -> filterCategories(categories, mode).ifEmpty {
                    categories.filter { it.list.isNotEmpty() }
                }
            }
            return searchHits(scoped, trimmed, searchLabel)
        }
        if (mode == LiveCommandMode.REMINDERS) {
            val rows = buildReminderRows(favorites, nowMs, snoozedUntil, labels = labels)
            if (rows.isEmpty()) return emptyList()
            return listOf(remindersCategory(rows, remindersLabel))
        }
        return filterCategories(categories, mode)
    }

    fun statusLine(
        mode: LiveCommandMode,
        query: String,
        /** Item hits for search; shelf count for Mode; ignored for dedicated kinds. */
        visibleCount: Int,
        reminderCount: Int,
        pinCount: Int,
        favoriteCount: Int = pinCount,
        shelfCount: Int = visibleCount,
    ): StatusKind {
        val q = query.trim()
        return when {
            q.isNotEmpty() -> StatusKind.Search(visibleCount, q)
            mode == LiveCommandMode.REMINDERS -> StatusKind.Reminders(reminderCount)
            mode == LiveCommandMode.SPORT -> StatusKind.SportZap
            mode == LiveCommandMode.NEWS -> StatusKind.News(visibleCount)
            mode == LiveCommandMode.FAVORITES -> StatusKind.Favorites(favoriteCount)
            else -> StatusKind.Mode(mode, shelfCount)
        }
    }

    sealed class StatusKind {
        data class Search(val hits: Int, val query: String) : StatusKind()
        data class Reminders(val count: Int) : StatusKind()
        data object SportZap : StatusKind()
        data class News(val count: Int) : StatusKind()
        data class Favorites(val favoriteCount: Int) : StatusKind()
        data class Mode(val mode: LiveCommandMode, val shelves: Int) : StatusKind()
    }

    fun visibleActions(snapshot: CommandSnapshot): List<CommandAction> {
        val out = ArrayList<CommandAction>(4)
        out += CommandAction.PINS
        out += CommandAction.REMINDERS
        if (snapshot.isTv && snapshot.ambientAvailable) {
            out += CommandAction.AMBIENT
        }
        if (snapshot.pipCandidateCount > 0) {
            out += CommandAction.PIP_ZAP
        }
        return out
    }

    /** Sport / News mode → prefer that zap ring; All / Favorites keep section zap. */
    fun preferredZapSection(mode: LiveCommandMode): String? = when (mode) {
        LiveCommandMode.SPORT -> LiveHubCatalog.SECTION_SPORT
        LiveCommandMode.NEWS -> LiveHubCatalog.SECTION_NEWS
        LiveCommandMode.CHANNELS -> LiveHubCatalog.SECTION_CHANNELS
        LiveCommandMode.FAVORITES -> LiveHubCatalog.SECTION_FAVORITES
        else -> null
    }
}
