package com.betterstreamflix.app.player

/**
 * Post-credits End-Card Chooser timing shared by Mobile and TV players.
 *
 * Near-end “Up next” stays a corner overlay; the end card is the full chooser
 * after [STATE_ENDED] / credits.
 */
object EndCardPolicy {

    /** Seconds of chooser countdown before autoplay advances (when autoplay is on). */
    const val AUTOPLAY_COUNTDOWN_SEC: Int = 10

    /** Shorter countdown under Codec Comfort / Stick Energy (CC-008). */
    const val AUTOPLAY_COUNTDOWN_SEC_COMFORT: Int = 6

    /** Effective autoplay countdown; capped when Comfort latched. */
    fun autoplayCountdownSec(
        codecComfortActive: Boolean = false,
    ): Int = if (codecComfortActive) AUTOPLAY_COUNTDOWN_SEC_COMFORT else AUTOPLAY_COUNTDOWN_SEC

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
