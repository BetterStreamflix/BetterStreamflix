package com.dskja.betterstreamflix.download

/**
 * When a download may be marked watched.
 *
 * Unknown duration (Media3 TIME_UNSET) and short HLS sliding windows used to
 * satisfy `position > duration * 0.90` and stamp watched / auto-delete.
 */
object DownloadWatchPolicy {
    private const val MIN_MARK_DURATION_MS = 60_000L
    private const val MAX_KNOWN_MS = 48L * 60L * 60L * 1000L

    fun isKnownTime(value: Long): Boolean = value in 0L..MAX_KNOWN_MS

    /**
     * True only when playback is actually near the end of a real program,
     * or the player reached STATE_ENDED on a known duration.
     */
    fun shouldMarkWatched(
        positionMs: Long,
        durationMs: Long,
        playbackEnded: Boolean = false,
    ): Boolean {
        if (!isKnownTime(positionMs) || !isKnownTime(durationMs) || durationMs < 1_000L) {
            return false
        }
        val nearEnd = positionMs >= (durationMs * 9L) / 10L
        // Sliding live windows and unset/short durations must not stamp watched
        // or trigger Smart Downloads delete, even if the player reports ENDED.
        if (durationMs < MIN_MARK_DURATION_MS) return false
        if (playbackEnded) {
            return positionMs >= durationMs / 2L &&
                positionMs >= durationMs - 20_000L
        }
        return nearEnd
    }

    fun hasReallyFinished(
        positionMs: Long,
        durationMs: Long,
        autoplayBufferSec: Long,
    ): Boolean {
        if (!isKnownTime(durationMs) || durationMs < MIN_MARK_DURATION_MS) return false
        if (!isKnownTime(positionMs)) return false
        val bufferMs = autoplayBufferSec.coerceAtLeast(0L) * 1000L
        return positionMs >= durationMs - bufferMs &&
            positionMs >= (durationMs * 9L) / 10L
    }

    fun hasStarted(positionMs: Long, durationMs: Long): Boolean {
        if (!isKnownTime(positionMs) || positionMs <= 0L) return false
        if (!isKnownTime(durationMs)) return positionMs > 20_000L
        return positionMs > durationMs / 200L || positionMs > 20_000L
    }
}
