package com.betterstreamflix.app.player

/**
 * Smart Mute Ads-Art — auto-seek past **broken** pre-/mid-roll hangs only.
 *
 * Never seeks while the playhead advances, while the user paused, on Live/offline,
 * near the end credits, or after the per-session seek budget is spent. Real content
 * must keep playing; we only jump when the stream is demonstrably stuck in an
 * ad-like window.
 */
object SmartMuteAds {

    /** Position must stay frozen this long before we consider a hang. */
    const val STUCK_MS: Long = 8_000L

    /** Opening window where a hang is treated as a pre-roll. */
    const val PRE_ROLL_WINDOW_MS: Long = 90_000L

    /** Default jump when the buffer does not give a safer landing. */
    const val SEEK_STEP_MS: Long = 15_000L

    /** Hard cap on how far a single rescue seek may jump. */
    const val MAX_SEEK_JUMP_MS: Long = 30_000L

    /** Minimum advance that clears the freeze clock. */
    const val MIN_ADVANCE_MS: Long = 400L

    /** Do not rescue in the last portion of a known-duration title. */
    const val END_GUARD_RATIO: Double = 0.08

    const val MAX_SEEKS_PER_SESSION: Int = 3
    const val COOLDOWN_AFTER_SEEK_MS: Long = 12_000L
    const val COOLDOWN_AFTER_USER_SEEK_MS: Long = 8_000L

    enum class Reason { STUCK_AD, STUCK_PREROLL, STUCK_MIDROLL }

    enum class PlaybackPhase { IDLE, BUFFERING, READY, ENDED, OTHER }

    data class Snapshot(
        val nowMs: Long,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val durationMs: Long,
        val phase: PlaybackPhase,
        val isPlaying: Boolean,
        val playWhenReady: Boolean,
        val isPlayingAd: Boolean,
        val isLive: Boolean,
        val isOffline: Boolean,
    )

    data class SeekTo(
        val positionMs: Long,
        val reason: Reason,
    )

    /**
     * Mutable watchdog state for one player session. Call [reset] when media changes.
     */
    class Watchdog {
        private var lastPositionMs: Long = -1L
        /** Wall-clock when freeze began; [UNSET] when the playhead is advancing. */
        private var frozenSinceMs: Long = UNSET
        private var lastSeekAtMs: Long = UNSET
        private var lastUserSeekAtMs: Long = UNSET
        private var seeksThisSession: Int = 0
        private var everAdvancedPastPreroll: Boolean = false

        fun reset() {
            lastPositionMs = -1L
            frozenSinceMs = UNSET
            lastSeekAtMs = UNSET
            lastUserSeekAtMs = UNSET
            seeksThisSession = 0
            everAdvancedPastPreroll = false
        }

        /** User scrub / chapter jump — never treat the next freeze as an ad hang. */
        fun onUserSeek(nowMs: Long) {
            lastUserSeekAtMs = nowMs
            frozenSinceMs = UNSET
            lastPositionMs = -1L
        }

        /** True once the per-session seek budget is spent (host can arm Stuck sooner). */
        fun isBudgetExhausted(): Boolean = seeksThisSession >= MAX_SEEKS_PER_SESSION

        fun onTick(snapshot: Snapshot): SeekTo? {
            if (snapshot.isLive || snapshot.isOffline) {
                clearFreeze()
                return null
            }
            if (snapshot.positionMs < 0L) return null

            // User intentionally paused — not a hung ad.
            if (!snapshot.playWhenReady && snapshot.phase == PlaybackPhase.READY) {
                clearFreeze()
                return null
            }

            if (seeksThisSession >= MAX_SEEKS_PER_SESSION) return null
            if (lastSeekAtMs != UNSET &&
                snapshot.nowMs - lastSeekAtMs < COOLDOWN_AFTER_SEEK_MS
            ) {
                return null
            }
            if (lastUserSeekAtMs != UNSET &&
                snapshot.nowMs - lastUserSeekAtMs < COOLDOWN_AFTER_USER_SEEK_MS
            ) {
                clearFreeze()
                return null
            }

            if (PlaybackProgress.hasKnownDuration(snapshot.durationMs)) {
                val endGuard = (snapshot.durationMs * (1.0 - END_GUARD_RATIO)).toLong()
                if (snapshot.positionMs >= endGuard) {
                    clearFreeze()
                    return null
                }
            }

            val previous = lastPositionMs
            if (previous >= 0L && snapshot.positionMs - previous >= MIN_ADVANCE_MS) {
                lastPositionMs = snapshot.positionMs
                frozenSinceMs = UNSET
                if (snapshot.positionMs > PRE_ROLL_WINDOW_MS) {
                    everAdvancedPastPreroll = true
                }
                return null
            }

            // First sample or tiny jitter — start / keep freeze clock.
            if (previous < 0L) {
                lastPositionMs = snapshot.positionMs
                frozenSinceMs = snapshot.nowMs
                return null
            }
            if (frozenSinceMs == UNSET) {
                frozenSinceMs = snapshot.nowMs
            }
            lastPositionMs = snapshot.positionMs

            val frozenFor = snapshot.nowMs - frozenSinceMs
            if (frozenFor < STUCK_MS) return null

            // Stuck, but only rescue when it looks like an ad hang.
            val reason = classifyStuck(snapshot) ?: run {
                // Still stuck for other reasons (e.g. slow network mid-scene) — do not cut.
                return null
            }

            // Preroll: require BUFFERING or explicit ad (avoid seeking into DRM/slow starts).
            // Mid-roll / explicit ads: also allow frozen READY + playWhenReady.
            val hung = when (reason) {
                Reason.STUCK_PREROLL ->
                    snapshot.isPlayingAd || snapshot.phase == PlaybackPhase.BUFFERING
                Reason.STUCK_AD,
                Reason.STUCK_MIDROLL,
                ->
                    snapshot.isPlayingAd ||
                        snapshot.phase == PlaybackPhase.BUFFERING ||
                        (snapshot.playWhenReady &&
                            !positionAdvancing(previous, snapshot.positionMs))
            }
            if (!hung) return null

            val target = seekTarget(snapshot) ?: return null
            if (target <= snapshot.positionMs + MIN_ADVANCE_MS) return null

            seeksThisSession++
            lastSeekAtMs = snapshot.nowMs
            frozenSinceMs = UNSET
            lastPositionMs = target
            return SeekTo(positionMs = target, reason = reason)
        }

        private fun classifyStuck(snapshot: Snapshot): Reason? {
            if (snapshot.isPlayingAd) return Reason.STUCK_AD
            if (snapshot.positionMs < PRE_ROLL_WINDOW_MS) return Reason.STUCK_PREROLL
            // Mid-roll: after real content advanced past the opening — frozen READY OK.
            if (everAdvancedPastPreroll &&
                (
                    snapshot.phase == PlaybackPhase.BUFFERING ||
                        snapshot.isPlayingAd ||
                        (snapshot.playWhenReady && snapshot.phase == PlaybackPhase.READY)
                    )
            ) {
                return Reason.STUCK_MIDROLL
            }
            return null
        }

        private fun seekTarget(snapshot: Snapshot): Long? {
            val pos = snapshot.positionMs
            val buffered = snapshot.bufferedPositionMs
            val duration = snapshot.durationMs
            val stepCap = (pos + MAX_SEEK_JUMP_MS)
            var target = when {
                buffered > pos + 2_000L ->
                    buffered.coerceAtMost(stepCap)
                else ->
                    pos + SEEK_STEP_MS
            }
            target = target.coerceAtMost(stepCap)
            if (PlaybackProgress.hasKnownDuration(duration)) {
                val maxAllowed = (duration * (1.0 - END_GUARD_RATIO)).toLong()
                    .coerceAtLeast(pos + MIN_ADVANCE_MS)
                target = target.coerceAtMost(maxAllowed)
                if (target >= duration - 1_000L) return null
            }
            return target.takeIf { it > pos + MIN_ADVANCE_MS }
        }

        private fun clearFreeze() {
            frozenSinceMs = UNSET
        }

        private fun positionAdvancing(previous: Long, current: Long): Boolean =
            previous >= 0L && current - previous >= MIN_ADVANCE_MS
    }

    private const val UNSET: Long = Long.MIN_VALUE

    fun phaseFromMedia3(playbackState: Int): PlaybackPhase = when (playbackState) {
        // Media3 Player.STATE_* constants mirrored to keep unit tests free of Media3.
        1 -> PlaybackPhase.IDLE      // STATE_IDLE
        2 -> PlaybackPhase.BUFFERING // STATE_BUFFERING
        3 -> PlaybackPhase.READY     // STATE_READY
        4 -> PlaybackPhase.ENDED     // STATE_ENDED
        else -> PlaybackPhase.OTHER
    }
}
