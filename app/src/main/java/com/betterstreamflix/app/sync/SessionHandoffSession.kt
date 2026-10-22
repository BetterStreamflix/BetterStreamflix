package com.betterstreamflix.app.sync

/**
 * In-memory Phone→TV Session Handoff offer state (phone side while code is live).
 */
object SessionHandoffSession {
    @Volatile
    var token: String? = null
        private set

    @Volatile
    var handoffId: String? = null
        private set

    @Volatile
    var shortCode: String? = null
        private set

    fun beginOffer(token: String, handoffId: String?, shortCode: String) {
        this.token = token
        this.handoffId = handoffId
        this.shortCode = shortCode
    }

    fun clear() {
        token = null
        handoffId = null
        shortCode = null
    }

    fun isOffering(): Boolean = !token.isNullOrBlank() && !shortCode.isNullOrBlank()
}
