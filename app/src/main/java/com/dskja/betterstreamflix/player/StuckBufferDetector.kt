package com.dskja.betterstreamflix.player

import androidx.media3.common.Player

/**
 * Detects endless buffering so the player can offer “anderen Server”
 * before the user rage-quits with Back.
 *
 * Pure timing/state — UI and hoster hops stay in the player fragments.
 */
class StuckBufferDetector(
    private val initialThresholdMs: Long = DEFAULT_INITIAL_THRESHOLD_MS,
    private val rebufferThresholdMs: Long = DEFAULT_REBUFFER_THRESHOLD_MS,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {

    private var enabled: Boolean = true
    private var resolveInFlight: Boolean = false
    private var hasStartedPlayback: Boolean = false
    private var bufferingSinceMs: Long? = null
    private var offeredForStall: Boolean = false
    private var overlayVisible: Boolean = false

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            clearStall()
            overlayVisible = false
        }
    }

    fun setResolveInFlight(inFlight: Boolean) {
        resolveInFlight = inFlight
        if (inFlight) {
            clearStall()
        }
    }

    fun markPlaybackStarted() {
        hasStartedPlayback = true
    }

    fun onServerChanged() {
        hasStartedPlayback = false
        clearStall()
        overlayVisible = false
    }

    /** Seeks cause a short, expected rebuffer — reset the stall clock. */
    fun onSeek() {
        clearStall()
    }

    fun onPlaybackStateChanged(playbackState: Int, playWhenReady: Boolean) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                if (playWhenReady) beginBuffering() else clearStall()
            }
            Player.STATE_READY, Player.STATE_ENDED, Player.STATE_IDLE -> clearStall()
        }
    }

    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            markPlaybackStarted()
            clearStall()
        }
    }

    fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible
    }

    fun isOverlayVisible(): Boolean = overlayVisible

    fun isBuffering(): Boolean = bufferingSinceMs != null

    /**
     * @return true once per stall after the threshold, when another server can help.
     */
    fun pollShouldOffer(serverCount: Int): Boolean {
        if (!enabled || resolveInFlight || offeredForStall || overlayVisible) return false
        if (serverCount < 2) return false
        val since = bufferingSinceMs ?: return false
        val threshold = if (hasStartedPlayback) rebufferThresholdMs else initialThresholdMs
        return nowMs() - since >= threshold
    }

    fun markOffered() {
        offeredForStall = true
    }

    fun dismissOverlay() {
        overlayVisible = false
    }

    fun reset() {
        enabled = true
        resolveInFlight = false
        hasStartedPlayback = false
        clearStall()
        overlayVisible = false
    }

    /** Test helper: elapsed buffering milliseconds, or null when not buffering. */
    internal fun bufferingElapsedMs(): Long? {
        val since = bufferingSinceMs ?: return null
        return (nowMs() - since).coerceAtLeast(0L)
    }

    private fun beginBuffering() {
        if (!enabled || resolveInFlight) return
        if (bufferingSinceMs == null) {
            bufferingSinceMs = nowMs()
            offeredForStall = false
        }
    }

    private fun clearStall() {
        bufferingSinceMs = null
        offeredForStall = false
    }

    companion object {
        const val DEFAULT_INITIAL_THRESHOLD_MS = 12_000L
        const val DEFAULT_REBUFFER_THRESHOLD_MS = 10_000L
        const val POLL_INTERVAL_MS = 500L
    }
}
