package com.dskja.betterstreamflix.remotefind

/**
 * In-memory Remote Find Phone session: TV hosts the LAN server; phone is the pad/keyboard.
 */
object RemoteFindSession {
    enum class Role { TV_HOST, PHONE }

    @Volatile
    var role: Role? = null
        private set

    @Volatile
    var token: String? = null
        private set

    @Volatile
    var shortCode: String? = null
        private set

    @Volatile
    var wsUrl: String? = null
        private set

    @Volatile
    var phoneConnected: Boolean = false

    fun beginHost(token: String, shortCode: String, wsUrl: String) {
        role = Role.TV_HOST
        this.token = token
        this.shortCode = shortCode
        this.wsUrl = wsUrl
        phoneConnected = false
    }

    fun beginPhone(token: String, shortCode: String?, wsUrl: String) {
        role = Role.PHONE
        this.token = token
        this.shortCode = shortCode
        this.wsUrl = wsUrl
        phoneConnected = true
    }

    fun clear() {
        role = null
        token = null
        shortCode = null
        wsUrl = null
        phoneConnected = false
    }

    fun isActive(): Boolean = role != null && !token.isNullOrBlank()

    fun isHost(): Boolean = role == Role.TV_HOST

    fun isPhone(): Boolean = role == Role.PHONE
}
