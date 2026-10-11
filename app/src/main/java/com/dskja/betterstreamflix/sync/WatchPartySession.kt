package com.dskja.betterstreamflix.sync

/**
 * In-memory Watch Together / Watch-Party role for the active player / lobby / remote.
 * Syncs position + chosen server (+ sidekick commands) only — never stream URLs.
 */
object WatchPartySession {
    enum class Role { HOST, GUEST, REMOTE }

    @Volatile
    var role: Role? = null
        private set

    @Volatile
    var token: String? = null
        private set

    @Volatile
    var partyId: String? = null
        private set

    @Volatile
    var shortCode: String? = null
        private set

    @Volatile
    var displayMode: String = WatchTogetherPro.MODE_SYNC_ALL
        private set

    @Volatile
    var inLobby: Boolean = false
        private set

    @Volatile
    var hostControlsLocked: Boolean = false

    @Volatile
    var lastAppliedPositionMs: Long = -1L

    @Volatile
    var lastAppliedServerKey: String? = null

    @Volatile
    var lastAppliedPlaying: Boolean? = null

    @Volatile
    var lastMessageAt: String? = null

    @Volatile
    var lastAppliedSeekSeq: Long = 0L

    @Volatile
    var lastAppliedPlaySeq: Long = 0L

    @Volatile
    var lastAppliedSyncRevision: Long = 0L

    @Volatile
    var lastLocalDriftMs: Long = 0L

    /** Latest sidekick_seek_seq observed on host poll (for heartbeat skip). */
    @Volatile
    var lastSeenSidekickSeekSeq: Long = 0L

    @Volatile
    var lastDriftReportAtMs: Long = 0L

    @Volatile
    var lastReportedDriftMs: Long = -1L

    @Volatile
    var lastGuestSeekAtMs: Long = 0L

    fun beginHost(
        token: String,
        partyId: String?,
        inLobby: Boolean = true,
        shortCode: String? = null,
        displayMode: String = WatchTogetherPro.MODE_SYNC_ALL,
    ) {
        role = Role.HOST
        this.token = token
        this.partyId = partyId
        this.shortCode = WatchPartyLinks.normalizeShortCode(shortCode) ?: shortCode
        this.displayMode = WatchTogetherPro.normalizeDisplayMode(displayMode)
        this.inLobby = inLobby
        resetCursors()
    }

    fun beginGuest(
        token: String,
        partyId: String?,
        inLobby: Boolean = false,
        shortCode: String? = null,
        displayMode: String = WatchTogetherPro.MODE_SYNC_ALL,
    ) {
        role = Role.GUEST
        this.token = token
        this.partyId = partyId
        this.shortCode = WatchPartyLinks.normalizeShortCode(shortCode) ?: shortCode
        this.displayMode = WatchTogetherPro.normalizeDisplayMode(displayMode)
        this.inLobby = inLobby
        resetCursors()
    }

    fun beginRemote(
        token: String,
        partyId: String?,
        shortCode: String? = null,
        displayMode: String = WatchTogetherPro.MODE_TV_SCREEN,
    ) {
        role = Role.REMOTE
        this.token = token
        this.partyId = partyId
        this.shortCode = WatchPartyLinks.normalizeShortCode(shortCode) ?: shortCode
        this.displayMode = WatchTogetherPro.normalizeDisplayMode(displayMode)
        this.inLobby = false
        resetCursors()
    }

    fun markPlaybackStarted() {
        inLobby = false
    }

    fun updateFromPayload(payload: WatchPartyPayload) {
        if (!payload.shortCode.isNullOrBlank()) {
            shortCode = WatchPartyLinks.normalizeShortCode(payload.shortCode) ?: payload.shortCode
        }
        displayMode = WatchTogetherPro.normalizeDisplayMode(payload.displayMode)
        hostControlsLocked = payload.hostControlsLocked
        if (!payload.id.isNullOrBlank()) {
            partyId = payload.id
        }
    }

    fun setDisplayMode(mode: String) {
        displayMode = WatchTogetherPro.normalizeDisplayMode(mode)
    }

    fun clear() {
        role = null
        token = null
        partyId = null
        shortCode = null
        displayMode = WatchTogetherPro.MODE_SYNC_ALL
        inLobby = false
        hostControlsLocked = false
        resetCursors()
    }

    fun isActive(): Boolean = role != null && !token.isNullOrBlank()

    fun isHost(): Boolean = role == Role.HOST

    fun isGuest(): Boolean = role == Role.GUEST

    fun isRemote(): Boolean = role == Role.REMOTE

    fun isInLobby(): Boolean = isActive() && inLobby

    fun isTvScreenMode(): Boolean = WatchTogetherPro.isTvScreenMode(displayMode)

    private fun resetCursors() {
        lastAppliedPositionMs = -1L
        lastAppliedServerKey = null
        lastAppliedPlaying = null
        lastMessageAt = null
        lastAppliedSeekSeq = 0L
        lastAppliedPlaySeq = 0L
        lastAppliedSyncRevision = 0L
        lastLocalDriftMs = 0L
        lastSeenSidekickSeekSeq = 0L
        lastDriftReportAtMs = 0L
        lastReportedDriftMs = -1L
        lastGuestSeekAtMs = 0L
        hostControlsLocked = false
    }
}
