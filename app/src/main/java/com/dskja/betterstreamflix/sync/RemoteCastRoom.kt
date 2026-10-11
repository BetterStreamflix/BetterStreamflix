package com.dskja.betterstreamflix.sync

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemoteCastRoom(
    val id: String? = null,
    @SerialName("share_token") val shareToken: String,
    @SerialName("short_code") val shortCode: String,
    @SerialName("host_user_id") val hostUserId: String? = null,
    val provider: String,
    @SerialName("now_playing_index") val nowPlayingIndex: Int = 0,
    @EncodeDefault val playing: Boolean = false,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @EncodeDefault val revision: Long = 1L,
    @EncodeDefault val ended: Boolean = false,
    @SerialName("sidekick_seek_ms") val sidekickSeekMs: Long? = null,
    @SerialName("sidekick_seek_seq") val sidekickSeekSeq: Long = 0L,
    @SerialName("sidekick_text") val sidekickText: String? = null,
    @SerialName("sidekick_text_seq") val sidekickTextSeq: Long = 0L,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class RemoteCastRoomEndUpdate(
    val ended: Boolean = true,
    val playing: Boolean = false,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class RemoteCastRoomHostHeartbeat(
    @SerialName("position_ms") val positionMs: Long,
    val playing: Boolean,
    @SerialName("now_playing_index") val nowPlayingIndex: Int,
    @SerialName("updated_at") val updatedAt: String,
)

/** Heartbeat without position — used while a Sidekick seek is still unacked (CC-010). */
@Serializable
data class RemoteCastRoomHostHeartbeatMeta(
    val playing: Boolean,
    @SerialName("now_playing_index") val nowPlayingIndex: Int,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CastRoomTokenParam(
    @SerialName("p_token") val token: String,
)

@Serializable
data class CastRoomAckSidekickTextParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_seq") val seq: Long,
)

@Serializable
data class CastRoomCodeParam(
    @SerialName("p_code") val code: String,
)

@Serializable
data class CastRoomItemIdParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_item_id") val itemId: String,
)

@Serializable
data class CastRoomReorderParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_item_ids") val itemIds: List<String>,
)

@Serializable
data class CastRoomPlayAtParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_index") val index: Int,
)

@Serializable
data class CastRoomSetPlayingParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_playing") val playing: Boolean,
)

@Serializable
data class CastRoomSeekParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_position_ms") val positionMs: Long,
)

@Serializable
data class CastRoomSidekickTextParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_text") val text: String,
)

@Serializable
data class CastRoomPostMessageParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_member_key") val memberKey: String,
    @SerialName("p_display_name") val displayName: String,
    @SerialName("p_kind") val kind: String,
    @SerialName("p_body") val body: String,
)

@Serializable
data class CastRoomListMessagesParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_limit") val limit: Int = 40,
)

@Serializable
data class CastRoomMessagePayload(
    val id: String? = null,
    @SerialName("member_key") val memberKey: String = "",
    @SerialName("display_name") val displayName: String = "Guest",
    val kind: String = TvSidekick.KIND_CHAT,
    val body: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class CastRoomAddItemParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_media_type") val mediaType: String,
    @SerialName("p_media_id") val mediaId: String,
    @SerialName("p_title") val title: String = "",
    @SerialName("p_subtitle") val subtitle: String = "",
    @SerialName("p_poster") val poster: String? = null,
    @SerialName("p_show_id") val showId: String? = null,
    @SerialName("p_show_title") val showTitle: String? = null,
    @SerialName("p_show_poster") val showPoster: String? = null,
    @SerialName("p_season_number") val seasonNumber: Int? = null,
    @SerialName("p_season_title") val seasonTitle: String? = null,
    @SerialName("p_episode_number") val episodeNumber: Int? = null,
    @SerialName("p_episode_title") val episodeTitle: String? = null,
    @SerialName("p_release_date") val releaseDate: String? = null,
    @SerialName("p_imdb_id") val imdbId: String? = null,
    @SerialName("p_preferred_server_id") val preferredServerId: String? = null,
    @SerialName("p_preferred_server_name") val preferredServerName: String? = null,
)

@Serializable
data class CastRoomItemPayload(
    val id: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
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
    @SerialName("preferred_server_id") val preferredServerId: String? = null,
    @SerialName("preferred_server_name") val preferredServerName: String? = null,
)

/** Companion-facing snapshot from get_cast_room (no host_user_id). */
@Serializable
data class CastRoomPayload(
    val id: String? = null,
    @SerialName("share_token") val shareToken: String? = null,
    @SerialName("short_code") val shortCode: String? = null,
    val provider: String,
    @SerialName("now_playing_index") val nowPlayingIndex: Int = 0,
    val playing: Boolean = false,
    @SerialName("position_ms") val positionMs: Long = 0L,
    val revision: Long = 1L,
    val ended: Boolean = false,
    @SerialName("sidekick_seek_ms") val sidekickSeekMs: Long? = null,
    @SerialName("sidekick_seek_seq") val sidekickSeekSeq: Long = 0L,
    @SerialName("sidekick_text") val sidekickText: String? = null,
    @SerialName("sidekick_text_seq") val sidekickTextSeq: Long = 0L,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val items: List<CastRoomItemPayload> = emptyList(),
)
