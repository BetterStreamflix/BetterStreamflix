package com.dskja.betterstreamflix.sync

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemoteEpisodeNote(
    @SerialName("user_id") val userId: String,
    @SerialName("episode_id") val episodeId: String,
    @EncodeDefault val text: String = "",
    @SerialName("client_updated_at_millis") val clientUpdatedAtMillis: Long,
) {
    val queueKey: String
        get() = listOf(userId, "note", episodeId).joinToString("\u001f")
}
