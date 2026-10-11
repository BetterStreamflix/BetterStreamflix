package com.dskja.betterstreamflix.kids

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
    fun settingsMinuteOptions_coverEveningHalfHours() {
        assertEquals(17 * 60, KidsBedtime.SETTINGS_MINUTE_OPTIONS.first())
        assertEquals(23 * 60, KidsBedtime.SETTINGS_MINUTE_OPTIONS.last())
        assertEquals(13, KidsBedtime.SETTINGS_MINUTE_OPTIONS.size)
    }

    @Test
    fun applies_requiresKidsAndEnabled() {
        assertFalse(KidsBedtime.applies(enabled = true, isKids = false))
        assertFalse(KidsBedtime.applies(enabled = false, isKids = true))
        assertTrue(KidsBedtime.applies(enabled = true, isKids = true))
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
                bedtimeMinute = KidsBedtime.minuteOfDay(now, ZoneOffset.UTC),
                bypassUntilMs = 0L,
            ),
        )
        assertFalse(
            KidsBedtime.isActiveWindow(
                nowMs = now,
                zone = ZoneOffset.UTC,
                enabled = true,
                isKids = true,
                bedtimeMinute = KidsBedtime.minuteOfDay(now, ZoneOffset.UTC),
                bypassUntilMs = now + 60_000L,
            ),
        )
    }
}
