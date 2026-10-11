package com.dskja.betterstreamflix.sync

/**
 * In-memory Watch-Party role for the active player / lobby instance.
 * Syncs position + chosen server only — never stream URLs.
 */
object WatchPartySession {
    enum class Role { HOST, GUEST }

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
    var inLobby: Boolean = false
        private set

    @Volatile
    var lastAppliedPositionMs: Long = -1L

    @Volatile
    var lastAppliedServerKey: String? = null

    @Volatile
    var lastAppliedPlaying: Boolean? = null

    @Volatile
    var lastMessageAt: String? = null

    fun beginHost(token: String, partyId: String?, inLobby: Boolean = true) {
        role = Role.HOST
        this.token = token
        this.partyId = partyId
        this.inLobby = inLobby
        lastAppliedPositionMs = -1L
        lastAppliedServerKey = null
        lastAppliedPlaying = null
        lastMessageAt = null
    }

    fun beginGuest(token: String, partyId: String?, inLobby: Boolean = false) {
        role = Role.GUEST
        this.token = token
        this.partyId = partyId
        this.inLobby = inLobby
        lastAppliedPositionMs = -1L
        lastAppliedServerKey = null
        lastAppliedPlaying = null
        lastMessageAt = null
    }

    fun markPlaybackStarted() {
        inLobby = false
    }

    fun clear() {
        role = null
        token = null
        partyId = null
        inLobby = false
        lastAppliedPositionMs = -1L
        lastAppliedServerKey = null
        lastAppliedPlaying = null
        lastMessageAt = null
    }

    fun isActive(): Boolean = role != null && !token.isNullOrBlank()

    fun isHost(): Boolean = role == Role.HOST

    fun isGuest(): Boolean = role == Role.GUEST

    fun isInLobby(): Boolean = isActive() && inLobby
}
