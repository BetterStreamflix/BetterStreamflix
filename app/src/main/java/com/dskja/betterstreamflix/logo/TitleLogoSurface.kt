package com.dskja.betterstreamflix.logo

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.format
import kotlinx.coroutines.launch

/**
 * Shared bind/resolve path for Mobile Detail, TV Detail, Featured, Player, and list surfaces.
 */
object TitleLogoSurface {

    private const val TITLE_TAG = com.dskja.betterstreamflix.R.id.title_logo_expected_title_tag

    fun bindAndMaybeResolve(
        anchor: View,
        imageView: ImageView,
        titleView: TextView?,
        movie: Movie,
        hideUntilReady: Boolean = true,
        persist: Boolean = true,
        allowAlternateOnFail: Boolean = true,
        stillCurrent: (() -> Boolean)? = null,
        onResolved: ((String, LogoSource) -> Unit)? = null,
    ) {
        TmdbLogoBinder.cancel(anchor)
        anchor.setTag(TITLE_TAG, movie.title)
        val currentCheck = stillCurrent ?: { anchor.getTag(TITLE_TAG) == movie.title }
        val current = movie.logo
        val wantedLang = UserPreferences.currentProvider?.language
        TmdbLogoBinder.bindImage(
            imageView = imageView,
            titleView = titleView,
            logoUrl = current,
            title = movie.title,
            hideUntilReady = hideUntilReady,
            stillCurrent = currentCheck,
            onLoadFailed = {
                if (allowAlternateOnFail && TmdbLogoPicker.isTrustedTmdbLogo(current)) {
                    resolveMovie(
                        anchor, movie, imageView, titleView,
                        hideUntilReady, persist, currentCheck, onResolved,
                    )
                }
            },
        )
        if (TmdbLogoPicker.shouldUpgradeLogo(current, movie.logoLanguage, wantedLang)) {
            resolveMovie(
                anchor, movie, imageView, titleView,
                hideUntilReady, persist, currentCheck, onResolved,
            )
        }
    }

    fun bindAndMaybeResolve(
        anchor: View,
        imageView: ImageView,
        titleView: TextView?,
        tvShow: TvShow,
        hideUntilReady: Boolean = true,
        persist: Boolean = true,
        allowAlternateOnFail: Boolean = true,
        stillCurrent: (() -> Boolean)? = null,
        onResolved: ((String, LogoSource) -> Unit)? = null,
    ) {
        TmdbLogoBinder.cancel(anchor)
        anchor.setTag(TITLE_TAG, tvShow.title)
        val currentCheck = stillCurrent ?: { anchor.getTag(TITLE_TAG) == tvShow.title }
        val current = tvShow.logo
        val wantedLang = UserPreferences.currentProvider?.language
        TmdbLogoBinder.bindImage(
            imageView = imageView,
            titleView = titleView,
            logoUrl = current,
            title = tvShow.title,
            hideUntilReady = hideUntilReady,
            stillCurrent = currentCheck,
            onLoadFailed = {
                if (allowAlternateOnFail && TmdbLogoPicker.isTrustedTmdbLogo(current)) {
                    resolveTv(
                        anchor, tvShow, imageView, titleView,
                        hideUntilReady, persist, currentCheck, onResolved,
                    )
                }
            },
        )
        if (TmdbLogoPicker.shouldUpgradeLogo(current, tvShow.logoLanguage, wantedLang)) {
            resolveTv(
                anchor, tvShow, imageView, titleView,
                hideUntilReady, persist, currentCheck, onResolved,
            )
        }
    }

    /**
     * Bind an already-known logo URL without network resolve (Continue Watching / Search rows).
     */
    fun bindCachedOnly(
        imageView: ImageView,
        titleView: TextView?,
        logoUrl: String?,
        title: String,
        hideUntilReady: Boolean = true,
    ) {
        TmdbLogoBinder.bindImage(
            imageView = imageView,
            titleView = titleView,
            logoUrl = logoUrl,
            title = title,
            hideUntilReady = hideUntilReady,
        )
    }

    /**
     * Player / free-form resolve: title (+ optional ids) without a Movie/TvShow model.
     */
    fun resolveAndBind(
        anchor: View,
        imageView: ImageView,
        titleView: TextView?,
        title: String,
        year: Int? = null,
        isTv: Boolean = false,
        tmdbId: String? = null,
        imdbId: String? = null,
        existingLogo: String? = null,
        existingLogoLanguage: String? = null,
        language: String? = UserPreferences.currentProvider?.language,
        hideUntilReady: Boolean = true,
        onResolved: ((String, LogoSource) -> Unit)? = null,
    ) {
        TmdbLogoBinder.cancel(anchor)
        anchor.setTag(TITLE_TAG, title)
        val stillCurrent = { anchor.getTag(TITLE_TAG) == title }
        val wantedLang = language ?: UserPreferences.currentProvider?.language
        TmdbLogoBinder.bindImage(
            imageView = imageView,
            titleView = titleView,
            logoUrl = existingLogo,
            title = title,
            hideUntilReady = hideUntilReady,
            stillCurrent = stillCurrent,
        )
        if (!TmdbLogoPicker.shouldUpgradeLogo(existingLogo, existingLogoLanguage, wantedLang)) return
        TmdbLogoBinder.resolve(
            anchor = anchor,
            request = LogoRequest(
                title = title,
                year = year,
                isTv = isTv,
                tmdbId = tmdbId,
                imdbId = imdbId,
                language = wantedLang,
                existingLogo = existingLogo,
            ),
        ) { url, source ->
            onResolved?.invoke(url, source)
            if (stillCurrent()) {
                TmdbLogoBinder.bindImage(
                    imageView = imageView,
                    titleView = titleView,
                    logoUrl = url,
                    title = title,
                    hideUntilReady = hideUntilReady,
                    stillCurrent = stillCurrent,
                )
            }
        }
    }

    private fun resolveMovie(
        anchor: View,
        movie: Movie,
        imageView: ImageView,
        titleView: TextView?,
        hideUntilReady: Boolean,
        persist: Boolean,
        stillCurrent: () -> Boolean,
        onResolved: ((String, LogoSource) -> Unit)?,
    ) {
        val wantedLang = UserPreferences.currentProvider?.language
        TmdbLogoBinder.resolve(
            anchor = anchor,
            request = LogoRequest(
                title = movie.title,
                year = movie.released?.format("yyyy")?.toIntOrNull(),
                isTv = false,
                tmdbId = movie.tmdbId,
                imdbId = movie.imdbId,
                language = wantedLang,
                existingLogo = movie.logo,
                existingSource = movie.logoSource,
            ),
        ) { url, source ->
            movie.logo = url
            movie.logoSource = source
            movie.logoLanguage = wantedLang
            onResolved?.invoke(url, source)
            if (persist) schedulePersistMovie(anchor, movie)
            if (stillCurrent()) {
                TmdbLogoBinder.bindImage(
                    imageView = imageView,
                    titleView = titleView,
                    logoUrl = url,
                    title = movie.title,
                    hideUntilReady = hideUntilReady,
                    stillCurrent = stillCurrent,
                )
            }
        }
    }

    private fun resolveTv(
        anchor: View,
        tvShow: TvShow,
        imageView: ImageView,
        titleView: TextView?,
        hideUntilReady: Boolean,
        persist: Boolean,
        stillCurrent: () -> Boolean,
        onResolved: ((String, LogoSource) -> Unit)?,
    ) {
        val wantedLang = UserPreferences.currentProvider?.language
        TmdbLogoBinder.resolve(
            anchor = anchor,
            request = LogoRequest(
                title = tvShow.title,
                year = tvShow.released?.format("yyyy")?.toIntOrNull(),
                isTv = true,
                tmdbId = tvShow.tmdbId,
                imdbId = tvShow.imdbId,
                language = wantedLang,
                existingLogo = tvShow.logo,
                existingSource = tvShow.logoSource,
            ),
        ) { url, source ->
            tvShow.logo = url
            tvShow.logoSource = source
            tvShow.logoLanguage = wantedLang
            onResolved?.invoke(url, source)
            if (persist) schedulePersistTv(anchor, tvShow)
            if (stillCurrent()) {
                TmdbLogoBinder.bindImage(
                    imageView = imageView,
                    titleView = titleView,
                    logoUrl = url,
                    title = tvShow.title,
                    hideUntilReady = hideUntilReady,
                    stillCurrent = stillCurrent,
                )
            }
        }
    }

    private fun schedulePersistMovie(anchor: View, movie: Movie) {
        val owner = anchor.findViewTreeLifecycleOwner() ?: return
        owner.lifecycleScope.launch { LogoPersist.persistMovieLogo(movie) }
    }

    private fun schedulePersistTv(anchor: View, tvShow: TvShow) {
        val owner = anchor.findViewTreeLifecycleOwner() ?: return
        owner.lifecycleScope.launch { LogoPersist.persistTvLogo(tvShow) }
    }
}
