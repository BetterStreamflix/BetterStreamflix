package com.dskja.betterstreamflix.ui

import android.content.Context
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
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared chrome for the Featured card: TMDb title logo centered over the cover,
 * with Watch Now / Add to List CTAs underneath (Mobile) or beside meta (TV).
 */
object FeaturedSwiperChrome {

    /**
     * Floor for Glide decode / prefetch. The mobile Featured card is tall
     * (580dp+); decoding at 1280×720 used to upsample into ~xxhdpi view pixels
     * and looked soft. Prefer [artworkOverride] at bind time.
     */
    const val ARTWORK_WIDTH = 1920
    const val ARTWORK_HEIGHT = 1920

    private const val MAX_ARTWORK_WIDTH = 3840
    private const val MAX_ARTWORK_HEIGHT = 2560

    /**
     * Glide override matching the Featured surface in device pixels so
     * centerCrop never stretches a tiny bitmap across a tall hero.
     */
    fun artworkOverride(view: View): Pair<Int, Int> = artworkOverride(view.context, view)

    fun artworkOverride(context: Context, view: View? = null): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val constrained = isConstrainedArtworkDevice(context)
        val floorW = if (constrained) 1280 else ARTWORK_WIDTH
        val floorH = if (constrained) 1280 else ARTWORK_HEIGHT
        val maxW = if (constrained) 1600 else MAX_ARTWORK_WIDTH
        val maxH = if (constrained) 1600 else MAX_ARTWORK_HEIGHT
        val cardHeightPx = runCatching {
            context.resources.getDimensionPixelSize(R.dimen.home_featured_card_height)
        }.getOrDefault((580f * dm.density).roundToInt())
        val width = max(
            max(view?.width ?: 0, dm.widthPixels),
            floorW,
        ).coerceAtMost(maxW)
        val height = max(
            max(view?.height ?: 0, cardHeightPx),
            floorH,
        ).coerceAtMost(maxH)
        return width to height
    }

    /** TV home full-bleed backdrop. Matches the screen so Glide does not letterbox a square decode. */
    fun tvBackdropOverride(context: Context): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val constrained = isConstrainedArtworkDevice(context)
        val maxW = if (constrained) 1920 else MAX_ARTWORK_WIDTH
        val maxH = if (constrained) 1080 else MAX_ARTWORK_HEIGHT
        val width = max(dm.widthPixels, if (constrained) 1280 else 1920).coerceAtMost(maxW)
        val height = max(dm.heightPixels, if (constrained) 720 else 1080).coerceAtMost(maxH)
        return width to height
    }

    private fun isConstrainedArtworkDevice(context: Context): Boolean {
        return DeviceCapabilities.isLowRamDevice(context) ||
            DeviceCapabilities.shouldUseConstrainedPlayback(context)
    }

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
        button.setTextColor(0xFF0A0A0C.toInt())
        button.compoundDrawableTintList = null
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
