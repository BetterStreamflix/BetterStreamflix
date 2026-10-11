package com.dskja.betterstreamflix.sync

import android.content.Context
import android.util.Log
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.UserPreferences
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

object WatchPartyRepository {
    private const val TAG = "WatchParty"
    private const val TABLE = "watch_parties"
    private const val RPC_GET = "get_watch_party"
    private const val RPC_LOBBY = "get_watch_party_lobby"
    private const val RPC_JOIN_LOBBY = "join_watch_party_lobby"
    private const val RPC_MEMBER_HB = "heartbeat_watch_party_member"
    private const val RPC_POST_MSG = "post_watch_party_message"
    private const val RPC_LIST_MSG = "list_watch_party_messages"
    private const val RPC_START = "start_watch_party"
    /** Short TTL — parties are ephemeral social sessions, not long-lived shares. */
    private const val TOKEN_TTL_HOURS = 12L

    data class HostCreateResult(
        val token: String,
        val link: String,
        val partyId: String?,
        val inLobby: Boolean,
    )

    suspend fun createParty(
        context: Context,
        videoType: Video.Type,
        server: Video.Server?,
        positionMs: Long,
    ): HostCreateResult {
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: error("Sign in to host a Watch Party")

        val provider = UserPreferences.currentProvider?.name
            ?: error("Select a provider before hosting a Watch Party")

        // End any previous active party for this host (one party at a time).
        endOwnedActiveParties(appContext, profileId, userId)

        val token = newShareToken()
        val row = buildRow(
            shareToken = token,
            hostUserId = userId,
            provider = provider,
            videoType = videoType,
            server = server,
            positionMs = positionMs.coerceAtLeast(0L),
        )
        val inserted = client.from(TABLE)
            .insert(row) { select(Columns.ALL) }
            .decodeSingle<RemoteWatchParty>()

        // Register host in lobby presence.
        runCatching {
            joinLobby(
                context = appContext,
                token = inserted.shareToken,
                isHost = true,
            )
        }.onFailure { Log.w(TAG, "Host lobby join failed", it) }

        return HostCreateResult(
            token = inserted.shareToken,
            link = WatchPartyLinks.buildUri(inserted.shareToken),
            partyId = inserted.id,
            inLobby = inserted.inLobby,
        )
    }

    suspend fun heartbeat(
        context: Context,
        partyId: String,
        server: Video.Server?,
        positionMs: Long,
        playing: Boolean,
    ) {
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull()?.user?.id == null) return

        client.from(TABLE)
            .update(
                RemoteWatchPartyHeartbeat(
                    serverId = server?.id,
                    serverName = server?.name,
                    positionMs = positionMs.coerceAtLeast(0L),
                    playing = playing,
                    updatedAt = Instant.now().toString(),
                ),
            ) {
                filter {
                    eq("id", partyId)
                    eq("ended", false)
                }
            }
    }

    /** Host picks the server everyone will use (lobby or during play). */
    suspend fun pickServer(
        context: Context,
        partyId: String,
        server: Video.Server,
    ) {
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull()?.user?.id == null) {
            error("Sign in to pick a Watch Party server")
        }
        client.from(TABLE)
            .update(
                RemoteWatchPartyServerPick(
                    serverId = server.id,
                    serverName = server.name,
                    updatedAt = Instant.now().toString(),
                ),
            ) {
                filter {
                    eq("id", partyId)
                    eq("ended", false)
                }
            }
    }

    suspend fun startParty(
        context: Context,
        token: String,
        server: Video.Server?,
    ): WatchPartyPayload {
        requireConfigured()
        if (!WatchPartyLinks.isValidToken(token)) error("Invalid share token")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull()?.user?.id == null) {
            error("Sign in to start a Watch Party")
        }
        val payload = runCatching {
            client.postgrest.rpc(
                RPC_START,
                WatchPartyStartParam(
                    token = token.trim(),
                    serverId = server?.id,
                    serverName = server?.name,
                ),
            ).decodeAs<WatchPartyPayload>()
        }.getOrNull()
        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Could not start Watch Party")
        }
        return payload
    }

    suspend fun endParty(context: Context, partyId: String?) {
        if (partyId.isNullOrBlank()) return
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull()?.user?.id == null) return

        runCatching {
            client.from(TABLE)
                .update(
                    RemoteWatchPartyEndUpdate(
                        ended = true,
                        playing = false,
                        updatedAt = Instant.now().toString(),
                    ),
                ) {
                    filter { eq("id", partyId) }
                }
        }.onFailure { Log.w(TAG, "Could not end watch party", it) }
    }

    suspend fun fetchByToken(context: Context, token: String): WatchPartyPayload {
        requireConfigured()
        if (!WatchPartyLinks.isValidToken(token)) {
            error("Invalid share token")
        }
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before joining a Watch Party")
        client.auth.awaitInitialization()

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_GET,
                WatchPartyTokenParam(token.trim()),
            ).decodeAs<WatchPartyPayload>()
        }.getOrNull()

        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Watch Party not found or ended")
        }
        if (payload.ended) error("Watch Party ended")
        return payload
    }

    suspend fun joinLobby(
        context: Context,
        token: String,
        isHost: Boolean = false,
    ): WatchPartyLobbyPayload {
        requireConfigured()
        if (!WatchPartyLinks.isValidToken(token)) error("Invalid share token")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before joining a Watch Party")
        client.auth.awaitInitialization()

        val lobby = runCatching {
            client.postgrest.rpc(
                RPC_JOIN_LOBBY,
                WatchPartyLobbyJoinParam(
                    token = token.trim(),
                    memberKey = WatchPartyMemberIdentity.memberKey(appContext),
                    displayName = WatchPartyMemberIdentity.displayName(),
                    isHost = isHost,
                ),
            ).decodeAs<WatchPartyLobbyPayload>()
        }.getOrNull()
        if (lobby == null || lobby.party.shareToken.isNullOrBlank()) {
            error("Watch Party not found or ended")
        }
        if (lobby.party.ended) error("Watch Party ended")
        return lobby
    }

    suspend fun fetchLobby(context: Context, token: String): WatchPartyLobbyPayload {
        requireConfigured()
        if (!WatchPartyLinks.isValidToken(token)) error("Invalid share token")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before joining a Watch Party")
        client.auth.awaitInitialization()

        val lobby = runCatching {
            client.postgrest.rpc(
                RPC_LOBBY,
                WatchPartyTokenParam(token.trim()),
            ).decodeAs<WatchPartyLobbyPayload>()
        }.getOrNull()
        if (lobby == null || lobby.party.shareToken.isNullOrBlank()) {
            error("Watch Party not found or ended")
        }
        return lobby
    }

    suspend fun heartbeatMember(context: Context, token: String) {
        if (!WatchPartyLinks.isValidToken(token)) return
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId) ?: return
        client.auth.awaitInitialization()
        runCatching {
            client.postgrest.rpc(
                RPC_MEMBER_HB,
                WatchPartyMemberHeartbeatParam(
                    token = token.trim(),
                    memberKey = WatchPartyMemberIdentity.memberKey(appContext),
                ),
            )
        }.onFailure { Log.w(TAG, "Member heartbeat failed", it) }
    }

    suspend fun postMessage(
        context: Context,
        token: String,
        kind: String,
        body: String,
    ): WatchPartyMessagePayload {
        requireConfigured()
        if (!WatchPartyLinks.isValidToken(token)) error("Invalid share token")
        val kindClean = kind.trim().lowercase()
        val bodyClean = when (kindClean) {
            WatchPartyChat.KIND_REACT -> {
                if (!WatchPartyChat.isAllowedReaction(body)) error("Unsupported reaction")
                body.trim()
            }
            WatchPartyChat.KIND_CHAT -> {
                WatchPartyChat.sanitizeChatBody(body) ?: error("Empty message")
            }
            else -> error("Unsupported message kind")
        }
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before chatting in a Watch Party")
        client.auth.awaitInitialization()

        val posted = runCatching {
            client.postgrest.rpc(
                RPC_POST_MSG,
                WatchPartyMessagePostParam(
                    token = token.trim(),
                    memberKey = WatchPartyMemberIdentity.memberKey(appContext),
                    displayName = WatchPartyMemberIdentity.displayName(),
                    kind = kindClean,
                    body = bodyClean,
                ),
            ).decodeAs<WatchPartyMessagePayload>()
        }.getOrNull()
        if (posted == null || posted.body.isBlank()) {
            error("Could not send message")
        }
        return posted
    }

    suspend fun listMessages(
        context: Context,
        token: String,
        after: String? = null,
    ): List<WatchPartyMessagePayload> {
        requireConfigured()
        if (!WatchPartyLinks.isValidToken(token)) return emptyList()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId) ?: return emptyList()
        client.auth.awaitInitialization()

        return runCatching {
            client.postgrest.rpc(
                RPC_LIST_MSG,
                WatchPartyMessagesListParam(
                    token = token.trim(),
                    after = after,
                ),
            ).decodeAs<List<WatchPartyMessagePayload>>()
        }.getOrElse {
            Log.w(TAG, "List messages failed", it)
            emptyList()
        }
    }

    suspend fun flushStalePending(context: Context): Int {
        val appContext = context.applicationContext
        if (!SupabaseProvider.isConfigured) return 0
        var dropped = 0
        for (token in WatchPartyPendingJoin.peek(appContext)) {
            if (!WatchPartyLinks.isValidToken(token)) {
                WatchPartyPendingJoin.acknowledge(appContext, token)
                dropped++
                continue
            }
            val outcome = runCatching { fetchByToken(appContext, token) }
            outcome.onFailure { error ->
                val msg = error.message.orEmpty()
                if (msg.contains("not found", ignoreCase = true) ||
                    msg.contains("ended", ignoreCase = true) ||
                    msg.contains("Invalid", ignoreCase = true)
                ) {
                    WatchPartyPendingJoin.acknowledge(appContext, token)
                    dropped++
                } else {
                    Log.w(TAG, "Pending party readiness check failed", error)
                }
            }
        }
        return dropped
    }

    fun toVideoType(payload: WatchPartyPayload): Video.Type {
        return when (payload.mediaType) {
            "movie" -> Video.Type.Movie(
                id = payload.mediaId,
                title = payload.title.ifBlank { "Movie" },
                releaseDate = payload.releaseDate.orEmpty(),
                poster = payload.poster.orEmpty(),
                imdbId = payload.imdbId,
            )
            "episode" -> Video.Type.Episode(
                id = payload.mediaId,
                number = payload.episodeNumber ?: 1,
                title = payload.episodeTitle ?: payload.title.takeIf { it.isNotBlank() },
                poster = payload.poster,
                overview = null,
                tvShow = Video.Type.Episode.TvShow(
                    id = payload.showId ?: payload.mediaId,
                    title = payload.showTitle ?: payload.title.ifBlank { "Show" },
                    poster = payload.showPoster ?: payload.poster,
                    banner = null,
                    releaseDate = payload.releaseDate,
                    imdbId = payload.imdbId,
                ),
                season = Video.Type.Episode.Season(
                    number = payload.seasonNumber ?: 1,
                    title = payload.seasonTitle,
                ),
            )
            else -> error("Unsupported media type: ${payload.mediaType}")
        }
    }

    private suspend fun endOwnedActiveParties(
        context: Context,
        profileId: String,
        userId: String,
    ) {
        val client = SupabaseProvider.clientFor(context, profileId)
        runCatching {
            client.from(TABLE)
                .update(
                    RemoteWatchPartyEndUpdate(
                        ended = true,
                        playing = false,
                        updatedAt = Instant.now().toString(),
                    ),
                ) {
                    filter {
                        eq("host_user_id", userId)
                        eq("ended", false)
                    }
                }
        }.onFailure { Log.w(TAG, "Could not end previous watch parties", it) }
    }

    private fun buildRow(
        shareToken: String,
        hostUserId: String,
        provider: String,
        videoType: Video.Type,
        server: Video.Server?,
        positionMs: Long,
    ): RemoteWatchParty {
        val expiresAt = Instant.now().plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS).toString()
        return when (videoType) {
            is Video.Type.Movie -> RemoteWatchParty(
                shareToken = shareToken,
                hostUserId = hostUserId,
                provider = provider,
                mediaType = "movie",
                mediaId = videoType.id,
                title = videoType.title,
                subtitle = "",
                poster = videoType.poster.takeIf { it.isNotBlank() },
                releaseDate = videoType.releaseDate.takeIf { it.isNotBlank() },
                imdbId = videoType.imdbId,
                serverId = server?.id,
                serverName = server?.name,
                positionMs = positionMs,
                playing = false,
                inLobby = true,
                ended = false,
                expiresAt = expiresAt,
            )
            is Video.Type.Episode -> RemoteWatchParty(
                shareToken = shareToken,
                hostUserId = hostUserId,
                provider = provider,
                mediaType = "episode",
                mediaId = videoType.id,
                title = videoType.tvShow.title,
                subtitle = "S${videoType.season.number} E${videoType.number}",
                poster = videoType.poster ?: videoType.tvShow.poster,
                showId = videoType.tvShow.id,
                showTitle = videoType.tvShow.title,
                showPoster = videoType.tvShow.poster,
                seasonNumber = videoType.season.number,
                seasonTitle = videoType.season.title,
                episodeNumber = videoType.number,
                episodeTitle = videoType.title,
                releaseDate = videoType.tvShow.releaseDate,
                imdbId = videoType.tvShow.imdbId,
                serverId = server?.id,
                serverName = server?.name,
                positionMs = positionMs,
                playing = false,
                inLobby = true,
                ended = false,
                expiresAt = expiresAt,
            )
        }
    }

    private fun newShareToken(): String =
        UUID.randomUUID().toString().replace("-", "")

    private fun requireConfigured() {
        if (!SupabaseProvider.isConfigured) {
            error("Configure Supabase before using Watch Party")
        }
    }
}
