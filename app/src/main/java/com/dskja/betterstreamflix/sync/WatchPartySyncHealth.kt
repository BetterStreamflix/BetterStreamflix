package com.dskja.betterstreamflix.sync

import java.time.Instant
import kotlin.math.abs

/**
 * Sync-Health levels for Watch Together Pro (drift + host freshness).
 */
object WatchPartySyncHealth {

    enum class Level {
        GOOD,
        LAGGING,
        LOST,
        STALE,
        UNKNOWN,
    }

    data class Snapshot(
        val level: Level,
        val driftMs: Long,
        val hostAgeMs: Long?,
        val syncRevision: Long,
        val memberCount: Int,
        val maxReportedDriftMs: Long,
        val displayMode: String,
        val controlsLocked: Boolean,
    )

    fun levelFor(driftMs: Long, hostAgeMs: Long?): Level {
        if (hostAgeMs != null && hostAgeMs > WatchTogetherPro.hostStaleMs()) {
            return Level.STALE
        }
        val drift = driftMs.coerceAtLeast(0L)
        return when {
            drift <= WatchTogetherPro.HEALTH_GOOD_MS -> Level.GOOD
            drift <= WatchTogetherPro.healthLostMs() -> Level.LAGGING
            else -> Level.LOST
        }
    }

    fun driftBetween(localMs: Long, remoteMs: Long): Long =
        abs(localMs - remoteMs.coerceAtLeast(0L))

    fun hostAgeMs(updatedAt: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        if (updatedAt.isNullOrBlank()) return null
        return runCatching {
            val instant = Instant.parse(updatedAt)
            (nowMs - instant.toEpochMilli()).coerceAtLeast(0L)
        }.getOrNull()
    }

    fun fromPayload(
        localPositionMs: Long,
        payload: WatchPartyPayload,
        memberCount: Int = 0,
        maxReportedDriftMs: Long = 0L,
    ): Snapshot {
        val drift = driftBetween(localPositionMs, payload.positionMs)
        val age = hostAgeMs(payload.updatedAt)
        return Snapshot(
            level = levelFor(drift, age),
            driftMs = drift,
            hostAgeMs = age,
            syncRevision = payload.syncRevision,
            memberCount = memberCount,
            maxReportedDriftMs = maxReportedDriftMs.coerceAtLeast(drift),
            displayMode = WatchTogetherPro.normalizeDisplayMode(payload.displayMode),
            controlsLocked = payload.hostControlsLocked,
        )
    }

    fun fromHealthPayload(
        health: WatchPartySyncHealthPayload,
        localDriftMs: Long = 0L,
    ): Snapshot {
        val age = hostAgeMs(health.updatedAt)
        val maxDrift = maxOf(health.maxDriftMs, localDriftMs)
        return Snapshot(
            level = levelFor(maxDrift, age),
            driftMs = localDriftMs,
            hostAgeMs = age,
            syncRevision = health.syncRevision,
            memberCount = health.memberCount,
            maxReportedDriftMs = maxDrift,
            displayMode = WatchTogetherPro.normalizeDisplayMode(health.displayMode),
            controlsLocked = health.hostControlsLocked,
        )
    }
}
