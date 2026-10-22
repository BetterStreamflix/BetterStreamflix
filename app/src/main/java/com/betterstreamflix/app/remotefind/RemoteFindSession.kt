package com.betterstreamflix.app.remotefind

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

    /** Closes the live phone WS client + finishes the phone activity (Settings Leave). */
    @Volatile
    private var phoneLeaveAction: (() -> Unit)? = null

    fun beginHost(token: String, shortCode: String, wsUrl: String) {
        role = Role.TV_HOST
        this.token = token
        this.shortCode = shortCode
        this.wsUrl = wsUrl
        phoneConnected = false
        phoneLeaveAction = null
    }

    fun beginPhone(token: String?, shortCode: String?, wsUrl: String) {
        role = Role.PHONE
        this.token = token
        this.shortCode = shortCode
        this.wsUrl = wsUrl
        phoneConnected = true
    }

    fun updatePhoneToken(token: String) {
        if (role == Role.PHONE) {
            this.token = token
        }
    }

    fun registerPhoneLeave(action: (() -> Unit)?) {
        phoneLeaveAction = action
    }

    /** Close live phone client, finish activity, clear session. */
    fun leavePhone() {
        val leave = phoneLeaveAction
        phoneLeaveAction = null
        leave?.invoke()
        if (role == Role.PHONE) {
            clear()
        }
    }

    fun clear() {
        role = null
        token = null
        shortCode = null
        wsUrl = null
        phoneConnected = false
        phoneLeaveAction = null
    }

    fun isActive(): Boolean = role != null && (!token.isNullOrBlank() || !shortCode.isNullOrBlank())

    fun isHost(): Boolean = role == Role.TV_HOST

    fun isPhone(): Boolean = role == Role.PHONE
}
