package com.dskja.betterstreamflix.telegram

import com.dskja.betterstreamflix.support.SupportUrls
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Soft community invite (mobile only) — dismissible, never a hard lock.
 *
 * Shown once per [CHANNEL_VERSION] until the user dismisses / continues.
 * Joining Telegram or Discord is optional — Close always works.
 *
 * Preference keys retain historical `telegramJoinGate*` names for migration;
 * [Phase] names describe invite state, not an access gate.
 */
object TelegramJoinGatePolicy {

    const val CHANNEL_HANDLE = "BetterStreamflix"
    /**
     * Bump to re-prompt installs that previously dismissed an older invite.
     * v7: optional community invite (Telegram + Discord), dismissible, live metadata.
     */
    const val CHANNEL_VERSION = 7
    const val WEB_URL = SupportUrls.TELEGRAM_URL
    const val APP_URL = SupportUrls.TELEGRAM_APP_URL
    const val DISCORD_URL = SupportUrls.DISCORD_URL

    /** Kept for older tests / debug — invite is no longer a multi-step gate. */
    @Deprecated("Invite is dismissible; no dwell required")
    val MIN_OPEN_DWELL_MS: Long = 0L

    /**
     * Soft invite lifecycle.
     * - [INVITE_PENDING]: invite should be shown
     * - [INVITE_OPENED]: user opened a community link (optional bookkeeping)
     * - [DISMISSED]: user continued / closed without being forced to join
     */
    enum class Phase {
        INVITE_PENDING,
        INVITE_OPENED,
        DISMISSED,
    }

    fun phase(): Phase = if (isUnlocked()) Phase.DISMISSED else Phase.INVITE_PENDING

    fun shouldShowGate(): Boolean = !isUnlocked()

    fun isUnlocked(): Boolean {
        if (!UserPreferences.telegramJoinGateUnlocked) return false
        return UserPreferences.telegramJoinGateChannelVersion >= CHANNEL_VERSION
    }

    fun hasFreshOpen(nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = UserPreferences.telegramJoinGateOpenedAtMs
        if (at <= 0L) return false
        return nowMs - at <= java.util.concurrent.TimeUnit.DAYS.toMillis(7)
    }

    fun canConfirm(nowMs: Long = System.currentTimeMillis()): Boolean = true

    fun markOpened(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.telegramJoinGateOpenedAtMs = nowMs
        UserPreferences.telegramJoinGateOpenCount =
            (UserPreferences.telegramJoinGateOpenCount + 1).coerceAtMost(10_000)
    }

    /** User closed the invite without being forced to join. */
    fun markDismissed(nowMs: Long = System.currentTimeMillis()) {
        markUnlocked(nowMs)
    }

    fun markUnlocked(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.telegramJoinGateUnlocked = true
        UserPreferences.telegramJoinGateUnlockedAtMs = nowMs
        UserPreferences.telegramJoinGateChannelVersion = CHANNEL_VERSION
        UserPreferences.telegramJoinGateConfirmCount =
            (UserPreferences.telegramJoinGateConfirmCount + 1).coerceAtMost(10_000)
        if (UserPreferences.telegramJoinGateOpenedAtMs <= 0L) {
            UserPreferences.telegramJoinGateOpenedAtMs = nowMs
        }
    }

    /** DEBUG / tests only — clears dismiss so the invite shows again. */
    fun resetForDebug() {
        UserPreferences.telegramJoinGateUnlocked = false
        UserPreferences.telegramJoinGateUnlockedAtMs = 0L
        UserPreferences.telegramJoinGateOpenedAtMs = 0L
        UserPreferences.telegramJoinGateChannelVersion = 0
    }
}
