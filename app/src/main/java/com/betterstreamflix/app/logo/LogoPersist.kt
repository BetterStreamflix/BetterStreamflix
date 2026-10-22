package com.betterstreamflix.app.logo

import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.BetterStreamflixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Soft-persist resolved logos into Room when a row already exists. */
object LogoPersist {

    /**
     * True when Room should be updated: URL changed, or language tag changed even
     * when the CDN URL is identical (provider language switch).
     */
    fun shouldUpdate(
        existingLogo: String?,
        existingLang: String?,
        newLogo: String,
        newLang: String?,
    ): Boolean {
        if (existingLogo != newLogo) return true
        return TmdbLogoPicker.primaryLanguage(existingLang) !=
            TmdbLogoPicker.primaryLanguage(newLang)
    }

    suspend fun persistMovieLogo(movie: Movie) {
        val logo = movie.logo?.takeIf { it.isNotBlank() } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.getInstanceOrNull(BetterStreamflixApp.instance) ?: return@runCatching
                val existing = db.movieDao().getById(movie.id) ?: return@runCatching
                if (!shouldUpdate(existing.logo, existing.logoLanguage, logo, movie.logoLanguage)) {
                    return@runCatching
                }
                // Column-only update — avoid read/modify/write races with watch progress.
                db.movieDao().updateLogo(movie.id, logo, movie.logoLanguage)
            }
        }
    }

    suspend fun persistTvLogo(tvShow: TvShow) {
        val logo = tvShow.logo?.takeIf { it.isNotBlank() } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.getInstanceOrNull(BetterStreamflixApp.instance) ?: return@runCatching
                val existing = db.tvShowDao().getById(tvShow.id) ?: return@runCatching
                if (!shouldUpdate(existing.logo, existing.logoLanguage, logo, tvShow.logoLanguage)) {
                    return@runCatching
                }
                db.tvShowDao().updateLogo(tvShow.id, logo, tvShow.logoLanguage)
            }
        }
    }
}
