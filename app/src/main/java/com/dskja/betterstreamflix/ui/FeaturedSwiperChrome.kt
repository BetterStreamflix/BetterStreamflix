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
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.UserPreferences
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
        // Leanback Density: Compact modestly shortens the Featured card (DENS-02).
        val densityHintDp = LeanbackDensityPolicy.featuredCardHeightDp()
        val cardHeightPx = runCatching {
            context.resources.getDimensionPixelSize(R.dimen.home_featured_card_height)
        }.getOrDefault((densityHintDp * dm.density).roundToInt()).let { dimenPx ->
            val hintPx = (densityHintDp * dm.density).roundToInt()
            // Prefer the smaller of dimen vs density hint so Compact can shrink.
            minOf(dimenPx, hintPx)
        }
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
        button.setTextColor(ContextCompat.getColor(button.context, R.color.cinema_cta_ink))
        button.compoundDrawableTintList = null
    }

    fun bindListButton(
        button: TextView,
        inList: Boolean,
        animate: Boolean = false,
        title: String? = null,
    ) {
        button.text = button.context.getString(
            if (inList) R.string.home_swiper_in_my_list else R.string.home_swiper_my_list,
        )
        val icon = ContextCompat.getDrawable(
            button.context,
            if (inList) R.drawable.ic_list_added else R.drawable.ic_list_add,
        )
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(button, icon, null, null, null)
        TooltipCompat.setTooltipText(button, button.text)
        val label = title?.takeIf { it.isNotBlank() }
        if (label != null) {
            button.contentDescription = button.context.getString(
                if (inList) R.string.a11y_featured_in_my_list else R.string.a11y_featured_my_list,
                label,
            )
        }
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
        val db = AppDatabase.getInstanceOrNull(anchor.context) ?: return null
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
        val db = AppDatabase.getInstanceOrNull(anchor.context) ?: return null
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
            val provider = UserPreferences.currentProvider ?: return@launch
            val db = AppDatabase.getInstanceOrNull(anchor.context) ?: return@launch
            val target = !(db.movieDao().getById(movie.id)?.isFavorite ?: movie.isFavorite)
            val resolved = com.dskja.betterstreamflix.sync.FavoriteMutations.setMovieFavorite(
                context = anchor.context,
                provider = provider,
                movie = movie,
                favorite = target,
                database = db,
            )
            withContext(Dispatchers.Main) {
                movie.isFavorite = target
                movie.poster = resolved.poster
                movie.banner = resolved.banner
                bindListButton(button, target, animate = true, title = movie.title)
                onDone?.invoke(target)
            }
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
            val provider = UserPreferences.currentProvider ?: return@launch
            val db = AppDatabase.getInstanceOrNull(anchor.context) ?: return@launch
            val target = !(db.tvShowDao().getById(tvShow.id)?.isFavorite ?: tvShow.isFavorite)
            val resolved = com.dskja.betterstreamflix.sync.FavoriteMutations.setTvShowFavorite(
                context = anchor.context,
                provider = provider,
                tvShow = tvShow,
                favorite = target,
                database = db,
            )
            withContext(Dispatchers.Main) {
                tvShow.isFavorite = target
                tvShow.poster = resolved.poster
                tvShow.banner = resolved.banner
                bindListButton(button, target, animate = true, title = tvShow.title)
                onDone?.invoke(target)
            }
        }
    }
}
