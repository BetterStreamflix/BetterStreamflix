package com.betterstreamflix.app.sync

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemoteSessionHandoff(
    val id: String? = null,
    @SerialName("share_token") val shareToken: String,
    @SerialName("short_code") val shortCode: String,
    @SerialName("host_user_id") val hostUserId: String? = null,
    val provider: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("media_id") val mediaId: String,
    @EncodeDefault val title: String = "",
    @EncodeDefault val subtitle: String = "",
    @EncodeDefault val poster: String? = null,
    @SerialName("show_id") val showId: String? = null,
    @SerialName("show_title") val showTitle: String? = null,
    @SerialName("show_poster") val showPoster: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("season_title") val seasonTitle: String? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("server_name") val serverName: String? = null,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("claimed_by") val claimedBy: String? = null,
    @SerialName("claimed_at") val claimedAt: String? = null,
    @EncodeDefault val ended: Boolean = false,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class RemoteSessionHandoffProgressUpdate(
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("server_name") val serverName: String? = null,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class RemoteSessionHandoffEndUpdate(
    val ended: Boolean = true,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class SessionHandoffCodeParam(
    @SerialName("p_code") val code: String,
)

@Serializable
data class SessionHandoffTokenParam(
    @SerialName("p_token") val token: String,
)

@Serializable
data class SessionHandoffPayload(
    val id: String? = null,
    @SerialName("share_token") val shareToken: String? = null,
    @SerialName("short_code") val shortCode: String? = null,
    val provider: String = "",
    @SerialName("media_type") val mediaType: String = "",
    @SerialName("media_id") val mediaId: String = "",
    val title: String = "",
    val subtitle: String = "",
    val poster: String? = null,
    @SerialName("show_id") val showId: String? = null,
    @SerialName("show_title") val showTitle: String? = null,
    @SerialName("show_poster") val showPoster: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("season_title") val seasonTitle: String? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("server_name") val serverName: String? = null,
    @SerialName("position_ms") val positionMs: Long = 0L,
    val claimed: Boolean = false,
    val ended: Boolean = false,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)
