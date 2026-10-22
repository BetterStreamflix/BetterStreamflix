package com.betterstreamflix.app.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class KidsBedtimeTest {

    @Test
    fun isInWindow_eveningToMorning_crossesMidnight() {
        // 20:00 → 05:00
        assertFalse(KidsBedtime.isInWindow(19 * 60 + 59, 20 * 60))
        assertTrue(KidsBedtime.isInWindow(20 * 60, 20 * 60))
        assertTrue(KidsBedtime.isInWindow(23 * 60 + 30, 20 * 60))
        assertTrue(KidsBedtime.isInWindow(0, 20 * 60))
        assertTrue(KidsBedtime.isInWindow(4 * 60 + 59, 20 * 60))
        assertFalse(KidsBedtime.isInWindow(5 * 60, 20 * 60))
        assertFalse(KidsBedtime.isInWindow(12 * 60, 20 * 60))
    }

    @Test
    fun isInWindow_sameBedAndWake_neverActive() {
        assertFalse(KidsBedtime.isInWindow(20 * 60, 20 * 60, wakeMinute = 20 * 60))
    }

    @Test
    fun formatMinuteOfDay_padsHoursAndMinutes() {
        assertEquals("20:00", KidsBedtime.formatMinuteOfDay(20 * 60))
        assertEquals("09:05", KidsBedtime.formatMinuteOfDay(9 * 60 + 5))
        assertEquals("00:00", KidsBedtime.formatMinuteOfDay(0))
    }

    @Test
    fun clampMinuteOfDay_bounds() {
        assertEquals(0, KidsBedtime.clampMinuteOfDay(-10))
        assertEquals(23 * 60 + 59, KidsBedtime.clampMinuteOfDay(99_999))
        assertEquals(20 * 60 + 15, KidsBedtime.clampMinuteOfDay(20 * 60 + 15))
    }

    @Test
    fun nextWakeEpochMs_picksUpcomingWake() {
        // 2026-10-11 21:00 UTC → wake 05:00 next day
        val evening = java.time.LocalDate.of(2026, 10, 11)
            .atTime(21, 0)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        val wake = KidsBedtime.nextWakeEpochMs(
            nowMs = evening,
            wakeMinute = 5 * 60,
            zone = ZoneOffset.UTC,
        )
        val wakeLocal = java.time.Instant.ofEpochMilli(wake).atZone(ZoneOffset.UTC)
        assertEquals(12, wakeLocal.dayOfMonth)
        assertEquals(5, wakeLocal.hour)
        assertEquals(0, wakeLocal.minute)
    }

    @Test
    fun nextWakeEpochMs_beforeWakeSameDay() {
        val early = java.time.LocalDate.of(2026, 10, 11)
            .atTime(3, 0)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        val wake = KidsBedtime.nextWakeEpochMs(
            nowMs = early,
            wakeMinute = 5 * 60,
            zone = ZoneOffset.UTC,
        )
        val wakeLocal = java.time.Instant.ofEpochMilli(wake).atZone(ZoneOffset.UTC)
        assertEquals(11, wakeLocal.dayOfMonth)
        assertEquals(5, wakeLocal.hour)
    }

    @Test
    fun settingsMinuteOptions_coverFullDayHalfHours() {
        assertEquals(0, KidsBedtime.SETTINGS_MINUTE_OPTIONS.first())
        assertEquals(23 * 60 + 30, KidsBedtime.SETTINGS_MINUTE_OPTIONS.last())
        assertEquals(48, KidsBedtime.SETTINGS_MINUTE_OPTIONS.size)
    }

    @Test
    fun applies_requiresKidsOrGuestAndEnabled() {
        assertFalse(KidsBedtime.applies(enabled = true, isKids = false, isGuest = false))
        assertFalse(KidsBedtime.applies(enabled = false, isKids = true, isGuest = false))
        assertTrue(KidsBedtime.applies(enabled = true, isKids = true, isGuest = false))
        assertTrue(KidsBedtime.applies(enabled = true, isKids = false, isGuest = true))
    }

    @Test
    fun isLockedState_outsideWindowIsFalse() {
        val noon = java.time.LocalDate.of(2026, 10, 11)
            .atTime(12, 0)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        assertFalse(
            KidsBedtime.isLockedState(
                nowMs = noon,
                zone = ZoneOffset.UTC,
                enabled = true,
                isKids = true,
                isGuest = false,
                lockedFlag = true,
                bedtimeMinute = 20 * 60,
                wakeMinute = 5 * 60,
            ),
        )
    }

    @Test
    fun isLockedState_inWindowWithFlagIsTrue() {
        val evening = java.time.LocalDate.of(2026, 10, 11)
            .atTime(21, 0)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
        assertTrue(
            KidsBedtime.isLockedState(
                nowMs = evening,
                zone = ZoneOffset.UTC,
                enabled = true,
                isKids = true,
                isGuest = false,
                lockedFlag = true,
                bedtimeMinute = 20 * 60,
                wakeMinute = 5 * 60,
            ),
        )
        assertFalse(
            KidsBedtime.isLockedState(
                nowMs = evening,
                zone = ZoneOffset.UTC,
                enabled = true,
                isKids = true,
                isGuest = false,
                lockedFlag = true,
                bedtimeMinute = 20 * 60,
                wakeMinute = 5 * 60,
                bypassUntilMs = evening + 60_000L,
            ),
        )
    }

    @Test
    fun isInWindow_respectsCustomWake() {
        // 22:00 → 06:00
        assertTrue(KidsBedtime.isInWindow(23 * 60, 22 * 60, wakeMinute = 6 * 60))
        assertFalse(KidsBedtime.isInWindow(6 * 60, 22 * 60, wakeMinute = 6 * 60))
        assertTrue(KidsBedtime.isInWindow(5 * 60, 22 * 60, wakeMinute = 6 * 60))
    }

    @Test
    fun isActiveWindow_respectsBypass() {
        val now = 1_000_000L
        assertTrue(
            KidsBedtime.isActiveWindow(
                nowMs = now,
                zone = ZoneOffset.UTC,
                enabled = true,
                isKids = true,
                isGuest = false,
                bedtimeMinute = KidsBedtime.minuteOfDay(now, ZoneOffset.UTC),
                wakeMinute = KidsBedtime.WAKE_MINUTE_OF_DAY,
                bypassUntilMs = 0L,
            ),
        )
        assertFalse(
            KidsBedtime.isActiveWindow(
                nowMs = now,
                zone = ZoneOffset.UTC,
                enabled = true,
                isKids = true,
                isGuest = false,
                bedtimeMinute = KidsBedtime.minuteOfDay(now, ZoneOffset.UTC),
                wakeMinute = KidsBedtime.WAKE_MINUTE_OF_DAY,
                bypassUntilMs = now + 60_000L,
            ),
        )
    }

    /** KB-011: disabling bedtime must clear lock + bypass (prefs side-effects). */
    @Test
    fun prefsAfterSetEnabled_disablingClearsLockAndBypass() {
        val cleared = KidsBedtime.prefsAfterSetEnabled(
            enabled = false,
            locked = true,
            bypassUntilMs = 9_999_999L,
        )
        assertFalse(cleared.locked)
        assertEquals(0L, cleared.bypassUntilMs)
    }

    @Test
    fun prefsAfterSetEnabled_enablingPreservesLockState() {
        val kept = KidsBedtime.prefsAfterSetEnabled(
            enabled = true,
            locked = true,
            bypassUntilMs = 42L,
        )
        assertTrue(kept.locked)
        assertEquals(42L, kept.bypassUntilMs)
    }

    @Test
    fun settingsSummary_lockedUsesPlainTextNoEmoji() {
        val summary = KidsBedtime.settingsSummary(
            enabled = true,
            minuteOfDay = 20 * 60,
            locked = true,
        )
        assertEquals("Locked · 20:00", summary)
        assertFalse(summary.contains("🔒"))
    }
}
