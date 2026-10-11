package com.dskja.betterstreamflix.support

import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.UserPreferences
import java.util.concurrent.TimeUnit

/**
 * Post–season-finale Support nudge (“Thanks After Binge”).
 *
 * Rules:
 * - Only after a season finale (caller-gated), never mid-season.
 * - Kids profiles never see it.
 * - Global Support “never again” opts out.
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
     */
    fun isSeasonFinale(currentSeasonNumber: Int?, nextSeasonNumber: Int?): Boolean {
        if (currentSeasonNumber == null) return false
        if (nextSeasonNumber == null) return true
        return nextSeasonNumber != currentSeasonNumber
    }

    fun shouldShow(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (ProfileManager.isKidsActive()) return false
        if (UserPreferences.neverShowThanksAfterBinge) return false
        if (UserPreferences.neverShowSupportOnStart) return false
        if (UserPreferences.thanksAfterBingeShowCount >= MAX_SHOWS) return false
        val last = maxOf(
            UserPreferences.thanksAfterBingeLastShownAtMs,
            UserPreferences.thanksAfterBingeLastDismissedAtMs,
        )
        if (last <= 0L) return true
        return nowMs - last >= COOLDOWN_MS
    }

    /** Panel became visible — start the long cool-down immediately (anti-spam). */
    fun recordShown(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.thanksAfterBingeLastShownAtMs = nowMs
        UserPreferences.thanksAfterBingeShowCount =
            (UserPreferences.thanksAfterBingeShowCount + 1).coerceAtMost(MAX_SHOWS + 1)
    }

    fun dismiss(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.thanksAfterBingeLastDismissedAtMs = nowMs
        if (UserPreferences.thanksAfterBingeLastShownAtMs <= 0L) {
            recordShown(nowMs)
        }
    }

    fun openSupport(nowMs: Long = System.currentTimeMillis()) {
        if (UserPreferences.thanksAfterBingeLastShownAtMs <= 0L) {
            recordShown(nowMs)
        } else {
            UserPreferences.thanksAfterBingeLastShownAtMs = nowMs
        }
        SupportPromptPolicy.markHubVisited(nowMs)
    }

    fun neverShowAgain() {
        UserPreferences.neverShowThanksAfterBinge = true
    }
}
