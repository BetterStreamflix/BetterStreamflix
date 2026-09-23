package com.dskja.betterstreamflix.logo

import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.BetterStreamflixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Soft-persist resolved logos into Room when a row already exists. */
object LogoPersist {

    suspend fun persistMovieLogo(movie: Movie) {
        val logo = movie.logo?.takeIf { it.isNotBlank() } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.getInstance(BetterStreamflixApp.instance)
                val existing = db.movieDao().getById(movie.id) ?: return@runCatching
                if (existing.logo == logo) return@runCatching
                // Column-only update — avoid read/modify/write races with watch progress.
                db.movieDao().updateLogo(movie.id, logo, movie.logoLanguage)
            }
        }
    }

    suspend fun persistTvLogo(tvShow: TvShow) {
        val logo = tvShow.logo?.takeIf { it.isNotBlank() } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.getInstance(BetterStreamflixApp.instance)
                val existing = db.tvShowDao().getById(tvShow.id) ?: return@runCatching
                if (existing.logo == logo) return@runCatching
                db.tvShowDao().updateLogo(tvShow.id, logo, tvShow.logoLanguage)
            }
        }
    }
}
