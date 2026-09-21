package com.dskja.betterstreamflix.telegram

import com.dskja.betterstreamflix.support.SupportUrls
import com.dskja.betterstreamflix.utils.UserPreferences
import java.util.concurrent.TimeUnit

/**
 * Soft Telegram join-gate policy (mobile only).
 *
 * Flow: LOCKED → OPENED (user launched Telegram) → UNLOCKED (user confirmed).
 * Unlock is permanent for the current [CHANNEL_VERSION]. Bumping the version
 * re-locks everyone so a channel migration can re-prompt.
 */
object TelegramJoinGatePolicy {

    const val CHANNEL_HANDLE = "BetterStreamflix"
    const val CHANNEL_VERSION = 1
    const val WEB_URL = SupportUrls.TELEGRAM_URL
    const val APP_URL = SupportUrls.TELEGRAM_APP_URL

    /** Minimum time the user must have "opened" Telegram before confirm is allowed. */
    val MIN_OPEN_DWELL_MS: Long = TimeUnit.SECONDS.toMillis(2)

    enum class Phase {
        LOCKED,
        OPENED,
        UNLOCKED,
    }

    fun phase(nowMs: Long = System.currentTimeMillis()): Phase {
        if (isUnlocked()) return Phase.UNLOCKED
        if (hasFreshOpen(nowMs)) return Phase.OPENED
        return Phase.LOCKED
    }

    fun shouldShowGate(): Boolean = !isUnlocked()

    fun isUnlocked(): Boolean {
        if (!UserPreferences.telegramJoinGateUnlocked) return false
        return UserPreferences.telegramJoinGateChannelVersion >= CHANNEL_VERSION
    }

    fun hasFreshOpen(nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = UserPreferences.telegramJoinGateOpenedAtMs
        if (at <= 0L) return false
        // Open remains valid for the rest of this install session window (7 days).
        return nowMs - at <= TimeUnit.DAYS.toMillis(7)
    }

    fun canConfirm(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (isUnlocked()) return true
        val at = UserPreferences.telegramJoinGateOpenedAtMs
        if (at <= 0L) return false
        return nowMs - at >= MIN_OPEN_DWELL_MS
    }

    fun markOpened(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.telegramJoinGateOpenedAtMs = nowMs
        UserPreferences.telegramJoinGateOpenCount =
            (UserPreferences.telegramJoinGateOpenCount + 1).coerceAtMost(10_000)
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

    /** DEBUG / tests only — clears unlock so the gate shows again. */
    fun resetForDebug() {
        UserPreferences.telegramJoinGateUnlocked = false
        UserPreferences.telegramJoinGateUnlockedAtMs = 0L
        UserPreferences.telegramJoinGateOpenedAtMs = 0L
        UserPreferences.telegramJoinGateChannelVersion = 0
    }
}
