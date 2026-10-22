package com.betterstreamflix.app.support

import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.utils.UserPreferences
import java.util.concurrent.TimeUnit

/**
 * Post–season-finale Support nudge (“Thanks After Binge”).
 *
 * Rules:
 * - Only after a season finale (caller-gated), never mid-season.
 * - Kids profiles never see it.
 * - Guest profiles never see it.
 * - Per-profile “never again” opts out (long-press dismiss).
 * - Soft show once, then a long cool-down ([COOLDOWN_MS]).
 * - Hard lifetime cap — never spam.
 */
object ThanksAfterBingePolicy {

    /** Long pause after a show / dismiss / CTA — “lange Pause”. */
    val COOLDOWN_MS: Long = TimeUnit.DAYS.toMillis(60)

    /** Soft lifetime cap across the whole install/profile prefs. */
    const val MAX_SHOWS = 3

    /**
     * Pure season-finale check: no next episode, or next is a different season.
     *
     * Missing season metadata ([currentSeasonNumber] null) is **not** a finale
     * (TB-008) — episode types without a season number never qualify.
     */
    fun isSeasonFinale(currentSeasonNumber: Int?, nextSeasonNumber: Int?): Boolean {
        if (currentSeasonNumber == null) return false
        if (nextSeasonNumber == null) return false
        if (nextSeasonNumber == 0) return false
        return nextSeasonNumber != currentSeasonNumber
    }

    /**
     * Pure persistence gate for tests (TB-006) — mirrors [shouldShow] without
     * reading [UserPreferences] / [ProfileManager].
     */
    fun shouldShowState(
        isKids: Boolean,
        isGuest: Boolean,
        neverShow: Boolean,
        showCount: Int,
        lastShownAtMs: Long,
        lastDismissedAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (isKids || isGuest) return false
        if (neverShow) return false
        if (showCount >= MAX_SHOWS) return false
        val last = maxOf(lastShownAtMs, lastDismissedAtMs)
        if (last <= 0L) return true
        return nowMs - last >= COOLDOWN_MS
    }

    fun shouldShow(nowMs: Long = System.currentTimeMillis()): Boolean =
        shouldShowState(
            isKids = ProfileManager.isKidsActive(),
            isGuest = ProfileManager.isGuestActive(),
            neverShow = UserPreferences.neverShowThanksAfterBinge,
            showCount = UserPreferences.thanksAfterBingeShowCount,
            lastShownAtMs = UserPreferences.thanksAfterBingeLastShownAtMs,
            lastDismissedAtMs = UserPreferences.thanksAfterBingeLastDismissedAtMs,
            nowMs = nowMs,
        )

    /** Panel became visible — start the long cool-down immediately (anti-spam). */
    fun recordShown(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.thanksAfterBingeLastShownAtMs = nowMs
        UserPreferences.thanksAfterBingeShowCount =
            (UserPreferences.thanksAfterBingeShowCount + 1).coerceAtMost(MAX_SHOWS + 1)
    }

    fun dismiss(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.thanksAfterBingeLastDismissedAtMs = nowMs
    }

    fun openSupport(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.thanksAfterBingeLastShownAtMs = nowMs
        SupportPromptPolicy.markHubVisited(nowMs)
    }

    fun neverShowAgain() {
        UserPreferences.neverShowThanksAfterBinge = true
    }
}
