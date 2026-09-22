package com.dskja.betterstreamflix.ui

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.doOnAttach
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.ItemCategorySwiperMobileBinding
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.ArtworkUrls
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared chrome for the Featured card: TMDb title logo centered over the cover,
 * with Watch Now / Add to List CTAs underneath.
 */
object FeaturedSwiperChrome {

    /** Wide enough to stay sharp on the full-width Featured card. */
    const val ARTWORK_WIDTH = 1280
    const val ARTWORK_HEIGHT = 720

    private const val TITLE_TAG = R.id.tv_swiper_title

    /** Shows the TMDb title logo when available, otherwise the plain title text. */
    fun bindLogo(binding: ItemCategorySwiperMobileBinding, logoUrl: String?, title: String) {
        binding.tvSwiperTitle.text = title
        binding.root.setTag(TITLE_TAG, title)
        val url = ArtworkUrls.preferOriginal(logoUrl) ?: ArtworkUrls.preferHero(logoUrl)
        val logo = binding.ivSwiperLogo
        if (url.isNullOrBlank()) {
            Glide.with(logo).clear(logo)
            logo.setImageDrawable(null)
            logo.visibility = View.GONE
            binding.tvSwiperTitle.visibility = View.VISIBLE
            return
        }
        logo.visibility = View.VISIBLE
        binding.tvSwiperTitle.visibility = View.GONE
        Glide.with(logo)
            .load(url)
            .fitCenter()
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    if (binding.root.getTag(TITLE_TAG) != title) return false
                    logo.visibility = View.GONE
                    binding.tvSwiperTitle.visibility = View.VISIBLE
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    if (binding.root.getTag(TITLE_TAG) != title) return false
                    logo.visibility = View.VISIBLE
                    binding.tvSwiperTitle.visibility = View.GONE
                    return false
                }
            })
            .into(logo)
    }

    /** Always resolves the TMDb title logo for Featured (catalogue rows rarely ship one). */
    fun resolveAndBindLogo(binding: ItemCategorySwiperMobileBinding, movie: Movie) {
        bindLogo(binding, movie.logo, movie.title)
        resolveLogo(
            binding = binding,
            title = movie.title,
            year = movie.released?.format("yyyy")?.toIntOrNull(),
            isTv = false,
            tmdbId = movie.tmdbId,
            skipIfBound = !movie.logo.isNullOrBlank(),
        ) { movie.logo = it }
    }

    /** Always resolves the TMDb title logo for Featured (catalogue rows rarely ship one). */
    fun resolveAndBindLogo(binding: ItemCategorySwiperMobileBinding, tvShow: TvShow) {
        bindLogo(binding, tvShow.logo, tvShow.title)
        resolveLogo(
            binding = binding,
            title = tvShow.title,
            year = tvShow.released?.format("yyyy")?.toIntOrNull(),
            isTv = true,
            tmdbId = tvShow.tmdbId,
            skipIfBound = !tvShow.logo.isNullOrBlank(),
        ) { tvShow.logo = it }
    }

    private fun resolveLogo(
        binding: ItemCategorySwiperMobileBinding,
        title: String,
        year: Int?,
        isTv: Boolean,
        tmdbId: String?,
        skipIfBound: Boolean,
        onResolved: (String) -> Unit,
    ) {
        if (title.isBlank()) return
        if (skipIfBound) return

        fun start() {
            val owner = binding.root.findViewTreeLifecycleOwner()
            if (owner == null) {
                binding.root.doOnAttach { start() }
                return
            }
            owner.lifecycleScope.launch {
                val logo = withContext(Dispatchers.IO) {
                    runCatching {
                        TmdbUtils.resolveTitleLogo(
                            title = title,
                            year = year,
                            isTv = isTv,
                            tmdbId = tmdbId,
                        )
                    }.getOrNull()
                }
                if (logo.isNullOrBlank()) return@launch
                onResolved(logo)
                if (binding.root.getTag(TITLE_TAG) == title) {
                    bindLogo(binding, logo, title)
                }
            }
        }
        start()
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
}
