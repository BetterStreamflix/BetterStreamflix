package com.dskja.betterstreamflix.player

/**
 * Post-credits End-Card Chooser timing shared by Mobile and TV players.
 *
 * Near-end “Up next” stays a corner overlay; the end card is the full chooser
 * after [STATE_ENDED] / credits.
 */
object EndCardPolicy {

    /** Seconds of chooser countdown before autoplay advances (when autoplay is on). */
    const val AUTOPLAY_COUNTDOWN_SEC: Int = 10

    fun shouldShowEndCard(
        reallyFinished: Boolean,
        stateEnded: Boolean,
        isLive: Boolean,
        isCasting: Boolean,
        resolveInFlight: Boolean,
    ): Boolean {
        if (isLive || isCasting || resolveInFlight) return false
        return reallyFinished && stateEnded
    }

    fun shouldAutoplayAfterCountdown(
        autoplayEnabled: Boolean,
        hasNextEpisode: Boolean,
        countdownCancelled: Boolean,
    ): Boolean = autoplayEnabled && hasNextEpisode && !countdownCancelled

    fun countdownLabelSec(remainingSec: Int): Int = remainingSec.coerceAtLeast(0)
}
