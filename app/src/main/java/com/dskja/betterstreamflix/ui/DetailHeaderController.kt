package com.dskja.betterstreamflix.ui

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.dskja.betterstreamflix.R
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
 * Overlay chrome for movie / TV detail pages.
 *
 * At the top of the page only Back + Cast float over the hero.
 * After the user scrolls past the hero logo, a soft bar fades in with the TMDb title logo.
 */
object DetailHeaderController {

    /** Fade starts only after the hero logo has scrolled away. */
    private const val SCROLL_START = 120f
    private const val COLLAPSE_RANGE = 280f

    fun wireBack(root: View) {
        val back = root.findViewById<View>(R.id.iv_detail_back) ?: return
        back.background = null
        TooltipCompat.setTooltipText(back, back.context.getString(R.string.exp_back))
        back.setOnClickListener {
            ExpMotion.hapticTap(it)
            androidx.navigation.Navigation.findNavController(root).navigateUp()
        }
    }


    fun wireCast(fragment: Fragment, root: View) {
        val button = root.findViewById<androidx.mediarouter.app.MediaRouteButton>(R.id.btn_detail_cast) ?: return
        runCatching {
            com.dskja.betterstreamflix.cast.CastPlaybackHub.ensureCastContext(fragment.requireContext())
            com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(
                fragment.requireContext(),
                button,
            )
            button.visibility = View.VISIBLE
        }.onFailure {
            button.visibility = View.GONE
        }
    }

    fun bindMovie(fragment: Fragment, root: View, movie: Movie) {
        bindTitleChrome(root, movie.title, movie.logo)
        if (movie.logo.isNullOrBlank()) {
            resolveLogo(
                fragment = fragment,
                root = root,
                title = movie.title,
                year = movie.released?.format("yyyy")?.toIntOrNull(),
                isTv = false,
                tmdbId = movie.tmdbId,
                imdbId = movie.imdbId,
            ) { movie.logo = it }
        }
        onScrolled(root, 0)
        root.findViewById<ImageView>(R.id.btn_detail_list)?.visibility = View.GONE
    }

    fun bindTvShow(fragment: Fragment, root: View, tvShow: TvShow) {
        bindTitleChrome(root, tvShow.title, tvShow.logo)
        if (tvShow.logo.isNullOrBlank()) {
            resolveLogo(
                fragment = fragment,
                root = root,
                title = tvShow.title,
                year = tvShow.released?.format("yyyy")?.toIntOrNull(),
                isTv = true,
                tmdbId = tvShow.tmdbId,
                imdbId = tvShow.imdbId,
            ) { tvShow.logo = it }
        }
        onScrolled(root, 0)
        root.findViewById<ImageView>(R.id.btn_detail_list)?.visibility = View.GONE
    }

    /** Soft bar + centered logo fade in only after the hero scrolls away. */
    fun onScrolled(root: View, scrollY: Int) {
        root.setTag(R.id.v_detail_header_scrim, scrollY)
        val progress = ((scrollY - SCROLL_START) / COLLAPSE_RANGE).coerceIn(0f, 1f)
        root.findViewById<View>(R.id.v_detail_header_scrim)?.alpha = progress
        val logo = root.findViewById<ImageView>(R.id.iv_detail_header_logo)
        val title = root.findViewById<TextView>(R.id.tv_detail_header_title)
        if (progress == 0f) {
            // INVISIBLE (not VISIBLE+alpha0) — some devices still composite alpha-0 images.
            logo?.alpha = 0f
            logo?.visibility = View.INVISIBLE
            title?.alpha = 0f
            title?.visibility = View.INVISIBLE
            return
        }
        val showLogo = logo?.tag == true
        if (showLogo) {
            logo?.visibility = View.VISIBLE
            logo?.alpha = progress
            title?.visibility = View.INVISIBLE
            title?.alpha = 0f
        } else {
            logo?.visibility = View.INVISIBLE
            logo?.alpha = 0f
            title?.visibility = View.VISIBLE
            title?.alpha = progress
        }
    }

    fun refreshListState(root: View, inList: Boolean) {
        root.findViewById<ImageView>(R.id.btn_detail_list)
            ?.let { applyListState(it, inList, animate = false) }
    }

    private fun lastScrollY(root: View): Int =
        (root.getTag(R.id.v_detail_header_scrim) as? Int) ?: 0

    private fun resolveLogo(
        fragment: Fragment,
        root: View,
        title: String,
        year: Int?,
        isTv: Boolean,
        tmdbId: String?,
        imdbId: String? = null,
        onResolved: (String) -> Unit,
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val logo = withContext(Dispatchers.IO) {
                runCatching {
                    TmdbUtils.resolveTitleLogo(
                        title = title,
                        year = year,
                        isTv = isTv,
                        tmdbId = tmdbId,
                        imdbId = imdbId,
                    )
                }.getOrNull()
            }
            if (logo.isNullOrBlank()) return@launch
            onResolved(logo)
            val currentTitle = root.findViewById<TextView>(R.id.tv_detail_header_title)?.text?.toString()
            if (currentTitle == title) {
                bindTitleChrome(root, title, logo)
                bindHeroLogo(fragment.view, isTv, logo)
                onScrolled(root, lastScrollY(root))
            }
        }
    }

    private fun bindHeroLogo(fragmentView: View?, isTv: Boolean, logoUrl: String) {
        val heroId = if (isTv) R.id.iv_tv_show_logo else R.id.iv_movie_logo
        val titleId = if (isTv) R.id.tv_tv_show_title else R.id.tv_movie_title
        val logoView = fragmentView?.findViewById<ImageView>(heroId) ?: return
        val titleView = fragmentView.findViewById<TextView>(titleId)
        logoView.visibility = View.VISIBLE
        titleView?.visibility = View.GONE
        loadLogoWithFallback(
            imageView = logoView,
            logoUrl = logoUrl,
            preferHeroFirst = false,
            onFailed = {
                logoView.visibility = View.GONE
                titleView?.visibility = View.VISIBLE
            },
            onReady = {
                logoView.visibility = View.VISIBLE
                titleView?.visibility = View.GONE
            },
        )
    }

    private fun bindTitleChrome(root: View, title: String, logoUrl: String?) {
        root.findViewById<TextView>(R.id.tv_detail_header_title)?.text = title
        val logo = root.findViewById<ImageView>(R.id.iv_detail_header_logo) ?: return
        val primary = ArtworkUrls.preferHero(logoUrl) ?: ArtworkUrls.preferOriginal(logoUrl)
        if (primary.isNullOrBlank()) {
            logo.setImageDrawable(null)
            logo.tag = false
            onScrolled(root, lastScrollY(root))
            return
        }
        loadLogoWithFallback(
            imageView = logo,
            logoUrl = logoUrl,
            preferHeroFirst = true,
            onFailed = {
                logo.tag = false
                logo.post { onScrolled(root, lastScrollY(root)) }
            },
            onReady = {
                logo.tag = true
                // Re-apply after Glide finishes so a load at scrollY=0 stays invisible.
                logo.post { onScrolled(root, lastScrollY(root)) }
            },
        )
    }

    private fun loadLogoWithFallback(
        imageView: ImageView,
        logoUrl: String?,
        preferHeroFirst: Boolean,
        onFailed: () -> Unit,
        onReady: () -> Unit,
    ) {
        val primary = if (preferHeroFirst) {
            ArtworkUrls.preferHero(logoUrl) ?: ArtworkUrls.preferOriginal(logoUrl)
        } else {
            ArtworkUrls.preferOriginal(logoUrl) ?: ArtworkUrls.preferHero(logoUrl)
        }
        val alternate = if (preferHeroFirst) {
            ArtworkUrls.preferOriginal(logoUrl)
        } else {
            ArtworkUrls.preferHero(logoUrl)
        }
        fun load(url: String?, isRetry: Boolean) {
            if (url.isNullOrBlank()) {
                onFailed()
                return
            }
            Glide.with(imageView)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .fitCenter()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean {
                        val retryUrl = alternate?.takeIf { !isRetry && it != url }
                        if (retryUrl != null) {
                            load(retryUrl, true)
                            return true
                        }
                        onFailed()
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        onReady()
                        return false
                    }
                })
                .into(imageView)
        }
        load(primary, false)
    }

    private fun applyListState(icon: ImageView, inList: Boolean, animate: Boolean) {
        icon.setImageDrawable(
            ContextCompat.getDrawable(
                icon.context,
                if (inList) R.drawable.ic_list_added else R.drawable.ic_list_add,
            )
        )
        val description = icon.context.getString(
            if (inList) R.string.detail_remove_from_list else R.string.detail_add_to_list,
        )
        icon.contentDescription = description
        TooltipCompat.setTooltipText(icon, description)
        if (animate) ExpMotion.softScale(icon)
    }
}
