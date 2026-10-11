package com.dskja.betterstreamflix.sync

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemoteWatchParty(
    val id: String? = null,
    @SerialName("share_token") val shareToken: String,
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
    @EncodeDefault val playing: Boolean = true,
    @EncodeDefault val ended: Boolean = false,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class RemoteWatchPartyHeartbeat(
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("server_name") val serverName: String? = null,
    @SerialName("position_ms") val positionMs: Long,
    val playing: Boolean,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class RemoteWatchPartyEndUpdate(
    val ended: Boolean = true,
    val playing: Boolean = false,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class WatchPartyTokenParam(
    @SerialName("p_token") val token: String,
)

/** Guest-facing snapshot returned by get_watch_party (no host_user_id). */
@Serializable
data class WatchPartyPayload(
    val id: String? = null,
    @SerialName("share_token") val shareToken: String? = null,
    val provider: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("media_id") val mediaId: String,
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
    val playing: Boolean = true,
    val ended: Boolean = false,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)
