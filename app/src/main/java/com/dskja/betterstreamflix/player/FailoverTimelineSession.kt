package com.dskja.betterstreamflix.player

import com.dskja.betterstreamflix.models.Video

/**
 * Per-playback-session attempt list owned by player fragments.
 * Cleared when a new server list loads.
 */
class FailoverTimelineSession {
    private val attempts = mutableListOf<FailoverAttempt>()
    private var successTimelineShown = false

    fun clear() {
        attempts.clear()
        successTimelineShown = false
    }

    fun snapshot(): List<FailoverAttempt> = attempts.toList()

    fun recordServerFailure(
        server: Video.Server?,
        servers: List<Video.Server>,
        error: Throwable?,
    ) {
        val index = PlaybackFailover.indexOfServer(servers, server)
        val ordinal = if (index >= 0) PlaybackFailover.serverOrdinal(index) else 0
        attempts += FailoverAttempt(
            serverOrdinal = ordinal,
            serverName = server?.name.orEmpty(),
            kind = FailoverTimelineBuilder.kindFromError(error),
        )
    }

    fun recordSoftwareDecoder() {
        attempts += FailoverAttempt(
            serverOrdinal = 0,
            kind = FailoverStepKind.SOFTWARE_DECODER,
        )
    }

    fun recordExternalPlayer() {
        attempts += FailoverAttempt(
            serverOrdinal = 0,
            kind = FailoverStepKind.EXTERNAL_PLAYER,
        )
    }

    fun recordOk(server: Video.Server?, servers: List<Video.Server>) {
        if (attempts.any { it.kind == FailoverStepKind.OK }) return
        val index = PlaybackFailover.indexOfServer(servers, server)
        val ordinal = if (index >= 0) PlaybackFailover.serverOrdinal(index) else 0
        attempts += FailoverAttempt(
            serverOrdinal = ordinal,
            serverName = server?.name.orEmpty(),
            kind = FailoverStepKind.OK,
        )
    }

    /** Returns true once when a success timeline should be presented. */
    fun consumeSuccessShow(): Boolean {
        if (successTimelineShown) return false
        if (!FailoverTimelineBuilder.shouldShowAfterSuccess(attempts)) return false
        successTimelineShown = true
        return true
    }
}
