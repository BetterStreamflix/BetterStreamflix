package com.dskja.betterstreamflix.sync

import android.content.Context
import android.util.Log
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
import java.util.UUID

object SharedWatchlistRepository {
    private const val TAG = "SharedWatchlist"
    private const val LISTS = "shared_watchlists"
    private const val ITEMS = "shared_watchlist_items"
    private const val RPC = "get_shared_watchlist"

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

        val list = if (existing?.id != null) {
            existing
        } else {
            val token = UUID.randomUUID().toString().replace("-", "")
            client.from(LISTS)
                .insert(
                    RemoteSharedWatchlist(
                        ownerId = userId,
                        title = "Watchlist",
                        shareToken = token,
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

    suspend fun flushPendingImports(context: Context): Int {
        if (!SupabaseProvider.isConfigured) return 0
        val appContext = context.applicationContext
        var imported = 0
        for (token in SharedWatchlistPendingImport.peek(appContext)) {
            runCatching {
                val payload = fetchByToken(appContext, token)
                SharedWatchlistImporter.importPayload(appContext, payload)
                SharedWatchlistPendingImport.acknowledge(appContext, token)
                imported++
            }.onFailure { error ->
                Log.w(TAG, "Pending shared-list import failed for $token", error)
            }
        }
        return imported
    }

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
