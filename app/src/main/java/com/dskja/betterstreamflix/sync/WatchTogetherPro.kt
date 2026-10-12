package com.dskja.betterstreamflix.sync

import android.content.Context
import com.dskja.betterstreamflix.ui.StickEnergyMode

/**
 * Watch Together Pro — session product on Watch Party:
 * lobby + join code, host controls, sync-health, TV-screen / phone-remote.
 * Reuses [TvSidekick] seek/chat sanitizers; no stream relay.
 */
object WatchTogetherPro {

    const val MODE_SYNC_ALL: String = "sync_all"
    const val MODE_TV_SCREEN: String = "tv_screen"

    const val SEEK_STEP_SHORT_MS: Long = TvSidekick.SEEK_STEP_SHORT_MS
    const val SEEK_STEP_LONG_MS: Long = TvSidekick.SEEK_STEP_LONG_MS

    /** Drift under this is “in sync”. */
    const val HEALTH_GOOD_MS: Long = WatchPartySync.SEEK_THRESHOLD_MS

    /** Drift above this is “lost” until force resync. */
    const val HEALTH_LOST_MS: Long = 8_000L

    /** Host heartbeat older than this → stale. */
    const val HOST_STALE_MS: Long = 8_000L

    /** Default host poll while party is live. */
    const val HOST_POLL_MS: Long = 2_500L

    /** Stick Energy: wider resync thresholds + slower poll (SE-004). */
    const val ENERGY_HEALTH_LOST_MS: Long = 12_000L
    const val ENERGY_HOST_STALE_MS: Long = 12_000L
    const val ENERGY_HOST_POLL_MS: Long = 4_000L

    fun isValidDisplayMode(mode: String?): Boolean =
        mode == MODE_SYNC_ALL || mode == MODE_TV_SCREEN

    fun normalizeDisplayMode(mode: String?): String =
        if (mode == MODE_TV_SCREEN) MODE_TV_SCREEN else MODE_SYNC_ALL

    fun isTvScreenMode(mode: String?): Boolean =
        normalizeDisplayMode(mode) == MODE_TV_SCREEN

    fun healthLostMs(context: Context? = null): Long =
        if (StickEnergyMode.isEnabled(context)) ENERGY_HEALTH_LOST_MS else HEALTH_LOST_MS

    fun hostStaleMs(context: Context? = null): Long =
        if (StickEnergyMode.isEnabled(context)) ENERGY_HOST_STALE_MS else HOST_STALE_MS

    fun hostPollMs(context: Context? = null): Long =
        if (StickEnergyMode.isEnabled(context)) ENERGY_HOST_POLL_MS else HOST_POLL_MS

    /** Skip heavy TV-screen chrome motion under Stick Energy. */
    fun shouldReducePartyChromeMotion(context: Context? = null): Boolean =
        StickEnergyMode.isEnabled(context)

    fun clampSeek(positionMs: Long, durationMs: Long? = null): Long =
        TvSidekick.clampSeek(positionMs, durationMs)

    fun relativeSeek(currentMs: Long, deltaMs: Long, durationMs: Long? = null): Long =
        TvSidekick.relativeSeek(currentMs, deltaMs, durationMs)

    fun parsePositionInput(raw: String?, durationMs: Long? = null): Long? =
        TvSidekick.parsePositionInput(raw, durationMs)

    /** Skip host heartbeat position overwrite while a sidekick seek is unacked. */
    fun shouldSkipHeartbeatForSidekick(seenSeekSeq: Long, appliedSeekSeq: Long): Boolean =
        seenSeekSeq > appliedSeekSeq

    /** Debounce guest drift reports (settle after seek + min interval). */
    const val DRIFT_REPORT_MIN_INTERVAL_MS: Long = 5_000L
    const val DRIFT_REPORT_SETTLE_MS: Long = 2_500L

    fun shouldReportDrift(
        nowMs: Long,
        lastReportAtMs: Long,
        lastSeekAtMs: Long,
        driftMs: Long,
        lastReportedDriftMs: Long,
    ): Boolean {
        if (nowMs - lastSeekAtMs < DRIFT_REPORT_SETTLE_MS) return false
        if (nowMs - lastReportAtMs < DRIFT_REPORT_MIN_INTERVAL_MS &&
            kotlin.math.abs(driftMs - lastReportedDriftMs) < HEALTH_GOOD_MS
        ) {
            return false
        }
        return true
    }

    fun formatPosition(positionMs: Long): String =
        TvSidekick.formatPosition(positionMs)

    fun shouldApplySeek(payloadSeq: Long, lastAppliedSeq: Long, seekMs: Long?): Boolean =
        TvSidekick.shouldApplySeek(payloadSeq, lastAppliedSeq, seekMs)

    fun shouldApplyPlay(payloadSeq: Long, lastAppliedSeq: Long, playing: Boolean?): Boolean =
        playing != null && payloadSeq > lastAppliedSeq

    fun shouldForceResync(payloadRevision: Long, lastAppliedRevision: Long): Boolean =
        payloadRevision > lastAppliedRevision
}
