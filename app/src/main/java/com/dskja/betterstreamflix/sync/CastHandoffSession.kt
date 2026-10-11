package com.dskja.betterstreamflix.sync

/**
 * In-memory Cast Hand-off state.
 * TV hosts an offer code; Phone claims once and seeks via [pendingPositionMs].
 * Never stores stream URLs.
 */
object CastHandoffSession {
    enum class Role { OFFER, CLAIM }

    @Volatile
    var role: Role? = null
        private set

    @Volatile
    var shortCode: String? = null
        private set

    @Volatile
    var handoffId: String? = null
        private set

    /** Applied once after claimer [displayVideo] — wins over Continue Watching resume. */
    @Volatile
    var pendingPositionMs: Long? = null
        private set

    @Volatile
    var pendingServerName: String? = null
        private set

    fun beginOffer(shortCode: String, handoffId: String?) {
        role = Role.OFFER
        this.shortCode = shortCode
        this.handoffId = handoffId
        pendingPositionMs = null
        pendingServerName = null
    }

    fun beginClaim(shortCode: String, handoffId: String?, positionMs: Long, serverName: String?) {
        role = Role.CLAIM
        this.shortCode = shortCode
        this.handoffId = handoffId
        pendingPositionMs = positionMs.coerceAtLeast(0L)
        pendingServerName = serverName
    }

    /** Returns and clears the one-shot seek target. */
    fun consumePendingPositionMs(): Long? {
        val value = pendingPositionMs ?: return null
        pendingPositionMs = null
        return value
    }

    fun hasPendingPosition(): Boolean = pendingPositionMs != null

    fun clear() {
        role = null
        shortCode = null
        handoffId = null
        pendingPositionMs = null
        pendingServerName = null
    }

    fun isActive(): Boolean = role != null && !shortCode.isNullOrBlank()

    fun isOffer(): Boolean = role == Role.OFFER

    fun isClaim(): Boolean = role == Role.CLAIM
}
