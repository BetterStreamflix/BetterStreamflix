package com.betterstreamflix.app.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.betterstreamflix.app.R
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.databinding.ContentCategorySwiperTvBinding
import com.betterstreamflix.app.databinding.ItemCategorySwiperMobileBinding
import com.betterstreamflix.app.logo.FeaturedLogoEnrich
import com.betterstreamflix.app.logo.TitleLogoSurface
import com.betterstreamflix.app.logo.TmdbLogoBinder
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.utils.DeviceCapabilities
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.UserPreferences
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
     * Mobile iOS-style circular My List chip: swaps plus/check glyph and plays
     * the ring-pulse + icon-pop on state change (mirrors FeaturedAddToListButton
     * on iOS — ring expands 1.0→1.22 fading out while the icon springs in).
     */
    fun bindMobileListButton(
        button: View,
        inList: Boolean,
        animate: Boolean = false,
        title: String? = null,
    ) {
        val icon = button.findViewById<android.widget.ImageView>(R.id.iv_swiper_list_icon)
        icon?.setImageResource(if (inList) R.drawable.ic_featured_check else R.drawable.ic_featured_add)
        val name = title?.takeIf { it.isNotBlank() }
        button.contentDescription = if (name != null) {
            button.context.getString(
                if (inList) R.string.a11y_featured_in_my_list else R.string.a11y_featured_my_list,
                name,
            )
        } else {
            button.context.getString(
                if (inList) R.string.home_swiper_in_my_list else R.string.home_swiper_my_list,
            )
        }
        TooltipCompat.setTooltipText(
            button,
            button.context.getString(
                if (inList) R.string.home_swiper_in_my_list else R.string.home_swiper_my_list,
            ),
        )
        if (animate) playMobileListPulse(button)
    }

    /** iOS FeaturedAddToListButton ring burst: ring scales out + fades, icon pops. */
    fun playMobileListPulse(button: View) {
        val ring = button.findViewById<View>(R.id.v_swiper_list_ring)
        val icon = button.findViewById<android.widget.ImageView>(R.id.iv_swiper_list_icon)
        ring?.let { r ->
            r.animate().cancel()
            r.visibility = View.VISIBLE
            r.scaleX = 1f
            r.scaleY = 1f
            r.alpha = 0.85f
            r.animate()
                .scaleX(1.22f).scaleY(1.22f)
                .alpha(0f)
                .setDuration(520L)
                .withEndAction {
                    r.visibility = View.GONE
                    r.scaleX = 1f
                    r.scaleY = 1f
                    r.alpha = 1f
                }
                .start()
        }
        icon?.let { iv ->
            iv.animate().cancel()
            iv.scaleX = 1f
            iv.scaleY = 1f
            iv.animate()
                .scaleX(1.18f).scaleY(1.18f)
                .setDuration(140L)
                .withEndAction {
                    iv.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
                }
                .start()
        }
        button.animate().cancel()
        button.scaleX = 1f
        button.scaleY = 1f
        button.animate()
            .scaleX(1.06f).scaleY(1.06f)
            .setDuration(140L)
            .withEndAction {
                button.animate().scaleX(1f).scaleY(1f).setDuration(160L).start()
            }
            .start()
    }

    /** Routes to the right binder depending on the bound view type. */
    private fun bindAnyListButton(
        button: View,
        inList: Boolean,
        animate: Boolean,
        title: String?,
    ) {
        if (button is TextView) {
            bindListButton(button, inList, animate = animate, title = title)
        } else {
            bindMobileListButton(button, inList, animate = animate, title = title)
        }
    }

    /**
     * Observe Room favorite state so Featured CTAs stay in sync when the user
     * toggles My List from Detail / Favorites / another surface.
     */
    fun observeListState(
        anchor: View,
        button: View,
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
        button: View,
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
        button: View,
        movie: Movie,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        val owner = anchor.findViewTreeLifecycleOwner() ?: return
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val provider = UserPreferences.currentProvider ?: return@launch
            val db = AppDatabase.getInstanceOrNull(anchor.context) ?: return@launch
            val target = !(db.movieDao().getById(movie.id)?.isFavorite ?: movie.isFavorite)
            val resolved = com.betterstreamflix.app.sync.FavoriteMutations.setMovieFavorite(
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
                bindAnyListButton(button, target, animate = true, title = movie.title)
                onDone?.invoke(target)
            }
        }
    }

    fun toggleTvShowFavorite(
        anchor: View,
        button: View,
        tvShow: TvShow,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        val owner = anchor.findViewTreeLifecycleOwner() ?: return
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val provider = UserPreferences.currentProvider ?: return@launch
            val db = AppDatabase.getInstanceOrNull(anchor.context) ?: return@launch
            val target = !(db.tvShowDao().getById(tvShow.id)?.isFavorite ?: tvShow.isFavorite)
            val resolved = com.betterstreamflix.app.sync.FavoriteMutations.setTvShowFavorite(
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
                bindAnyListButton(button, target, animate = true, title = tvShow.title)
                onDone?.invoke(target)
            }
        }
    }
}
