package com.dskja.betterstreamflix.sync

import android.content.Context
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.ArtworkRepair
import com.dskja.betterstreamflix.utils.UserDataCache

/**
 * Single path for favorite (My List / watchlist) toggles so Room, cache, cloud
 * enqueue, and Simkl stay aligned. Call from an IO dispatcher.
 */
object FavoriteMutations {

    suspend fun setMovieFavorite(
        context: Context,
        provider: Provider,
        movie: Movie,
        favorite: Boolean,
        database: AppDatabase = AppDatabase.getInstance(context),
    ): Movie {
        val appContext = context.applicationContext
        val resolved = ArtworkRepair.resolveMovieForFavorite(appContext, movie, favorite)
        database.movieDao().upsertFavorite(resolved, favorite)
        val favorited = resolved.copy(isFavorite = favorite).apply {
            favoritedAtMillis = if (favorite) {
                resolved.favoritedAtMillis ?: System.currentTimeMillis()
            } else {
                null
            }
        }
        if (favorite) {
            UserDataCache.addMovieToFavorites(appContext, provider, favorited)
        } else {
            UserDataCache.removeMovieFromFavorites(appContext, provider, movie.id)
        }
        SimklSyncHooks.onListToggle(
            add = favorite,
            imdbId = movie.imdbId,
            tmdbId = movie.tmdbId,
            isTv = false,
        )
        return favorited
    }

    suspend fun setTvShowFavorite(
        context: Context,
        provider: Provider,
        tvShow: TvShow,
        favorite: Boolean,
        database: AppDatabase = AppDatabase.getInstance(context),
    ): TvShow {
        val appContext = context.applicationContext
        val resolved = ArtworkRepair.resolveTvShowForFavorite(appContext, tvShow, favorite)
        database.tvShowDao().upsertFavorite(resolved, favorite)
        val favorited = resolved.copy(isFavorite = favorite).apply {
            favoritedAtMillis = if (favorite) {
                resolved.favoritedAtMillis ?: System.currentTimeMillis()
            } else {
                null
            }
        }
        if (favorite) {
            UserDataCache.addTvShowToFavorites(appContext, provider, favorited)
        } else {
            UserDataCache.removeTvShowFromFavorites(appContext, provider, tvShow.id)
        }
        SimklSyncHooks.onListToggle(
            add = favorite,
            imdbId = tvShow.imdbId,
            tmdbId = tvShow.tmdbId,
            isTv = true,
        )
        return favorited
    }
}
