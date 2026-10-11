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
    private const val RPC = "get_watch_party"
    /** Short TTL — parties are ephemeral social sessions, not long-lived shares. */
    private const val TOKEN_TTL_HOURS = 12L

    data class HostCreateResult(
        val token: String,
        val link: String,
        val partyId: String?,
    )

    suspend fun createParty(
        context: Context,
        videoType: Video.Type,
        server: Video.Server?,
        positionMs: Long,
        playing: Boolean,
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
            playing = playing,
        )
        val inserted = client.from(TABLE)
            .insert(row) { select(Columns.ALL) }
            .decodeSingle<RemoteWatchParty>()

        return HostCreateResult(
            token = inserted.shareToken,
            link = WatchPartyLinks.buildUri(inserted.shareToken),
            partyId = inserted.id,
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
                RPC,
                WatchPartyTokenParam(token.trim()),
            ).decodeAs<WatchPartyPayload>()
        }.getOrNull()

        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Watch Party not found or ended")
        }
        if (payload.ended) error("Watch Party ended")
        return payload
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
        playing: Boolean,
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
                playing = playing,
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
                playing = playing,
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
