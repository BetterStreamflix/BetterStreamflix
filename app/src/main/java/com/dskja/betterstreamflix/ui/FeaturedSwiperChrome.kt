package com.dskja.betterstreamflix.ui

import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.ContentCategorySwiperTvBinding
import com.dskja.betterstreamflix.databinding.ItemCategorySwiperMobileBinding
import com.dskja.betterstreamflix.logo.FeaturedLogoEnrich
import com.dskja.betterstreamflix.logo.TitleLogoSurface
import com.dskja.betterstreamflix.logo.TmdbLogoBinder
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks
import com.dskja.betterstreamflix.utils.ArtworkRepair
import com.dskja.betterstreamflix.utils.ExpMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared chrome for the Featured card: TMDb title logo centered over the cover,
 * with Watch Now / Add to List CTAs underneath (Mobile) or beside meta (TV).
 */
object FeaturedSwiperChrome {

    /** Wide enough to stay sharp on the full-width Featured card. */
    const val ARTWORK_WIDTH = 1280
    const val ARTWORK_HEIGHT = 720

    /**
     * Binds any existing logo immediately. Upgrades blank / non-TMDb logos via a
     * shared [TitleLogoSurface] resolve, persists into Room, and retries an alternate
     * candidate after a trusted decode failure.
     */
    fun resolveAndBindLogo(binding: ItemCategorySwiperMobileBinding, movie: Movie) {
        TitleLogoSurface.bindAndMaybeResolve(
            anchor = binding.root,
            imageView = binding.ivSwiperLogo,
            titleView = binding.tvSwiperTitle,
            movie = movie,
            persist = true,
            allowAlternateOnFail = true,
        )
    }

    /**
     * Binds any existing logo immediately. Upgrades blank / non-TMDb logos via a
     * shared [TitleLogoSurface] resolve, persists into Room, and retries an alternate
     * candidate after a trusted decode failure.
     */
    fun resolveAndBindLogo(binding: ItemCategorySwiperMobileBinding, tvShow: TvShow) {
        TitleLogoSurface.bindAndMaybeResolve(
            anchor = binding.root,
            imageView = binding.ivSwiperLogo,
            titleView = binding.tvSwiperTitle,
            tvShow = tvShow,
            persist = true,
            allowAlternateOnFail = true,
        )
    }

    fun resolveAndBindLogo(binding: ContentCategorySwiperTvBinding, movie: Movie) {
        TitleLogoSurface.bindAndMaybeResolve(
            anchor = binding.root,
            imageView = binding.ivSwiperLogo,
            titleView = binding.tvSwiperTitle,
            movie = movie,
            persist = true,
            allowAlternateOnFail = true,
        )
    }

    fun resolveAndBindLogo(binding: ContentCategorySwiperTvBinding, tvShow: TvShow) {
        TitleLogoSurface.bindAndMaybeResolve(
            anchor = binding.root,
            imageView = binding.ivSwiperLogo,
            titleView = binding.tvSwiperTitle,
            tvShow = tvShow,
            persist = true,
            allowAlternateOnFail = true,
        )
    }

    /** Cancel in-flight featured logo work when the page is recycled. */
    fun cancel(binding: ItemCategorySwiperMobileBinding) {
        TmdbLogoBinder.cancel(binding.root)
        FeaturedLogoEnrich.cancel(binding.root)
    }

    fun cancel(binding: ContentCategorySwiperTvBinding) {
        TmdbLogoBinder.cancel(binding.root)
        FeaturedLogoEnrich.cancel(binding.root)
    }

    fun wireWatchButton(button: TextView) {
        val play = ContextCompat.getDrawable(button.context, R.drawable.ic_featured_play)
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(button, play, null, null, null)
    }

    fun bindListButton(button: TextView, inList: Boolean, animate: Boolean = false) {
        button.text = button.context.getString(
            if (inList) R.string.home_swiper_in_my_list else R.string.home_swiper_my_list,
        )
        val icon = ContextCompat.getDrawable(
            button.context,
            if (inList) R.drawable.ic_list_added else R.drawable.ic_list_add,
        )
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(button, icon, null, null, null)
        TooltipCompat.setTooltipText(button, button.text)
        if (animate) ExpMotion.softScale(button)
    }

    /**
     * Observe Room favorite state so Featured CTAs stay in sync when the user
     * toggles My List from Detail / Favorites / another surface.
     */
    fun observeListState(
        anchor: View,
        button: TextView,
        movieId: String,
        apply: (Boolean) -> Unit,
    ): Job? {
        val owner = anchor.findViewTreeLifecycleOwner() ?: return null
        val db = AppDatabase.getInstance(anchor.context)
        return owner.lifecycleScope.launch {
            db.movieDao().getByIdAsFlow(movieId).collect { persisted ->
                // Missing Room row ⇒ not in list (keeps CTA honest after deletes).
                apply(persisted?.isFavorite == true)
            }
        }
    }

    fun observeListStateTv(
        anchor: View,
        button: TextView,
        tvShowId: String,
        apply: (Boolean) -> Unit,
    ): Job? {
        val owner = anchor.findViewTreeLifecycleOwner() ?: return null
        val db = AppDatabase.getInstance(anchor.context)
        return owner.lifecycleScope.launch {
            db.tvShowDao().getByIdAsFlow(tvShowId).collect { persisted ->
                apply(persisted?.isFavorite == true)
            }
        }
    }

    fun toggleMovieFavorite(
        anchor: View,
        button: TextView,
        movie: Movie,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        val owner = anchor.findViewTreeLifecycleOwner() ?: return
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(anchor.context)
            val dao = db.movieDao()
            val target = !(dao.getById(movie.id)?.isFavorite ?: movie.isFavorite)
            val resolved = ArtworkRepair.resolveMovieForFavorite(anchor.context, movie, target)
            dao.upsertFavorite(resolved, target)
            withContext(Dispatchers.Main) {
                movie.isFavorite = target
                movie.poster = resolved.poster
                movie.banner = resolved.banner
                bindListButton(button, target, animate = true)
                onDone?.invoke(target)
            }
            SimklSyncHooks.onListToggle(
                add = target,
                imdbId = movie.imdbId,
                tmdbId = movie.tmdbId,
                isTv = false,
                onError = {
                    owner.lifecycleScope.launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            anchor.context,
                            R.string.simkl_sync_failed,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }
    }

    fun toggleTvShowFavorite(
        anchor: View,
        button: TextView,
        tvShow: TvShow,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        val owner = anchor.findViewTreeLifecycleOwner() ?: return
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(anchor.context)
            val dao = db.tvShowDao()
            val target = !(dao.getById(tvShow.id)?.isFavorite ?: tvShow.isFavorite)
            val resolved = ArtworkRepair.resolveTvShowForFavorite(anchor.context, tvShow, target)
            dao.upsertFavorite(resolved, target)
            withContext(Dispatchers.Main) {
                tvShow.isFavorite = target
                tvShow.poster = resolved.poster
                tvShow.banner = resolved.banner
                bindListButton(button, target, animate = true)
                onDone?.invoke(target)
            }
            SimklSyncHooks.onListToggle(
                add = target,
                imdbId = tvShow.imdbId,
                tmdbId = tvShow.tmdbId,
                isTv = true,
                onError = {
                    owner.lifecycleScope.launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            anchor.context,
                            R.string.simkl_sync_failed,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }
    }
}
