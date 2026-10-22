package com.betterstreamflix.app.sync

/**
 * In-memory Cast Room role: TV host consumes the queue; phone controller mutates it.
 * Syncs media identity + queue order only — never stream URLs.
 */
object CastRoomSession {
    enum class Role { HOST, CONTROLLER }

    @Volatile
    var role: Role? = null
        private set

    @Volatile
    var token: String? = null
        private set

    @Volatile
    var roomId: String? = null
        private set

    @Volatile
    var shortCode: String? = null
        private set

    @Volatile
    var lastAppliedRevision: Long = -1L

    @Volatile
    var lastAppliedPlayingKey: String? = null

    @Volatile
    var lastAppliedPlaying: Boolean? = null

    /** Last Sidekick scrubber sequence applied on the TV host. */
    @Volatile
    var lastAppliedSeekSeq: Long = 0L

    /** Last Sidekick keyboard sequence applied on the TV host. */
    @Volatile
    var lastAppliedTextSeq: Long = 0L

    /** Last chat message timestamp toasted on the host surface (CC-035). */
    @Volatile
    var lastHostChatSeenAt: String? = null

    fun beginHost(token: String, roomId: String?, shortCode: String?) {
        role = Role.HOST
        this.token = token
        this.roomId = roomId
        this.shortCode = shortCode
        lastAppliedRevision = -1L
        lastAppliedPlayingKey = null
        lastAppliedPlaying = null
        lastAppliedSeekSeq = 0L
        lastAppliedTextSeq = 0L
        lastHostChatSeenAt = null
    }

    fun beginController(token: String, roomId: String?, shortCode: String?) {
        role = Role.CONTROLLER
        this.token = token
        this.roomId = roomId
        this.shortCode = shortCode
        lastAppliedRevision = -1L
        lastAppliedPlayingKey = null
        lastAppliedPlaying = null
        lastAppliedSeekSeq = 0L
        lastAppliedTextSeq = 0L
        lastHostChatSeenAt = null
    }

    fun clear() {
        role = null
        token = null
        roomId = null
        shortCode = null
        lastAppliedRevision = -1L
        lastAppliedPlayingKey = null
        lastAppliedPlaying = null
        lastAppliedSeekSeq = 0L
        lastAppliedTextSeq = 0L
        lastHostChatSeenAt = null
    }

    fun isActive(): Boolean = role != null && !token.isNullOrBlank()

    fun isHost(): Boolean = role == Role.HOST

    fun isController(): Boolean = role == Role.CONTROLLER
}
