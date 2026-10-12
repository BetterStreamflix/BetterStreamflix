package com.dskja.betterstreamflix.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LiveReminderPolicyTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    @Test
    fun parseKickoff24hFromTitle() {
        val noon = LocalDate.of(2026, 10, 11).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val kickoff = LiveReminderPolicy.parseKickoffEpochMs(
            title = "Man Utd vs Chelsea 20:45",
            nowMs = noon,
            zone = zone,
        )
        val expected = LocalDate.of(2026, 10, 11).atTime(20, 45).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, kickoff)
    }

    @Test
    fun parseKickoff12hPm() {
        val noon = LocalDate.of(2026, 10, 11).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val kickoff = LiveReminderPolicy.parseKickoffEpochMs(
            title = "Lakers vs Celtics 7:30 PM",
            nowMs = noon,
            zone = zone,
        )
        val expected = LocalDate.of(2026, 10, 11).atTime(19, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, kickoff)
    }

    @Test
    fun parseKickoffNullWithoutClock() {
        assertNull(
            LiveReminderPolicy.parseKickoffEpochMs(
                title = "ESPN USA HD",
                nowMs = System.currentTimeMillis(),
                zone = zone,
            ),
        )
    }

    @Test
    fun parseKickoffRejectsBareLowHourScore() {
        val noon = LocalDate.of(2026, 10, 11).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertNull(
            LiveReminderPolicy.parseKickoffEpochMs(
                title = "Highlight reel 1:05",
                nowMs = noon,
                zone = zone,
            ),
        )
    }

    @Test
    fun parseKickoffAcceptsLowHourWithSportContext() {
        val early = LocalDate.of(2026, 10, 11).atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
        val kickoff = LiveReminderPolicy.parseKickoffEpochMs(
            title = "Early kickoff derby 5:30",
            nowMs = early,
            zone = zone,
        )
        val expected = LocalDate.of(2026, 10, 11).atTime(5, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, kickoff)
    }

    @Test
    fun decideSchedulesWhenKickoffFarAway() {
        val now = 1_000_000L
        val kickoff = now + 30L * 60L * 1000L
        val d = LiveReminderPolicy.decide(
            nowMs = now,
            kickoffEpochMs = kickoff,
            newlyLive = false,
            gate = LiveReminderPolicy.Gate(),
        )
        assertTrue(d is LiveReminderPolicy.Decision.ScheduleAt)
        assertEquals(
            kickoff - LiveReminderPolicy.LEAD_BEFORE_KICKOFF_MS,
            (d as LiveReminderPolicy.Decision.ScheduleAt).fireAtMs,
        )
    }

    @Test
    fun decideNotifiesInsideFiveMinuteLead() {
        val kickoff = 10_000_000L
        val now = kickoff - 3L * 60L * 1000L
        val d = LiveReminderPolicy.decide(
            nowMs = now,
            kickoffEpochMs = kickoff,
            newlyLive = false,
            gate = LiveReminderPolicy.Gate(),
        )
        assertEquals(
            LiveReminderPolicy.Reason.PRE_KICKOFF_WINDOW,
            (d as LiveReminderPolicy.Decision.NotifyNow).reason,
        )
    }

    @Test
    fun decideSkipsWhenSnoozed() {
        val now = 5_000_000L
        val d = LiveReminderPolicy.decide(
            nowMs = now,
            kickoffEpochMs = now + 60_000L,
            newlyLive = true,
            gate = LiveReminderPolicy.Gate(snoozedUntilMs = now + 60_000L),
        )
        assertEquals(LiveReminderPolicy.Decision.Skip, d)
    }

    @Test
    fun decideSkipsHourlyFlood() {
        val now = 8_000_000L
        val d = LiveReminderPolicy.decide(
            nowMs = now,
            kickoffEpochMs = null,
            newlyLive = true,
            gate = LiveReminderPolicy.Gate(
                recentFireTimesMs = listOf(now - 10_000L, now - 20_000L),
            ),
        )
        assertEquals(LiveReminderPolicy.Decision.Skip, d)
    }

    @Test
    fun decideNewlyLiveWithoutKickoff() {
        val d = LiveReminderPolicy.decide(
            nowMs = 1L,
            kickoffEpochMs = null,
            newlyLive = true,
            gate = LiveReminderPolicy.Gate(),
        )
        assertEquals(
            LiveReminderPolicy.Reason.NEWLY_LIVE,
            (d as LiveReminderPolicy.Decision.NotifyNow).reason,
        )
    }

    @Test
    fun decidePastKickoffFallsThroughToNewlyLive() {
        val kickoff = 10_000_000L
        val now = kickoff + 5L * 60L * 1000L
        val d = LiveReminderPolicy.decide(
            nowMs = now,
            kickoffEpochMs = kickoff,
            newlyLive = true,
            gate = LiveReminderPolicy.Gate(),
        )
        assertEquals(
            LiveReminderPolicy.Reason.NEWLY_LIVE,
            (d as LiveReminderPolicy.Decision.NotifyNow).reason,
        )
    }

    @Test
    fun decidePastKickoffSkipsWhenNotNewlyLive() {
        val kickoff = 10_000_000L
        val now = kickoff + 5L * 60L * 1000L
        val d = LiveReminderPolicy.decide(
            nowMs = now,
            kickoffEpochMs = kickoff,
            newlyLive = false,
            gate = LiveReminderPolicy.Gate(),
        )
        assertEquals(LiveReminderPolicy.Decision.Skip, d)
    }

    @Test
    fun decideSkipsAlreadyNotifiedSameKickoff() {
        val now = 1_000_000L
        val d = LiveReminderPolicy.decide(
            nowMs = now,
            kickoffEpochMs = now + 60_000L,
            newlyLive = false,
            gate = LiveReminderPolicy.Gate(alreadyNotifiedThisKickoff = true),
        )
        assertEquals(LiveReminderPolicy.Decision.Skip, d)
    }

    @Test
    fun snoozeIsFifteenMinutes() {
        assertEquals(15L * 60L * 1000L, LiveReminderPolicy.snoozeUntil(0L))
    }

    @Test
    fun pastKickoffRollsToTomorrow() {
        val late = LocalDate.of(2026, 10, 11).atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val kickoff = LiveReminderPolicy.parseKickoffEpochMs(
            title = "Match 18:00",
            nowMs = late,
            zone = zone,
        )
        val expected = LocalDate.of(2026, 10, 12).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, kickoff)
    }

    @Test
    fun reconstructKickoffFromFireAtMatchesLead() {
        val kickoff = 20_000_000L
        val fireAt = kickoff - LiveReminderPolicy.LEAD_BEFORE_KICKOFF_MS
        assertEquals(kickoff, fireAt + LiveReminderPolicy.LEAD_BEFORE_KICKOFF_MS)
        val now = fireAt + 1_000L
        val d = LiveReminderPolicy.decide(
            nowMs = now,
            kickoffEpochMs = fireAt + LiveReminderPolicy.LEAD_BEFORE_KICKOFF_MS,
            newlyLive = false,
            gate = LiveReminderPolicy.Gate(),
        )
        assertEquals(
            LiveReminderPolicy.Reason.PRE_KICKOFF_WINDOW,
            (d as LiveReminderPolicy.Decision.NotifyNow).reason,
        )
    }
}
