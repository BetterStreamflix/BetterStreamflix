package com.dskja.betterstreamflix.kids

import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.UserPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Kids Bedtime — clock time → finish the current episode, then PIN lock.
 *
 * Window runs from the configured bedtime until [WAKE_MINUTE_OF_DAY] (05:00),
 * crossing midnight. A verified parental/profile PIN sets a bypass until wake.
 */
object KidsBedtime {

    /** Default 20:00 local. */
    const val DEFAULT_MINUTE_OF_DAY: Int = 20 * 60

    /** Morning release when the overnight lock window ends. */
    const val WAKE_MINUTE_OF_DAY: Int = 5 * 60

    const val MIN_MINUTE_OF_DAY: Int = 0
    const val MAX_MINUTE_OF_DAY: Int = 23 * 60 + 59

    /** Half-hour slots offered in Settings (17:00–23:00). */
    val SETTINGS_MINUTE_OPTIONS: IntArray =
        IntArray(13) { index -> (17 * 60) + index * 30 }

    fun clampMinuteOfDay(minuteOfDay: Int): Int =
        minuteOfDay.coerceIn(MIN_MINUTE_OF_DAY, MAX_MINUTE_OF_DAY)

    fun formatMinuteOfDay(minuteOfDay: Int, locale: Locale = Locale.getDefault()): String {
        val clamped = clampMinuteOfDay(minuteOfDay)
        return String.format(locale, "%02d:%02d", clamped / 60, clamped % 60)
    }

    /**
     * True when [minuteOfDayNow] falls in the bedtime→wake window.
     * Bedtime after wake (e.g. 20:00→05:00) crosses midnight.
     */
    fun isInWindow(
        minuteOfDayNow: Int,
        bedtimeMinute: Int,
        wakeMinute: Int = WAKE_MINUTE_OF_DAY,
    ): Boolean {
        val now = clampMinuteOfDay(minuteOfDayNow)
        val bed = clampMinuteOfDay(bedtimeMinute)
        val wake = clampMinuteOfDay(wakeMinute)
        if (bed == wake) return false
        return if (bed < wake) {
            now in bed until wake
        } else {
            now >= bed || now < wake
        }
    }

    fun minuteOfDay(
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val local = LocalTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        return local.hour * 60 + local.minute
    }

    /** Next wake instant at or after [nowMs] (today or tomorrow). */
    fun nextWakeEpochMs(
        nowMs: Long = System.currentTimeMillis(),
        wakeMinute: Int = WAKE_MINUTE_OF_DAY,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val wake = clampMinuteOfDay(wakeMinute)
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        var candidate = now.toLocalDate()
            .atTime(LocalTime.of(wake / 60, wake % 60))
            .atZone(zone)
        if (!candidate.toInstant().isAfter(now.toInstant())) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }

    fun applies(
        enabled: Boolean = UserPreferences.kidsBedtimeEnabled,
        isKids: Boolean = ProfileManager.isKidsActive(),
    ): Boolean = enabled && isKids

    fun isBypassActive(
        nowMs: Long = System.currentTimeMillis(),
        bypassUntilMs: Long = UserPreferences.kidsBedtimeBypassUntilMs,
    ): Boolean = bypassUntilMs > nowMs

    fun isActiveWindow(
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        enabled: Boolean = UserPreferences.kidsBedtimeEnabled,
        isKids: Boolean = ProfileManager.isKidsActive(),
        bedtimeMinute: Int = UserPreferences.kidsBedtimeMinuteOfDay,
        bypassUntilMs: Long = UserPreferences.kidsBedtimeBypassUntilMs,
    ): Boolean {
        if (!applies(enabled, isKids)) return false
        if (isBypassActive(nowMs, bypassUntilMs)) return false
        return isInWindow(minuteOfDay(nowMs, zone), bedtimeMinute)
    }

    fun isLocked(
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!applies()) return false
        if (isBypassActive(nowMs)) {
            if (UserPreferences.kidsBedtimeLocked) {
                UserPreferences.kidsBedtimeLocked = false
            }
            return false
        }
        if (!UserPreferences.kidsBedtimeLocked) return false
        // Auto-clear after wake.
        if (!isInWindow(minuteOfDay(nowMs, zone), UserPreferences.kidsBedtimeMinuteOfDay)) {
            UserPreferences.kidsBedtimeLocked = false
            return false
        }
        return true
    }

    /** Finish current title, then require PIN. */
    fun engageLock() {
        if (!applies()) return
        UserPreferences.kidsBedtimeLocked = true
        UserPreferences.kidsBedtimeBypassUntilMs = 0L
    }

    /** Parental/profile PIN succeeded — watch until wake. */
    fun unlockUntilWake(
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        UserPreferences.kidsBedtimeLocked = false
        UserPreferences.kidsBedtimeBypassUntilMs = nextWakeEpochMs(nowMs, zone = zone)
    }

    fun shouldStopAfterEpisode(nowMs: Long = System.currentTimeMillis()): Boolean =
        isLocked(nowMs) || isActiveWindow(nowMs)

    fun shouldBlockAutoplay(nowMs: Long = System.currentTimeMillis()): Boolean =
        shouldStopAfterEpisode(nowMs)

    /** Block opening the player for a new title once bedtime applies. */
    fun shouldBlockEnteringPlayer(nowMs: Long = System.currentTimeMillis()): Boolean =
        isLocked(nowMs) || isActiveWindow(nowMs)

    fun shouldStopLiveNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        shouldStopAfterEpisode(nowMs)

    fun settingsSummary(
        enabled: Boolean = UserPreferences.kidsBedtimeEnabled,
        minuteOfDay: Int = UserPreferences.kidsBedtimeMinuteOfDay,
        locked: Boolean = isLocked(),
    ): String {
        if (!enabled) return ""
        val time = formatMinuteOfDay(minuteOfDay)
        return if (locked) "🔒 $time" else time
    }

    fun dateKey(
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone).toString()
}
