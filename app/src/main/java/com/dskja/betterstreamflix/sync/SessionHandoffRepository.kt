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

object SessionHandoffRepository {
    private const val TAG = "SessionHandoff"
    private const val TABLE = "session_handoffs"
    private const val RPC_GET = "get_session_handoff"
    private const val RPC_GET_CODE = "get_session_handoff_by_code"
    private const val RPC_CLAIM = "claim_session_handoff"
    /** Short-lived offer — phone shows a code, TV claims quickly. */
    private const val TOKEN_TTL_MINUTES = 15L

    data class OfferResult(
        val token: String,
        val shortCode: String,
        val handoffId: String?,
        val link: String,
    )

    suspend fun createOffer(
        context: Context,
        videoType: Video.Type,
        server: Video.Server?,
        positionMs: Long,
    ): OfferResult {
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: error("Sign in to send a session to TV")

        val provider = UserPreferences.currentProvider?.name
            ?: error("Select a provider before sending to TV")

        endOwnedActiveOffers(appContext, profileId, userId)

        val token = newShareToken()
        val inserted = CompanionShortCodes.mintUnique { shortCode ->
            val row = buildRow(
                shareToken = token,
                shortCode = shortCode,
                hostUserId = userId,
                provider = provider,
                videoType = videoType,
                server = server,
                positionMs = positionMs.coerceAtLeast(0L),
            )
            runCatching {
                client.from(TABLE)
                    .insert(row) { select(Columns.ALL) }
                    .decodeSingle<RemoteSessionHandoff>()
            }.getOrElse { error ->
                val msg = error.message.orEmpty()
                if (msg.contains("short_code", ignoreCase = true) ||
                    msg.contains("unique", ignoreCase = true) ||
                    msg.contains("23505")
                ) {
                    null
                } else {
                    throw error
                }
            }
        }

        return OfferResult(
            token = inserted.shareToken,
            shortCode = inserted.shortCode,
            handoffId = inserted.id,
            link = SessionHandoffLinks.buildCodeUri(inserted.shortCode),
        )
    }

    suspend fun updateProgress(
        context: Context,
        handoffId: String,
        server: Video.Server?,
        positionMs: Long,
    ) {
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull()?.user?.id == null) return

        client.from(TABLE)
            .update(
                RemoteSessionHandoffProgressUpdate(
                    serverId = server?.id,
                    serverName = server?.name,
                    positionMs = positionMs.coerceAtLeast(0L),
                    updatedAt = Instant.now().toString(),
                ),
            ) {
                filter {
                    eq("id", handoffId)
                    eq("ended", false)
                }
            }
    }

    suspend fun endOffer(context: Context, handoffId: String?) {
        if (handoffId.isNullOrBlank()) return
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull()?.user?.id == null) return

        runCatching {
            client.from(TABLE)
                .update(
                    RemoteSessionHandoffEndUpdate(
                        ended = true,
                        updatedAt = Instant.now().toString(),
                    ),
                ) {
                    filter { eq("id", handoffId) }
                }
        }.onFailure { Log.w(TAG, "Could not end session handoff", it) }
    }

    suspend fun fetchByToken(context: Context, token: String): SessionHandoffPayload {
        requireConfigured()
        if (!SessionHandoffLinks.isValidToken(token)) error("Invalid handoff token")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before using Session Handoff")
        client.auth.awaitInitialization()

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_GET,
                SessionHandoffTokenParam(token.trim()),
            ).decodeAs<SessionHandoffPayload>()
        }.getOrNull()
        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Handoff not found or expired")
        }
        return payload
    }

    suspend fun fetchByCode(context: Context, code: String): SessionHandoffPayload {
        requireConfigured()
        val cleaned = SessionHandoffCodes.normalize(code) ?: error("Invalid handoff code")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before using Session Handoff")
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull()?.user?.id == null) {
            error("Sign in to peek a session handoff")
        }

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_GET_CODE,
                SessionHandoffCodeParam(cleaned),
            ).decodeAs<SessionHandoffPayload>()
        }.getOrNull()
        // Peek JSON intentionally omits share_token — validate via media identity.
        if (payload == null || payload.mediaId.isBlank()) {
            error("Handoff not found or already claimed")
        }
        if (payload.ended || payload.claimed) error("Handoff already claimed")
        return payload
    }

    suspend fun claimByCode(context: Context, code: String): SessionHandoffPayload {
        requireConfigured()
        val cleaned = SessionHandoffCodes.normalize(code) ?: error("Invalid handoff code")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before claiming a handoff")
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull()?.user?.id == null) {
            error("Sign in on TV to continue watching")
        }

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_CLAIM,
                SessionHandoffCodeParam(cleaned),
            ).decodeAs<SessionHandoffPayload>()
        }.getOrNull()
        if (payload == null || payload.mediaId.isBlank()) {
            error("Handoff not found or already claimed")
        }
        return payload
    }

    fun toVideoType(payload: SessionHandoffPayload): Video.Type {
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

    private suspend fun endOwnedActiveOffers(
        context: Context,
        profileId: String,
        userId: String,
    ) {
        val client = SupabaseProvider.clientFor(context, profileId)
        runCatching {
            client.from(TABLE)
                .update(
                    RemoteSessionHandoffEndUpdate(
                        ended = true,
                        updatedAt = Instant.now().toString(),
                    ),
                ) {
                    filter {
                        eq("host_user_id", userId)
                        eq("ended", false)
                    }
                }
        }.onFailure { Log.w(TAG, "Could not end previous handoffs", it) }
    }

    private fun buildRow(
        shareToken: String,
        shortCode: String,
        hostUserId: String,
        provider: String,
        videoType: Video.Type,
        server: Video.Server?,
        positionMs: Long,
    ): RemoteSessionHandoff = when (videoType) {
        is Video.Type.Movie -> RemoteSessionHandoff(
            shareToken = shareToken,
            shortCode = shortCode,
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
            expiresAt = Instant.now().plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES).toString(),
        )
        is Video.Type.Episode -> RemoteSessionHandoff(
            shareToken = shareToken,
            shortCode = shortCode,
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
            expiresAt = Instant.now().plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES).toString(),
        )
    }

    private fun newShareToken(): String =
        UUID.randomUUID().toString().replace("-", "")

    private fun requireConfigured() {
        if (!SupabaseProvider.isConfigured) {
            error("Configure Supabase before using Session Handoff")
        }
    }
}
