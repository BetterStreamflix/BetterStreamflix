package com.dskja.betterstreamflix.player

/**
 * Unified playback failover policy for mobile + TV.
 * Prefer the next hoster first; after servers are exhausted, retry once with software decoding.
 * Optional third stage: hand off to an external MPV-capable player when enabled.
 *
 * Decoder capability failures (BETTERSTREAMFLIX-D) skip hoster hopping and go straight to
 * software-decoder retry — the next server usually has the same AVC profile.
 */
object PlaybackFailover {

    sealed class Action {
        data class TryNextServer(val nextIndex: Int) : Action()
        data object RetrySoftwareDecoder : Action()
        data object TryExternalPlayer : Action()
        data object GiveUp : Action()
    }

    /** True when Exo hit a dead CDN/HTML page — skip lingering on this server. */
    fun isHardSourceFailure(error: Throwable?): Boolean {
        val chain = errorChain(error)
        return "unrecognizedinputformat" in chain ||
            "none of the available extractors" in chain ||
            "response code: 403" in chain ||
            "response code: 404" in chain ||
            "response code: 410" in chain ||
            "response code: 451" in chain ||
            ("httpdatasource" in chain && ("403" in chain || "404" in chain))
    }

    /** HW decoder cannot handle this format (10-bit / exceeds capabilities). */
    fun isDecoderCapabilityFailure(error: Throwable?): Boolean {
        val chain = errorChain(error)
        return "no_exceeds_capabilities" in chain ||
            "exceeds_capabilities" in chain ||
            "mediacodecvideodecoderexception" in chain ||
            "decoder failed" in chain ||
            ("mediacodec" in chain && ("0x80001009" in chain || "error 0x8000" in chain))
    }

    fun decide(
        currentServerIndex: Int,
        serverCount: Int,
        playbackAlreadyStarted: Boolean,
        softwareDecoderAlreadyEnabled: Boolean,
        allowMidPlaybackFailover: Boolean = false,
        externalPlayerAvailable: Boolean = false,
        externalPlayerAlreadyTried: Boolean = false,
        error: Throwable? = null,
    ): Action {
        // Same stream profile on another hoster won't fix decoder capability — soft-retry first.
        if (isDecoderCapabilityFailure(error) && !softwareDecoderAlreadyEnabled) {
            return Action.RetrySoftwareDecoder
        }

        if (playbackAlreadyStarted && !allowMidPlaybackFailover) {
            // Mid-play URI clear on TV looks like a crash-to-home; stop cascading.
            return when {
                !softwareDecoderAlreadyEnabled -> Action.RetrySoftwareDecoder
                externalPlayerAvailable && !externalPlayerAlreadyTried -> Action.TryExternalPlayer
                else -> Action.GiveUp
            }
        }
        // indexOf can return -1 when the server instance identity differs — treat as 0.
        val safeIndex = currentServerIndex.coerceAtLeast(0)
        val next = safeIndex + 1
        if (next in 0 until serverCount) {
            return Action.TryNextServer(next)
        }
        if (!softwareDecoderAlreadyEnabled) {
            return Action.RetrySoftwareDecoder
        }
        if (externalPlayerAvailable && !externalPlayerAlreadyTried) {
            return Action.TryExternalPlayer
        }
        return Action.GiveUp
    }

    private fun errorChain(error: Throwable?): String =
        generateSequence(error) { it.cause }
            .mapNotNull { it.message ?: it.javaClass.simpleName }
            .joinToString(" ")
            .lowercase()
}
