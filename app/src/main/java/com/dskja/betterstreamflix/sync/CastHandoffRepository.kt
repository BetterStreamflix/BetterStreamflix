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

object CastHandoffRepository {
    private const val TAG = "CastHandoff"
    private const val TABLE = "cast_handoffs"
    private const val RPC_GET = "get_cast_handoff_by_code"
    private const val RPC_CLAIM = "claim_cast_handoff"
    /** Short TTL — hand-offs are one-shot continue-on-phone sessions. */
    private const val CODE_TTL_MINUTES = 30L

    data class OfferResult(
        val shortCode: String,
        val link: String,
        val handoffId: String?,
    )

    suspend fun offer(
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
            ?: error("Sign in to offer a Cast Hand-off")

        val provider = UserPreferences.currentProvider?.name
            ?: error("Select a provider before offering a Cast Hand-off")

        revokeOwnedActive(appContext, profileId, userId)

        val inserted = CompanionShortCodes.mintUnique { shortCode ->
            val row = buildRow(
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
                    .decodeSingle<RemoteCastHandoff>()
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
            shortCode = inserted.shortCode,
            link = CastHandoffLinks.buildUri(inserted.shortCode),
            handoffId = inserted.id,
        )
    }

    suspend fun refreshPosition(
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
                RemoteCastHandoffPositionUpdate(
                    serverId = server?.id,
                    serverName = server?.name,
                    positionMs = positionMs.coerceAtLeast(0L),
                    updatedAt = Instant.now().toString(),
                ),
            ) {
                filter {
                    eq("id", handoffId)
                    eq("revoked", false)
                    // PostgREST IS NULL — do not refresh after claim.
                    exact("claimed_at", null as Boolean?)
                }
            }
    }

    suspend fun revoke(context: Context, handoffId: String?) {
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
                    RemoteCastHandoffRevokeUpdate(
                        revoked = true,
                        updatedAt = Instant.now().toString(),
                    ),
                ) {
                    filter { eq("id", handoffId) }
                }
        }.onFailure { Log.w(TAG, "Could not revoke cast hand-off", it) }
    }

    suspend fun peekByCode(context: Context, code: String): CastHandoffPayload {
        requireConfigured()
        val cleaned = CastHandoffLinks.normalizeShortCode(code)
            ?: error("Invalid Cast Hand-off code")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before claiming a Cast Hand-off")
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull()?.user?.id == null) {
            error("Sign in to peek a Cast Hand-off")
        }

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_GET,
                CastHandoffCodeParam(cleaned),
            ).decodeAs<CastHandoffPayload>()
        }.getOrNull()

        if (payload == null || payload.shortCode.isNullOrBlank()) {
            error("Cast Hand-off not found, claimed, or expired")
        }
        if (payload.revoked) error("Cast Hand-off revoked")
        return payload
    }

    suspend fun claim(context: Context, code: String): CastHandoffPayload {
        requireConfigured()
        val cleaned = CastHandoffLinks.normalizeShortCode(code)
            ?: error("Invalid Cast Hand-off code")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before claiming a Cast Hand-off")
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull()?.user?.id == null) {
            error("Sign in to claim a Cast Hand-off")
        }

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_CLAIM,
                CastHandoffCodeParam(cleaned),
            ).decodeAs<CastHandoffPayload>()
        }.getOrNull()

        if (payload == null || payload.shortCode.isNullOrBlank()) {
            error("Cast Hand-off not found, already claimed, or expired")
        }
        return payload
    }

    suspend fun flushStalePending(context: Context): Int {
        val appContext = context.applicationContext
        if (!SupabaseProvider.isConfigured) return 0
        var dropped = 0
        for (code in CastHandoffPendingClaim.peek(appContext)) {
            if (!CastHandoffLinks.isValidShortCode(code)) {
                CastHandoffPendingClaim.acknowledge(appContext, code)
                dropped++
                continue
            }
            val outcome = runCatching { peekByCode(appContext, code) }
            outcome.onFailure { error ->
                val msg = error.message.orEmpty()
                if (msg.contains("not found", ignoreCase = true) ||
                    msg.contains("claimed", ignoreCase = true) ||
                    msg.contains("expired", ignoreCase = true) ||
                    msg.contains("revoked", ignoreCase = true) ||
                    msg.contains("Invalid", ignoreCase = true)
                ) {
                    CastHandoffPendingClaim.acknowledge(appContext, code)
                    dropped++
                } else {
                    Log.w(TAG, "Pending hand-off readiness check failed", error)
                }
            }
        }
        return dropped
    }

    fun toVideoType(payload: CastHandoffPayload): Video.Type {
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

    fun formatPositionLabel(positionMs: Long): String {
        val totalSec = (positionMs.coerceAtLeast(0L) / 1000L).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%d:%02d", m, s)
        }
    }

    private suspend fun revokeOwnedActive(
        context: Context,
        profileId: String,
        userId: String,
    ) {
        val client = SupabaseProvider.clientFor(context, profileId)
        runCatching {
            client.from(TABLE)
                .update(
                    RemoteCastHandoffRevokeUpdate(
                        revoked = true,
                        updatedAt = Instant.now().toString(),
                    ),
                ) {
                    filter {
                        eq("host_user_id", userId)
                        eq("revoked", false)
                    }
                }
        }.onFailure { Log.w(TAG, "Could not revoke previous cast hand-offs", it) }
    }

    private fun buildRow(
        shortCode: String,
        hostUserId: String,
        provider: String,
        videoType: Video.Type,
        server: Video.Server?,
        positionMs: Long,
    ): RemoteCastHandoff {
        val expiresAt = Instant.now().plus(CODE_TTL_MINUTES, ChronoUnit.MINUTES).toString()
        return when (videoType) {
            is Video.Type.Movie -> RemoteCastHandoff(
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
                revoked = false,
                expiresAt = expiresAt,
            )
            is Video.Type.Episode -> RemoteCastHandoff(
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
                revoked = false,
                expiresAt = expiresAt,
            )
        }
    }

    private fun requireConfigured() {
        if (!SupabaseProvider.isConfigured) {
            error("Configure Supabase before using Cast Hand-off")
        }
    }
}
