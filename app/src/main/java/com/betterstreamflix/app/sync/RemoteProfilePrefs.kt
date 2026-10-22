package com.betterstreamflix.app.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RemoteProfilePrefs(
    @SerialName("user_id") val userId: String,
    val look: String,
    @SerialName("client_updated_at_millis") val clientUpdatedAtMillis: Long,
    @SerialName("leanback_density") val leanbackDensity: String? = null,
) {
    val queueKey: String
        get() = listOf(userId, "look").joinToString("\u001f")
}
