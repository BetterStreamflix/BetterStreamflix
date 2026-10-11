package com.dskja.betterstreamflix.watchlist

import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.utils.UserPreferences
import java.util.concurrent.TimeUnit

/**
 * Weekly home-card policy for Watchlist Digest.
 *
 * Soft dismiss / CTA start a 7-day cool-down. Hard opt-out (“never”) hides forever
 * until the user re-enables the setting. Empty plans never show the card.
 */
object WatchlistDigestPolicy {

    /** Minimum gap between weekly digest presentations after dismiss / queue. */
    val COOLDOWN_MS: Long = TimeUnit.DAYS.toMillis(7)

    /** Soft cap — stop auto-showing after this many presentations. */
    const val MAX_HOME_CARD_SHOWS = 12

    fun shouldShow(
        episodes: List<Episode>,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!UserPreferences.homeWatchlistDigestEnabled) return false
        if (UserPreferences.homeWatchlistDigestDismissed) return false
        if (episodes.isEmpty()) return false
        if (UserPreferences.homeWatchlistDigestShowCount >= MAX_HOME_CARD_SHOWS) return false
        val last = maxOf(
            UserPreferences.homeWatchlistDigestLastDismissedAtMs,
            UserPreferences.homeWatchlistDigestLastShownAtMs,
        )
        if (last <= 0L) return true
        return nowMs - last >= COOLDOWN_MS
    }

    /** Soft dismiss — hide now, allow again after [COOLDOWN_MS]. */
    fun dismiss(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.homeWatchlistDigestLastDismissedAtMs = nowMs
        UserPreferences.homeWatchlistDigestShowCount =
            (UserPreferences.homeWatchlistDigestShowCount + 1).coerceAtMost(MAX_HOME_CARD_SHOWS + 1)
    }

    /** One-tap queue / CTA — start the weekly cool-down. */
    fun recordQueued(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.homeWatchlistDigestLastShownAtMs = nowMs
        UserPreferences.homeWatchlistDigestShowCount =
            (UserPreferences.homeWatchlistDigestShowCount + 1).coerceAtMost(MAX_HOME_CARD_SHOWS + 1)
        UserPreferences.homeWatchlistDigestLastQueuedAtMs = nowMs
    }

    /** Permanent opt-out for the home digest card. */
    fun neverShow() {
        UserPreferences.homeWatchlistDigestDismissed = true
    }
}
