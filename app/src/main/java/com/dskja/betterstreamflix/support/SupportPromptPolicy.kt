package com.dskja.betterstreamflix.support

import com.dskja.betterstreamflix.utils.UserPreferences
import java.util.concurrent.TimeUnit

/**
 * Smart startup / home-card policy for the Support / tip (“Trinkgeld”) flow.
 *
 * Rules:
 * - Wait until the user has watched ~[EPISODES_BEFORE_PROMPT] episodes.
 * - Soft prompts are dismissible with a multi-day cool-down.
 * - Hard opt-out (“never again”) stops all soft prompts.
 * - Never blocks playback; never spam (cap + cool-down).
 */
object SupportPromptPolicy {

    /** Episode finishes required before any soft Support prompt. */
    const val EPISODES_BEFORE_PROMPT = 10

    /** Minimum gap between soft prompts when the user did not opt out. */
    val COOLDOWN_MS: Long = TimeUnit.DAYS.toMillis(5)

    /** Soft cap — after this many startup shows we stop unless they open Support themselves. */
    const val MAX_SOFT_SHOWS = 8

    /** Soft cap for the home Support card (independent of startup). */
    const val MAX_HOME_CARD_SHOWS = 6

    /** Bound unique episode-id tracking stored in prefs. */
    private const val MAX_TRACKED_EPISODE_IDS = 48

    fun hasReachedWatchThreshold(): Boolean =
        UserPreferences.supportEpisodesWatchedCount >= EPISODES_BEFORE_PROMPT

    /**
     * Record a meaningfully finished episode once per id.
     * Safe to call from player IO threads; prefs writes are synchronized by SharedPreferences.
     */
    fun recordEpisodeWatched(episodeId: String) {
        val id = episodeId.trim()
        if (id.isEmpty()) return
        val tracked = UserPreferences.supportEpisodeIdsCounted.toMutableSet()
        if (!tracked.add(id)) return
        while (tracked.size > MAX_TRACKED_EPISODE_IDS) {
            tracked.remove(tracked.first())
        }
        UserPreferences.supportEpisodeIdsCounted = tracked
        val next = (UserPreferences.supportEpisodesWatchedCount + 1)
            .coerceAtMost(EPISODES_BEFORE_PROMPT + MAX_HOME_CARD_SHOWS + 5)
        UserPreferences.supportEpisodesWatchedCount = next
    }

    /**
     * Lift the counter when Room already knows about watched episodes
     * (existing installs) without waiting for 10 new finishes.
     */
    fun seedWatchedCountFromDatabase(watchedCount: Int) {
        if (watchedCount <= 0) return
        val capped = watchedCount.coerceAtMost(EPISODES_BEFORE_PROMPT + MAX_HOME_CARD_SHOWS + 5)
        if (capped > UserPreferences.supportEpisodesWatchedCount) {
            UserPreferences.supportEpisodesWatchedCount = capped
        }
    }

    fun shouldShowStartup(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (UserPreferences.neverShowSupportOnStart) return false
        if (!hasReachedWatchThreshold()) return false
        if (UserPreferences.supportStartupShowCount >= MAX_SOFT_SHOWS) return false
        val last = UserPreferences.supportStartupLastShownAtMs
        if (last <= 0L) return true
        return nowMs - last >= COOLDOWN_MS
    }

    fun recordShown(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.supportStartupLastShownAtMs = nowMs
        UserPreferences.supportStartupShowCount =
            (UserPreferences.supportStartupShowCount + 1).coerceAtMost(MAX_SOFT_SHOWS + 1)
    }

    fun remindLater(nowMs: Long = System.currentTimeMillis()) {
        // Push next eligibility to a full cooldown from now without counting as a hard opt-out.
        UserPreferences.supportStartupLastShownAtMs = nowMs
    }

    fun shouldShowHomeCard(nowMs: Long = System.currentTimeMillis()): Boolean {
        // Permanent home hide (legacy “dismiss forever”) or global never-again.
        if (UserPreferences.homeSupportCardDismissed) return false
        if (UserPreferences.neverShowSupportOnStart) return false
        if (!hasReachedWatchThreshold()) return false
        if (UserPreferences.homeSupportCardShowCount >= MAX_HOME_CARD_SHOWS) return false
        // Stay visible until soft-dismiss / CTA / never — cool-down only after an action.
        val last = maxOf(
            UserPreferences.homeSupportCardLastDismissedAtMs,
            UserPreferences.homeSupportCardLastShownAtMs,
        )
        if (last <= 0L) return true
        return nowMs - last >= COOLDOWN_MS
    }

    /** Soft dismiss — hide now, allow again after [COOLDOWN_MS]. */
    fun dismissHomeCard(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.homeSupportCardLastDismissedAtMs = nowMs
        UserPreferences.homeSupportCardShowCount =
            (UserPreferences.homeSupportCardShowCount + 1).coerceAtMost(MAX_HOME_CARD_SHOWS + 1)
    }

    /** CTA opened Support hub from the home card — same cool-down, no permanent opt-out. */
    fun openHomeCard(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.homeSupportCardLastShownAtMs = nowMs
        UserPreferences.homeSupportCardShowCount =
            (UserPreferences.homeSupportCardShowCount + 1).coerceAtMost(MAX_HOME_CARD_SHOWS + 1)
        markHubVisited(nowMs)
    }

    /** Permanent opt-out for the home card (and startup soft-prompt). */
    fun neverShowHomeCard() {
        UserPreferences.homeSupportCardDismissed = true
        UserPreferences.neverShowSupportOnStart = true
    }

    fun markHubVisited(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.supportHubLastVisitedAtMs = nowMs
        UserPreferences.supportHubVisitCount = UserPreferences.supportHubVisitCount + 1
    }
}
