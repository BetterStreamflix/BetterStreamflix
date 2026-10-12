package com.dskja.betterstreamflix.live

import com.dskja.betterstreamflix.models.Video

/**
 * Pure rules for in-player Mini-Live Picture-in-Picture Zap (not Android OS PiP).
 * Eligible during VOD; picks a preview channel from pins then live favorites.
 */
object LivePipZapPolicy {
    /** Preview audio is always muted so VOD keeps focus. */
    const val PREVIEW_MUTED = true

    data class Candidate(
        val id: String,
        val provider: String,
        val title: String,
        val poster: String? = null,
    ) {
        val key: String get() = "$provider|$id"
    }

    /**
     * Snapshot of the VOD session so promote → live can offer “back to movie”.
     */
    data class ReturnTicket(
        val providerName: String,
        val id: String,
        val videoType: Video.Type,
        val title: String,
        val subtitle: String,
        val preferredServerName: String?,
        val positionMs: Long,
    )

    data class Eligibility(
        val isLivePlayback: Boolean,
        val isOffline: Boolean,
        val isKidsOrAgeGated: Boolean,
        val isCasting: Boolean,
        val isSystemPip: Boolean,
        val hasChannelCandidate: Boolean,
    )

    fun isEligible(state: Eligibility): Boolean {
        if (state.isLivePlayback) return false
        if (state.isOffline) return false
        if (state.isKidsOrAgeGated) return false
        if (state.isCasting) return false
        if (state.isSystemPip) return false
        return state.hasChannelCandidate
    }

    fun previewShouldMute(): Boolean = PREVIEW_MUTED

    /**
     * Channel pick order: pins (newest-first store order), then live favorites,
     * then optional recent session channel if it matches a known candidate.
     */
    fun pickChannel(
        pins: List<Candidate>,
        favorites: List<Candidate>,
        recent: Candidate? = null,
    ): Candidate? {
        val pinHit = pins.firstOrNull(::isValidCandidate)
        if (pinHit != null) return pinHit
        if (recent != null && isValidCandidate(recent)) {
            val known = (pins + favorites).any { it.key == recent.key }
            if (known) return recent
        }
        return favorites.firstOrNull(::isValidCandidate)
    }

    fun mergeCandidates(pins: List<Candidate>, favorites: List<Candidate>): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        pins.filter(::isValidCandidate).forEach { out.putIfAbsent(it.key, it) }
        favorites.filter(::isValidCandidate).forEach { out.putIfAbsent(it.key, it) }
        return out.values.toList()
    }

    fun isValidCandidate(candidate: Candidate): Boolean =
        candidate.id.isNotBlank() && candidate.provider.isNotBlank()

    fun buildReturnTicket(
        providerName: String?,
        id: String,
        videoType: Video.Type,
        title: String,
        subtitle: String,
        preferredServerName: String?,
        positionMs: Long,
    ): ReturnTicket? {
        val provider = providerName?.trim().orEmpty()
        if (provider.isEmpty() || id.isBlank()) return null
        return ReturnTicket(
            providerName = provider,
            id = id,
            videoType = videoType,
            title = title,
            subtitle = subtitle,
            preferredServerName = preferredServerName,
            positionMs = positionMs.coerceAtLeast(0L),
        )
    }

    /** TV: preview overlay may take focus only while player chrome is visible. */
    fun tvOverlayFocusable(chromeVisible: Boolean): Boolean = chromeVisible

    /**
     * TV: return-to-VOD chip stays focusable whenever visible so leanback users
     * need not show chrome first (PZ-010).
     */
    fun tvReturnChipFocusable(visible: Boolean): Boolean = visible
}

/**
 * In-memory return ticket across the VOD → live self-navigation.
 */
object LivePipZapSession {
    @Volatile
    private var ticket: LivePipZapPolicy.ReturnTicket? = null

    @Volatile
    private var suppressClearOnDestroy: Boolean = false

    /**
     * Sticky across promote and subsequent LIVE→LIVE player destroys.
     * Cleared only by [beginReturn] or [clear] (PZ-001).
     */
    @Volatile
    private var retainForLiveSession: Boolean = false

    fun stash(ticket: LivePipZapPolicy.ReturnTicket) {
        this.ticket = ticket
        suppressClearOnDestroy = false
        retainForLiveSession = true
    }

    /** Re-stash after a failed return navigate (PZ-002). */
    fun restore(ticket: LivePipZapPolicy.ReturnTicket) {
        this.ticket = ticket
        suppressClearOnDestroy = false
        retainForLiveSession = true
    }

    fun peek(): LivePipZapPolicy.ReturnTicket? = ticket

    fun hasReturnTicket(): Boolean = ticket != null

    /** Consume ticket for a return navigate; suppresses clear during that destroy. */
    fun beginReturn(): LivePipZapPolicy.ReturnTicket? {
        val current = ticket ?: return null
        ticket = null
        suppressClearOnDestroy = true
        retainForLiveSession = false
        return current
    }

    fun onPlayerDestroyed(wasLivePlayback: Boolean) {
        if (retainForLiveSession && wasLivePlayback) {
            // Keep ticket across promote and CH± / live self-nav destroys.
            return
        }
        if (wasLivePlayback && !suppressClearOnDestroy) {
            ticket = null
        }
        suppressClearOnDestroy = false
    }

    fun clear() {
        ticket = null
        suppressClearOnDestroy = false
        retainForLiveSession = false
    }
}
