package com.dskja.betterstreamflix.player

/**
 * VOD progress gates shared by the mobile and TV players.
 *
 * Media3 reports [TIME_UNSET] until a duration is known. Treating that sentinel
 * as a real length marks a title watched and wipes the resume point.
 */
object PlaybackProgress {

    /** Same sentinel as Media3 `C.TIME_UNSET`, without pulling the player into unit tests. */
    const val TIME_UNSET: Long = Long.MIN_VALUE + 1

    const val RESUME_REWIND_MS: Long = 10_000L
    const val STARTED_POSITION_MS: Long = 20_000L
    const val RESUME_APPLY_MAX_POSITION_MS: Long = 1_500L

    fun hasKnownDuration(durationMs: Long): Boolean = durationMs > 0L

    fun hasStarted(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs > STARTED_POSITION_MS) return true
        if (!hasKnownDuration(durationMs)) return false
        return positionMs > durationMs * 0.005
    }

    fun hasFinished(positionMs: Long, durationMs: Long): Boolean {
        if (!hasKnownDuration(durationMs)) return false
        return positionMs > durationMs * 0.90
    }

    fun hasReallyFinished(positionMs: Long, durationMs: Long, autoplayBufferSec: Long): Boolean {
        if (!hasKnownDuration(durationMs)) return false
        val bufferMs = autoplayBufferSec.coerceAtLeast(0L) * 1000L
        return positionMs >= durationMs - bufferMs
    }

    /**
     * Saved position minus a short rewind. Never negative, and never parked
     * at or past the end when the duration is already known.
     */
    fun resumeTargetMs(savedPositionMs: Long, durationMs: Long = TIME_UNSET): Long {
        val floored = (savedPositionMs - RESUME_REWIND_MS).coerceAtLeast(0L)
        if (!hasKnownDuration(durationMs) || floored < durationMs) return floored
        return (durationMs - RESUME_REWIND_MS).coerceAtLeast(0L)
    }

    /** A late resume lookup must not jump the playhead after the viewer already seeked. */
    fun shouldApplyResume(currentPositionMs: Long): Boolean =
        currentPositionMs in 0L..RESUME_APPLY_MAX_POSITION_MS

    fun durationToStore(observedMs: Long, previousMs: Long?): Long {
        if (hasKnownDuration(observedMs)) return observedMs
        return previousMs?.takeIf { hasKnownDuration(it) } ?: 0L
    }
}
