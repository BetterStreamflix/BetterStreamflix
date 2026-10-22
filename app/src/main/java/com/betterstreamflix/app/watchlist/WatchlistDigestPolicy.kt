package com.betterstreamflix.app.watchlist

import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.utils.UserPreferences
import java.util.concurrent.TimeUnit

/**
 * Weekly home-card policy for Watchlist Digest.
 *
 * Soft dismiss / CTA start a 7-day cool-down. Hard opt-out (“never”) hides forever
 * until the user re-enables the setting. Empty plans never show the card.
 * Hidden entirely on Kids profiles.
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
        if (ProfileManager.isKidsActive()) return false
        if (!UserPreferences.homeWatchlistDigestEnabled) return false
        if (UserPreferences.homeWatchlistDigestDismissed) return false
        if (episodes.isEmpty()) return false
        if (UserPreferences.homeWatchlistDigestShowCount >= MAX_HOME_CARD_SHOWS) return false
        // Cool-down after dismiss / successful queue — not after mere display.
        val last = maxOf(
            UserPreferences.homeWatchlistDigestLastDismissedAtMs,
            UserPreferences.homeWatchlistDigestLastQueuedAtMs,
        )
        if (last <= 0L) return true
        return nowMs - last >= COOLDOWN_MS
    }

    /**
     * Record a Home presentation (show-cap + lastShown). Does not start cool-down.
     * Rematerialize within [COOLDOWN_MS] only refreshes the timestamp (no cap burn).
     */
    fun recordShown(nowMs: Long = System.currentTimeMillis()) {
        val last = UserPreferences.homeWatchlistDigestLastShownAtMs
        if (last > 0L && nowMs - last < COOLDOWN_MS) {
            UserPreferences.homeWatchlistDigestLastShownAtMs = nowMs
            return
        }
        UserPreferences.homeWatchlistDigestLastShownAtMs = nowMs
        UserPreferences.homeWatchlistDigestShowCount =
            (UserPreferences.homeWatchlistDigestShowCount + 1).coerceAtMost(MAX_HOME_CARD_SHOWS + 1)
    }

    /** Soft dismiss — hide now, allow again after [COOLDOWN_MS]. */
    fun dismiss(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.homeWatchlistDigestLastDismissedAtMs = nowMs
    }

    /** One-tap queue / CTA — start the weekly cool-down (call only after seed/nav ok). */
    fun recordQueued(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.homeWatchlistDigestLastShownAtMs = nowMs
        UserPreferences.homeWatchlistDigestLastQueuedAtMs = nowMs
    }

    /** Permanent opt-out for the home digest card. */
    fun neverShow() {
        UserPreferences.homeWatchlistDigestDismissed = true
    }

    /** Settings re-enable: clear dismiss + reset presentation cap / timestamps. */
    fun resetForReEnable() {
        UserPreferences.homeWatchlistDigestDismissed = false
        UserPreferences.homeWatchlistDigestShowCount = 0
        UserPreferences.homeWatchlistDigestLastShownAtMs = 0L
        UserPreferences.homeWatchlistDigestLastDismissedAtMs = 0L
        UserPreferences.homeWatchlistDigestLastQueuedAtMs = 0L
    }
}
