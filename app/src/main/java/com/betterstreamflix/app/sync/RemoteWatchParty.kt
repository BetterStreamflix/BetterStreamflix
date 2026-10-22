package com.betterstreamflix.app.sync

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemoteWatchParty(
    val id: String? = null,
    @SerialName("share_token") val shareToken: String,
    @SerialName("short_code") val shortCode: String? = null,
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
    @EncodeDefault val playing: Boolean = false,
    @EncodeDefault @SerialName("in_lobby") val inLobby: Boolean = true,
    @EncodeDefault val ended: Boolean = false,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @EncodeDefault @SerialName("display_mode") val displayMode: String = WatchTogetherPro.MODE_SYNC_ALL,
    @SerialName("sidekick_seek_ms") val sidekickSeekMs: Long? = null,
    @EncodeDefault @SerialName("sidekick_seek_seq") val sidekickSeekSeq: Long = 0L,
    @SerialName("sidekick_playing") val sidekickPlaying: Boolean? = null,
    @EncodeDefault @SerialName("sidekick_play_seq") val sidekickPlaySeq: Long = 0L,
    @EncodeDefault @SerialName("sync_revision") val syncRevision: Long = 0L,
    @EncodeDefault @SerialName("host_controls_locked") val hostControlsLocked: Boolean = false,
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
data class RemoteWatchPartyServerPick(
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("server_name") val serverName: String? = null,
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

@Serializable
data class WatchPartyLobbyJoinParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_member_key") val memberKey: String,
    @SerialName("p_display_name") val displayName: String,
    @SerialName("p_is_host") val isHost: Boolean = false,
)

@Serializable
data class WatchPartyMemberHeartbeatParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_member_key") val memberKey: String,
)

@Serializable
data class WatchPartyMessagePostParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_member_key") val memberKey: String,
    @SerialName("p_display_name") val displayName: String,
    @SerialName("p_kind") val kind: String,
    @SerialName("p_body") val body: String,
)

@Serializable
data class WatchPartyMessagesListParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_after") val after: String? = null,
)

@Serializable
data class WatchPartyStartParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_server_id") val serverId: String? = null,
    @SerialName("p_server_name") val serverName: String? = null,
)

@Serializable
data class WatchPartyCodeParam(
    @SerialName("p_code") val code: String,
)

@Serializable
data class WatchPartySidekickSeekParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_position_ms") val positionMs: Long,
    @SerialName("p_member_key") val memberKey: String? = null,
)

@Serializable
data class WatchPartySidekickPlayParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_playing") val playing: Boolean,
    @SerialName("p_member_key") val memberKey: String? = null,
)

@Serializable
data class WatchPartyLeaveParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_member_key") val memberKey: String,
)

@Serializable
data class WatchPartyForceResyncParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_position_ms") val positionMs: Long,
)

@Serializable
data class WatchPartyDisplayModeParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_mode") val mode: String,
)

@Serializable
data class WatchPartyControlsLockedParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_locked") val locked: Boolean,
)

@Serializable
data class WatchPartyKickParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_member_key") val memberKey: String,
)

@Serializable
data class WatchPartyDriftReportParam(
    @SerialName("p_token") val token: String,
    @SerialName("p_member_key") val memberKey: String,
    @SerialName("p_drift_ms") val driftMs: Long,
)

/** Guest-facing snapshot returned by get_watch_party (no host_user_id). */
@Serializable
data class WatchPartyPayload(
    val id: String? = null,
    @SerialName("share_token") val shareToken: String? = null,
    @SerialName("short_code") val shortCode: String? = null,
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
    val playing: Boolean = false,
    @SerialName("in_lobby") val inLobby: Boolean = false,
    val ended: Boolean = false,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("display_mode") val displayMode: String = WatchTogetherPro.MODE_SYNC_ALL,
    @SerialName("sidekick_seek_ms") val sidekickSeekMs: Long? = null,
    @SerialName("sidekick_seek_seq") val sidekickSeekSeq: Long = 0L,
    @SerialName("sidekick_playing") val sidekickPlaying: Boolean? = null,
    @SerialName("sidekick_play_seq") val sidekickPlaySeq: Long = 0L,
    @SerialName("sync_revision") val syncRevision: Long = 0L,
    @SerialName("host_controls_locked") val hostControlsLocked: Boolean = false,
)

@Serializable
data class WatchPartyMemberPayload(
    @SerialName("member_key") val memberKey: String? = null,
    @SerialName("display_name") val displayName: String = "Guest",
    @SerialName("is_host") val isHost: Boolean = false,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
data class WatchPartyLobbyPayload(
    val party: WatchPartyPayload,
    val members: List<WatchPartyMemberPayload> = emptyList(),
)

@Serializable
data class WatchPartyMessagePayload(
    val id: String? = null,
    @SerialName("member_key") val memberKey: String? = null,
    @SerialName("display_name") val displayName: String = "Guest",
    val kind: String = "chat",
    val body: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class WatchPartySyncReportPayload(
    @SerialName("member_key") val memberKey: String? = null,
    @SerialName("drift_ms") val driftMs: Long = 0L,
    @SerialName("reported_at") val reportedAt: String? = null,
)

@Serializable
data class WatchPartySyncHealthPayload(
    @SerialName("sync_revision") val syncRevision: Long = 0L,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("display_mode") val displayMode: String = WatchTogetherPro.MODE_SYNC_ALL,
    @SerialName("host_controls_locked") val hostControlsLocked: Boolean = false,
    @SerialName("max_drift_ms") val maxDriftMs: Long = 0L,
    @SerialName("stale_reports") val staleReports: Int = 0,
    @SerialName("member_count") val memberCount: Int = 0,
    val reports: List<WatchPartySyncReportPayload> = emptyList(),
)
