package com.dskja.betterstreamflix.notifications

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.regex.Pattern

/**
 * Fine-grained Live Hub favorite reminders: 5 minutes before kickoff when a
 * clock time is parseable, otherwise one “newly live” ping. Favorites-only
 * callers supply the candidate set; this object never expands beyond that.
 */
object LiveReminderPolicy {

    const val LEAD_BEFORE_KICKOFF_MS = 5L * 60L * 1000L
    const val SNOOZE_MS = 15L * 60L * 1000L
    const val PER_KEY_COOLDOWN_MS = 3L * 60L * 60L * 1000L
    const val MAX_LIVE_PER_RUN = 1
    const val MAX_LIVE_PER_HOUR = 2
    private const val HOUR_MS = 60L * 60L * 1000L
    /** Bare 24h times earlier than this need sport context (avoids score-like 1:05). */
    private const val MIN_BARE_24H_HOUR = 6

    sealed class Decision {
        data object Skip : Decision()
        data class NotifyNow(val reason: Reason) : Decision()
        data class ScheduleAt(val fireAtMs: Long) : Decision()
    }

    enum class Reason { PRE_KICKOFF_WINDOW, NEWLY_LIVE }

    data class Gate(
        val snoozedUntilMs: Long = 0L,
        val lastNotifiedMs: Long = 0L,
        val recentFireTimesMs: List<Long> = emptyList(),
        val alreadyNotifiedThisKickoff: Boolean = false,
    )

    /**
     * @param kickoffEpochMs Parsed kickoff, or null when the catalog has no clock time.
     * @param newlyLive True when this favorite just entered the live set vs baseline.
     */
    fun decide(
        nowMs: Long,
        kickoffEpochMs: Long?,
        newlyLive: Boolean,
        gate: Gate,
    ): Decision {
        if (gate.snoozedUntilMs > nowMs) return Decision.Skip
        if (gate.lastNotifiedMs > 0L && nowMs - gate.lastNotifiedMs < PER_KEY_COOLDOWN_MS) {
            return Decision.Skip
        }
        if (gate.alreadyNotifiedThisKickoff) return Decision.Skip
        val recent = gate.recentFireTimesMs.count { nowMs - it < HOUR_MS }
        if (recent >= MAX_LIVE_PER_HOUR) return Decision.Skip

        if (kickoffEpochMs != null) {
            val fireAt = kickoffEpochMs - LEAD_BEFORE_KICKOFF_MS
            when {
                nowMs < fireAt -> return Decision.ScheduleAt(fireAt)
                // Inside the 5-minute lead (or up to ~2 min after kickoff).
                nowMs <= kickoffEpochMs + 2L * 60L * 1000L ->
                    return Decision.NotifyNow(Reason.PRE_KICKOFF_WINDOW)
                // Past the pre-kickoff window: fall through to newly-live.
            }
        }

        // No clock, or kickoff already more than ~2 min past: ping once when newly live.
        return if (newlyLive) Decision.NotifyNow(Reason.NEWLY_LIVE) else Decision.Skip
    }

    fun snoozeUntil(nowMs: Long): Long = nowMs + SNOOZE_MS

    fun pruneRecentFires(recent: List<Long>, nowMs: Long): List<Long> =
        recent.filter { nowMs - it < HOUR_MS }.takeLast(MAX_LIVE_PER_HOUR + 2)

    /**
     * Best-effort kickoff from title / overview (“20:45”, “8:30 PM”).
     * Uses [zone] local calendar day; if the time is already >2h past, rolls to tomorrow.
     */
    fun parseKickoffEpochMs(
        title: String?,
        overview: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val blob = listOfNotNull(title, overview).joinToString(" ")
        if (blob.isBlank()) return null
        val time = parseLocalTime(blob) ?: return null
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
        var candidate = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
        if (candidate < nowMs - 2L * 60L * 60L * 1000L) {
            candidate = today.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
        }
        // Ignore kickoffs more than 36h out (stale catalog noise).
        if (candidate - nowMs > 36L * 60L * 60L * 1000L) return null
        return candidate
    }

    private fun parseLocalTime(blob: String): LocalTime? {
        val m12 = TIME_12.matcher(blob)
        if (m12.find()) {
            var hour = m12.group(1).toInt()
            val minute = m12.group(2).toInt()
            val ampm = m12.group(3).lowercase(Locale.US)
            if (ampm.startsWith("p") && hour < 12) hour += 12
            if (ampm.startsWith("a") && hour == 12) hour = 0
            return runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }
        val m24 = TIME_24.matcher(blob)
        if (m24.find()) {
            val hour = m24.group(1).toInt()
            val minute = m24.group(2).toInt()
            if (hour !in 0..23) return null
            // Low hours look like scores (1:05) unless the title has sport context.
            if (hour < MIN_BARE_24H_HOUR && !hasSportContext(blob)) return null
            return runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }
        return null
    }

    private fun hasSportContext(blob: String): Boolean {
        val lower = blob.lowercase(Locale.US)
        return SPORT_HINTS.any { lower.contains(it) }
    }

    private val SPORT_HINTS = listOf(
        " vs ", " vs.", " vs-", "v.", " match", "match ", "kickoff", "kick-off",
        "sport", "liga", "league", "cup ", " final", "fixture", "derby",
        "fc ", " united", "game ",
    )

    private val TIME_12: Pattern =
        Pattern.compile("""\b(1[0-2]|0?[1-9])[:.]([0-5]\d)\s*([AaPp][Mm])\b""")
    /** HH:MM at start or after whitespace; optional leading `h` (e.g. h20:45). */
    private val TIME_24: Pattern =
        Pattern.compile("""(?:^|\s)h?([01]?\d|2[0-3])[:.]([0-5]\d)\b""")
}
