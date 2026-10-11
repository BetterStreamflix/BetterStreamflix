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

object CastRoomRepository {
    private const val TAG = "CastRoom"
    private const val TABLE = "cast_rooms"
    private const val RPC_GET = "get_cast_room"
    private const val RPC_GET_CODE = "get_cast_room_by_code"
    private const val RPC_ADD = "cast_room_add_item"
    private const val RPC_REMOVE = "cast_room_remove_item"
    private const val RPC_REORDER = "cast_room_reorder"
    private const val RPC_PLAY_AT = "cast_room_play_at"
    private const val RPC_SKIP = "cast_room_skip"
    private const val RPC_SET_PLAYING = "cast_room_set_playing"
    private const val TOKEN_TTL_HOURS = 12L

    data class HostCreateResult(
        val token: String,
        val link: String,
        val shortCode: String,
        val roomId: String?,
    )

    suspend fun createRoom(context: Context): HostCreateResult {
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: error("Sign in to host a Cast Room")

        val provider = UserPreferences.currentProvider?.name
            ?: error("Select a provider before hosting a Cast Room")

        endOwnedActiveRooms(appContext, profileId, userId)

        val token = newShareToken()
        val shortCode = newUniqueShortCode()
        val row = RemoteCastRoom(
            shareToken = token,
            shortCode = shortCode,
            hostUserId = userId,
            provider = provider,
            nowPlayingIndex = 0,
            playing = false,
            positionMs = 0L,
            revision = 1L,
            ended = false,
            expiresAt = Instant.now().plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS).toString(),
        )
        val inserted = client.from(TABLE)
            .insert(row) { select(Columns.ALL) }
            .decodeSingle<RemoteCastRoom>()

        return HostCreateResult(
            token = inserted.shareToken,
            link = CastRoomLinks.buildUri(inserted.shareToken),
            shortCode = inserted.shortCode,
            roomId = inserted.id,
        )
    }

    suspend fun endRoom(context: Context, roomId: String?) {
        if (roomId.isNullOrBlank()) return
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull()?.user?.id == null) return

        runCatching {
            client.from(TABLE)
                .update(
                    RemoteCastRoomEndUpdate(
                        ended = true,
                        playing = false,
                        updatedAt = Instant.now().toString(),
                    ),
                ) {
                    filter { eq("id", roomId) }
                }
        }.onFailure { Log.w(TAG, "Could not end cast room", it) }
    }

    suspend fun hostHeartbeat(
        context: Context,
        roomId: String,
        nowPlayingIndex: Int,
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
                RemoteCastRoomHostHeartbeat(
                    positionMs = positionMs.coerceAtLeast(0L),
                    playing = playing,
                    nowPlayingIndex = nowPlayingIndex.coerceAtLeast(0),
                    updatedAt = Instant.now().toString(),
                ),
            ) {
                filter {
                    eq("id", roomId)
                    eq("ended", false)
                }
            }
    }

    suspend fun fetchByToken(context: Context, token: String): CastRoomPayload {
        requireConfigured()
        if (!CastRoomLinks.isValidToken(token)) {
            error("Invalid share token")
        }
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before joining a Cast Room")
        client.auth.awaitInitialization()

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_GET,
                CastRoomTokenParam(token.trim()),
            ).decodeAs<CastRoomPayload>()
        }.getOrNull()

        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Cast Room not found or ended")
        }
        if (payload.ended) error("Cast Room ended")
        return payload
    }

    suspend fun fetchByShortCode(context: Context, code: String): CastRoomPayload {
        requireConfigured()
        val cleaned = CastRoomLinks.normalizeShortCode(code)
            ?: error("Invalid Cast Room code")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before joining a Cast Room")
        client.auth.awaitInitialization()

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_GET_CODE,
                CastRoomCodeParam(cleaned),
            ).decodeAs<CastRoomPayload>()
        }.getOrNull()

        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Cast Room not found or ended")
        }
        if (payload.ended) error("Cast Room ended")
        return payload
    }

    suspend fun addItem(
        context: Context,
        token: String,
        videoType: Video.Type,
        server: Video.Server? = null,
    ): CastRoomPayload {
        requireConfigured()
        if (!CastRoomLinks.isValidToken(token)) error("Invalid share token")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before using Cast Room")
        client.auth.awaitInitialization()

        val param = when (videoType) {
            is Video.Type.Movie -> CastRoomAddItemParam(
                token = token.trim(),
                mediaType = "movie",
                mediaId = videoType.id,
                title = videoType.title,
                subtitle = "",
                poster = videoType.poster.takeIf { it.isNotBlank() },
                releaseDate = videoType.releaseDate.takeIf { it.isNotBlank() },
                imdbId = videoType.imdbId,
                preferredServerId = server?.id,
                preferredServerName = server?.name,
            )
            is Video.Type.Episode -> CastRoomAddItemParam(
                token = token.trim(),
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
                preferredServerId = server?.id,
                preferredServerName = server?.name,
            )
        }

        val payload = runCatching {
            client.postgrest.rpc(RPC_ADD, param).decodeAs<CastRoomPayload>()
        }.getOrNull()
        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Could not add to Cast Room queue")
        }
        return payload
    }

    suspend fun removeItem(context: Context, token: String, itemId: String): CastRoomPayload {
        requireConfigured()
        if (!CastRoomLinks.isValidToken(token) || itemId.isBlank()) {
            error("Invalid Cast Room item")
        }
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before using Cast Room")
        client.auth.awaitInitialization()

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_REMOVE,
                CastRoomItemIdParam(token.trim(), itemId.trim()),
            ).decodeAs<CastRoomPayload>()
        }.getOrNull()
        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Could not remove Cast Room item")
        }
        return payload
    }

    suspend fun reorder(context: Context, token: String, itemIds: List<String>): CastRoomPayload {
        requireConfigured()
        if (!CastRoomLinks.isValidToken(token) || itemIds.isEmpty()) {
            error("Invalid Cast Room reorder")
        }
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before using Cast Room")
        client.auth.awaitInitialization()

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_REORDER,
                CastRoomReorderParam(token.trim(), itemIds),
            ).decodeAs<CastRoomPayload>()
        }.getOrNull()
        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Could not reorder Cast Room queue")
        }
        return payload
    }

    suspend fun playAt(context: Context, token: String, index: Int): CastRoomPayload {
        requireConfigured()
        if (!CastRoomLinks.isValidToken(token)) error("Invalid share token")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before using Cast Room")
        client.auth.awaitInitialization()

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_PLAY_AT,
                CastRoomPlayAtParam(token.trim(), index),
            ).decodeAs<CastRoomPayload>()
        }.getOrNull()
        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Could not play Cast Room item")
        }
        return payload
    }

    suspend fun skip(context: Context, token: String): CastRoomPayload {
        requireConfigured()
        if (!CastRoomLinks.isValidToken(token)) error("Invalid share token")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before using Cast Room")
        client.auth.awaitInitialization()

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_SKIP,
                CastRoomTokenParam(token.trim()),
            ).decodeAs<CastRoomPayload>()
        }.getOrNull()
        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Could not skip Cast Room item")
        }
        return payload
    }

    suspend fun setPlaying(context: Context, token: String, playing: Boolean): CastRoomPayload {
        requireConfigured()
        if (!CastRoomLinks.isValidToken(token)) error("Invalid share token")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before using Cast Room")
        client.auth.awaitInitialization()

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_SET_PLAYING,
                CastRoomSetPlayingParam(token.trim(), playing),
            ).decodeAs<CastRoomPayload>()
        }.getOrNull()
        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Could not update Cast Room playback")
        }
        return payload
    }

    suspend fun flushStalePending(context: Context): Int {
        val appContext = context.applicationContext
        if (!SupabaseProvider.isConfigured) return 0
        var dropped = 0
        for (token in CastRoomPendingJoin.peek(appContext)) {
            if (!CastRoomLinks.isValidToken(token)) {
                CastRoomPendingJoin.acknowledge(appContext, token)
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
                    CastRoomPendingJoin.acknowledge(appContext, token)
                    dropped++
                } else {
                    Log.w(TAG, "Pending cast room readiness check failed", error)
                }
            }
        }
        return dropped
    }

    fun toVideoType(item: CastRoomItemPayload): Video.Type {
        return when (item.mediaType) {
            "movie" -> Video.Type.Movie(
                id = item.mediaId,
                title = item.title.ifBlank { "Movie" },
                releaseDate = item.releaseDate.orEmpty(),
                poster = item.poster.orEmpty(),
                imdbId = item.imdbId,
            )
            "episode" -> Video.Type.Episode(
                id = item.mediaId,
                number = item.episodeNumber ?: 1,
                title = item.episodeTitle ?: item.title.takeIf { it.isNotBlank() },
                poster = item.poster,
                overview = null,
                tvShow = Video.Type.Episode.TvShow(
                    id = item.showId ?: item.mediaId,
                    title = item.showTitle ?: item.title.ifBlank { "Show" },
                    poster = item.showPoster ?: item.poster,
                    banner = null,
                    releaseDate = item.releaseDate,
                    imdbId = item.imdbId,
                ),
                season = Video.Type.Episode.Season(
                    number = item.seasonNumber ?: 1,
                    title = item.seasonTitle,
                ),
            )
            else -> error("Unsupported media type: ${item.mediaType}")
        }
    }

    private suspend fun endOwnedActiveRooms(
        context: Context,
        profileId: String,
        userId: String,
    ) {
        val client = SupabaseProvider.clientFor(context, profileId)
        runCatching {
            client.from(TABLE)
                .update(
                    RemoteCastRoomEndUpdate(
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
        }.onFailure { Log.w(TAG, "Could not end previous cast rooms", it) }
    }

    private fun newShareToken(): String =
        UUID.randomUUID().toString().replace("-", "")

    private fun newUniqueShortCode(): String = CastRoomQueue.newShortCode()

    private fun requireConfigured() {
        if (!SupabaseProvider.isConfigured) {
            error("Configure Supabase before using Cast Room")
        }
    }
}
