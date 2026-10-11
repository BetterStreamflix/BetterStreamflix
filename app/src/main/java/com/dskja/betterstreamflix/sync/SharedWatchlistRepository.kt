package com.dskja.betterstreamflix.sync

import android.content.Context
import android.util.Log
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.CrossProviderLibrary
import com.dskja.betterstreamflix.utils.UserPreferences
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

object SharedWatchlistRepository {
    private const val TAG = "SharedWatchlist"
    private const val LISTS = "shared_watchlists"
    private const val ITEMS = "shared_watchlist_items"
    private const val RPC = "get_shared_watchlist"
    private const val TOKEN_TTL_DAYS = 30L

    /**
     * Publishes the active profile's favorites as this Auth user's single shared list.
     *
     * One row per owner: insert on first share, update thereafter. Every publish/republish
     * mints a new [RemoteSharedWatchlist.shareToken] and extends [RemoteSharedWatchlist.expiresAt]
     * so previously shared links stop working (stolen links die on republish).
     */
    suspend fun publishCurrentFavorites(context: Context): String {
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: error("Sign in to share your watchlist")

        val history = CrossProviderLibrary.loadHomeHistory(appContext)
        val fallbackProvider = UserPreferences.currentProvider?.name
        val items = buildItems(history.favoriteMovies, history.favoriteTvShows, fallbackProvider)
        if (items.isEmpty()) error("Your watchlist is empty")

        val existing = client.from(LISTS)
            .select(Columns.ALL) {
                filter {
                    eq("owner_id", userId)
                }
                limit(1)
            }
            .decodeList<RemoteSharedWatchlist>()
            .firstOrNull()

        val title = appContext.getString(R.string.shared_watchlist_default_title)
        val token = newShareToken()
        val expiresAt = defaultExpiryIso()
        val nowIso = Instant.now().toString()

        val list = if (existing?.id != null) {
            client.from(LISTS)
                .update(
                    RemoteSharedWatchlistTokenUpdate(
                        title = title,
                        shareToken = token,
                        expiresAt = expiresAt,
                        updatedAt = nowIso,
                    ),
                ) {
                    filter { eq("id", existing.id) }
                    select(Columns.ALL)
                }
                .decodeSingle<RemoteSharedWatchlist>()
        } else {
            client.from(LISTS)
                .insert(
                    RemoteSharedWatchlist(
                        ownerId = userId,
                        title = title,
                        shareToken = token,
                        expiresAt = expiresAt,
                    ),
                ) {
                    select(Columns.ALL)
                }
                .decodeSingle<RemoteSharedWatchlist>()
        }

        val listId = list.id ?: error("Shared list missing id")
        client.from(ITEMS).delete {
            filter { eq("list_id", listId) }
        }
        val rows = items.mapIndexed { index, item ->
            item.copy(listId = listId, sortIndex = index)
        }
        rows.chunked(100).forEach { chunk ->
            client.from(ITEMS).insert(chunk)
        }

        return SharedWatchlistLinks.buildUri(list.shareToken)
    }

    /**
     * Rotates the owner's share token and extends TTL without replacing items.
     * Returns the new deep-link URI.
     */
    suspend fun rotateShareToken(context: Context): String {
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: error("Sign in to rotate your watchlist link")

        val existing = client.from(LISTS)
            .select(Columns.ALL) {
                filter { eq("owner_id", userId) }
                limit(1)
            }
            .decodeList<RemoteSharedWatchlist>()
            .firstOrNull()
            ?: error("No shared list to rotate")

        val listId = existing.id ?: error("Shared list missing id")
        val title = existing.title.ifBlank {
            appContext.getString(R.string.shared_watchlist_default_title)
        }
        val updated = client.from(LISTS)
            .update(
                RemoteSharedWatchlistTokenUpdate(
                    title = title,
                    shareToken = newShareToken(),
                    expiresAt = defaultExpiryIso(),
                    updatedAt = Instant.now().toString(),
                ),
            ) {
                filter { eq("id", listId) }
                select(Columns.ALL)
            }
            .decodeSingle<RemoteSharedWatchlist>()

        return SharedWatchlistLinks.buildUri(updated.shareToken)
    }

    /** Deletes the signed-in owner's shared_watchlists row (items cascade). */
    suspend fun revokeCurrentSharedList(context: Context) {
        requireConfigured()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: error("Sign in to revoke your watchlist link")

        client.from(LISTS).delete {
            filter { eq("owner_id", userId) }
        }
    }

    suspend fun fetchByToken(context: Context, token: String): SharedWatchlistPayload {
        requireConfigured()
        if (!SharedWatchlistLinks.isValidToken(token)) {
            error("Invalid share link")
        }
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before opening shared lists")
        client.auth.awaitInitialization()

        val payload = runCatching {
            client.postgrest.rpc(
                RPC,
                SharedWatchlistTokenParam(token.trim()),
            ).decodeAs<SharedWatchlistPayload>()
        }.getOrNull()

        if (payload == null || payload.shareToken.isNullOrBlank()) {
            error("Shared list not found")
        }
        return payload
    }

    /**
     * Does **not** auto-import. Drops invalid/expired/missing tokens from the
     * per-profile pending queue. Interactive confirm happens in
     * [SharedWatchlistUi.tryFlushPending].
     */
    suspend fun flushPendingImports(context: Context): Int {
        if (!SupabaseProvider.isConfigured) return 0
        val appContext = context.applicationContext
        var dropped = 0
        for (token in SharedWatchlistPendingImport.peek(appContext)) {
            if (!SharedWatchlistLinks.isValidToken(token)) {
                SharedWatchlistPendingImport.acknowledge(appContext, token)
                dropped++
                continue
            }
            val outcome = runCatching { fetchByToken(appContext, token) }
            outcome.onFailure { error ->
                val msg = error.message.orEmpty()
                if (msg.contains("not found", ignoreCase = true) ||
                    msg.contains("Invalid share", ignoreCase = true)
                ) {
                    SharedWatchlistPendingImport.acknowledge(appContext, token)
                    dropped++
                    Log.i(TAG, "Dropped stale pending share token")
                } else {
                    Log.w(TAG, "Pending shared-list readiness check failed", error)
                }
            }
        }
        return dropped
    }

    private fun newShareToken(): String =
        UUID.randomUUID().toString().replace("-", "")

    private fun defaultExpiryIso(): String =
        Instant.now().plus(TOKEN_TTL_DAYS, ChronoUnit.DAYS).toString()

    private fun buildItems(
        movies: List<Movie>,
        shows: List<TvShow>,
        fallbackProvider: String?,
    ): List<RemoteSharedWatchlistItem> {
        val movieItems = movies.mapNotNull { movie ->
            val provider = movie.providerName?.takeIf { it.isNotBlank() } ?: fallbackProvider
            if (provider.isNullOrBlank()) return@mapNotNull null
            RemoteSharedWatchlistItem(
                provider = provider,
                mediaType = "movie",
                mediaId = movie.id,
                title = movie.title,
                poster = movie.poster,
                banner = movie.banner,
            )
        }
        val showItems = shows.mapNotNull { show ->
            val provider = show.providerName?.takeIf { it.isNotBlank() } ?: fallbackProvider
            if (provider.isNullOrBlank()) return@mapNotNull null
            RemoteSharedWatchlistItem(
                provider = provider,
                mediaType = "tv_show",
                mediaId = show.id,
                title = show.title,
                poster = show.poster,
                banner = show.banner,
            )
        }
        return movieItems + showItems
    }

    private fun requireConfigured() {
        if (!SupabaseProvider.isConfigured) {
            error("Configure Supabase before using watchlist sync")
        }
    }
}
