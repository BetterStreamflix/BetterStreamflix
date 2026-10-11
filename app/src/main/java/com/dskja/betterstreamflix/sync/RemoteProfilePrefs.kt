package com.dskja.betterstreamflix.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RemoteProfilePrefs(
    @SerialName("user_id") val userId: String,
    val look: String,
    @SerialName("client_updated_at_millis") val clientUpdatedAtMillis: Long,
) {
    val queueKey: String
        get() = listOf(userId, "look").joinToString("\u001f")
}
