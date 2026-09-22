package com.dskja.betterstreamflix.ui

import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
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
 * Shared chrome for the Featured card: centered title logo and Add-to-list CTA state.
 */
object FeaturedSwiperChrome {

    /** Wide enough to stay sharp on the full-width Featured card. */
    const val ARTWORK_WIDTH = 1280
    const val ARTWORK_HEIGHT = 720

    /** Shows the title logo when available, otherwise the plain title text. */
    fun bindLogo(binding: ItemCategorySwiperMobileBinding, logoUrl: String?, title: String) {
        binding.tvSwiperTitle.text = title
        // Title logos stay sharp as original; hero sizing is for backdrops.
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
            .into(logo)
    }

    /** Binds the known logo, and looks one up on TMDb when the catalogue has none. */
    fun resolveAndBindLogo(binding: ItemCategorySwiperMobileBinding, movie: Movie) {
        bindLogo(binding, movie.logo, movie.title)
        if (!movie.logo.isNullOrBlank()) return
        resolveLogo(
            binding = binding,
            title = movie.title,
            year = movie.released?.format("yyyy")?.toIntOrNull(),
            isTv = false,
            tmdbId = movie.tmdbId,
        ) { movie.logo = it }
    }

    /** Binds the known logo, and looks one up on TMDb when the catalogue has none. */
    fun resolveAndBindLogo(binding: ItemCategorySwiperMobileBinding, tvShow: TvShow) {
        bindLogo(binding, tvShow.logo, tvShow.title)
        if (!tvShow.logo.isNullOrBlank()) return
        resolveLogo(
            binding = binding,
            title = tvShow.title,
            year = tvShow.released?.format("yyyy")?.toIntOrNull(),
            isTv = true,
            tmdbId = tvShow.tmdbId,
        ) { tvShow.logo = it }
    }

    private fun resolveLogo(
        binding: ItemCategorySwiperMobileBinding,
        title: String,
        year: Int?,
        isTv: Boolean,
        tmdbId: String?,
        onResolved: (String) -> Unit,
    ) {
        if (title.isBlank()) return
        val owner = binding.root.findViewTreeLifecycleOwner() ?: return
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
            // The pager may have recycled this page onto another title meanwhile.
            if (binding.tvSwiperTitle.text == title) bindLogo(binding, logo, title)
        }
    }

    fun wireWatchButton(button: TextView) {
        val play = ContextCompat.getDrawable(button.context, R.drawable.ic_featured_play)
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(button, play, null, null, null)
    }

    fun bindListButton(button: TextView, inList: Boolean, animate: Boolean = false) {
        button.text = button.context.getString(
            if (inList) R.string.detail_remove_from_list else R.string.detail_add_to_list,
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
