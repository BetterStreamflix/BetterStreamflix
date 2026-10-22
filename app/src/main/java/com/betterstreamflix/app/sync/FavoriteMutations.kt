package com.betterstreamflix.app.sync

import android.content.Context
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.platform.simkl.SimklSyncHooks
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.utils.ArtworkRepair
import com.betterstreamflix.app.utils.UserDataCache

/**
 * Single path for favorite (My List / watchlist) toggles so Room, cache, cloud
 * enqueue (via [UserDataCache] → [CloudSyncHooks]), and Simkl stay aligned.
 *
 * Callers must use these helpers instead of DAO [upsertFavorite] + cache writes
 * so artwork repair and enqueue stay consistent. Call from an IO dispatcher.
 */
object FavoriteMutations {

    suspend fun setMovieFavorite(
        context: Context,
        provider: Provider,
        movie: Movie,
        favorite: Boolean,
        database: AppDatabase? = null,
    ): Movie {
        val appContext = context.applicationContext
        val db = database ?: AppDatabase.getInstanceOrNull(appContext) ?: return movie
        val resolved = ArtworkRepair.resolveMovieForFavorite(appContext, movie, favorite)
        db.movieDao().upsertFavorite(resolved, favorite)
        val favorited = resolved.copy(isFavorite = favorite).apply {
            favoritedAtMillis = if (favorite) {
                resolved.favoritedAtMillis ?: System.currentTimeMillis()
            } else {
                null
            }
        }
        if (favorite) {
            UserDataCache.addMovieToFavorites(appContext, provider, favorited)
            runCatching {
                com.betterstreamflix.app.taste.TasteFeedback.favoriteBoost(appContext, favorited)
            }
        } else {
            UserDataCache.removeMovieFromFavorites(appContext, provider, movie.id)
            runCatching {
                com.betterstreamflix.app.taste.TasteFeedback.favoriteUnboost(appContext, favorited)
            }
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
        database: AppDatabase? = null,
    ): TvShow {
        val appContext = context.applicationContext
        val db = database ?: AppDatabase.getInstanceOrNull(appContext) ?: return tvShow
        val resolved = ArtworkRepair.resolveTvShowForFavorite(appContext, tvShow, favorite)
        db.tvShowDao().upsertFavorite(resolved, favorite)
        val favorited = resolved.copy(isFavorite = favorite).apply {
            favoritedAtMillis = if (favorite) {
                resolved.favoritedAtMillis ?: System.currentTimeMillis()
            } else {
                null
            }
        }
        if (favorite) {
            UserDataCache.addTvShowToFavorites(appContext, provider, favorited)
            runCatching {
                com.betterstreamflix.app.taste.TasteFeedback.favoriteBoost(appContext, favorited)
            }
        } else {
            UserDataCache.removeTvShowFromFavorites(appContext, provider, tvShow.id)
            runCatching {
                com.betterstreamflix.app.taste.TasteFeedback.favoriteUnboost(appContext, favorited)
            }
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
