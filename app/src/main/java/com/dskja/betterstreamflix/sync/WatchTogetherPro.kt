package com.dskja.betterstreamflix.sync

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

    fun isValidDisplayMode(mode: String?): Boolean =
        mode == MODE_SYNC_ALL || mode == MODE_TV_SCREEN

    fun normalizeDisplayMode(mode: String?): String =
        if (mode == MODE_TV_SCREEN) MODE_TV_SCREEN else MODE_SYNC_ALL

    fun isTvScreenMode(mode: String?): Boolean =
        normalizeDisplayMode(mode) == MODE_TV_SCREEN

    fun clampSeek(positionMs: Long, durationMs: Long? = null): Long =
        TvSidekick.clampSeek(positionMs, durationMs)

    fun relativeSeek(currentMs: Long, deltaMs: Long, durationMs: Long? = null): Long =
        TvSidekick.relativeSeek(currentMs, deltaMs, durationMs)

    fun parsePositionInput(raw: String?): Long? =
        TvSidekick.parsePositionInput(raw)

    fun formatPosition(positionMs: Long): String =
        TvSidekick.formatPosition(positionMs)

    fun shouldApplySeek(payloadSeq: Long, lastAppliedSeq: Long, seekMs: Long?): Boolean =
        TvSidekick.shouldApplySeek(payloadSeq, lastAppliedSeq, seekMs)

    fun shouldApplyPlay(payloadSeq: Long, lastAppliedSeq: Long, playing: Boolean?): Boolean =
        playing != null && payloadSeq > lastAppliedSeq

    fun shouldForceResync(payloadRevision: Long, lastAppliedRevision: Long): Boolean =
        payloadRevision > lastAppliedRevision
}
