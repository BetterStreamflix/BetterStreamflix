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
        @Suppress("UNUSED_PARAMETER") isCasting: Boolean,
        resolveInFlight: Boolean,
    ): Boolean {
        // Casting still gets a local chooser (Similar / Back / Next) instead of
        // silently autoplaying the next episode with no escape hatch.
        if (isLive || resolveInFlight) return false
        return reallyFinished && stateEnded
    }

    fun shouldAutoplayAfterCountdown(
        autoplayEnabled: Boolean,
        hasNextEpisode: Boolean,
        countdownCancelled: Boolean,
        bedtimeBlocks: Boolean = false,
    ): Boolean = autoplayEnabled && hasNextEpisode && !countdownCancelled && !bedtimeBlocks

    fun countdownLabelSec(remainingSec: Int): Int = remainingSec.coerceAtLeast(0)
}
