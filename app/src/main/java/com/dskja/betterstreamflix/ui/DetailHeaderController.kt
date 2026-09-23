package com.dskja.betterstreamflix.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.logo.TitleLogoSurface
import com.dskja.betterstreamflix.logo.TmdbLogoGlide
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.ExpMotion

/**
 * Overlay chrome for movie / TV detail pages.
 *
 * Owns both the collapsing header logo and the hero title logo on the detail body.
 * After the user scrolls past the hero logo, a soft bar fades in with the TMDb title logo.
 * Resolve/bind/persist goes through [TitleLogoSurface] (shared with Featured + TV).
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
        root.findViewById<TextView>(R.id.tv_detail_header_title)?.text = movie.title
        bindTitleChrome(root, movie.title, movie.logo)
        bindHeroMovie(fragment, root, movie, retries = 2)
        wireDevLogoLongPress(root, movie.title) { movie.logo }
        onScrolled(root, lastScrollY(root))
        root.findViewById<ImageView>(R.id.btn_detail_list)?.visibility = View.GONE
    }

    fun bindTvShow(fragment: Fragment, root: View, tvShow: TvShow) {
        root.findViewById<TextView>(R.id.tv_detail_header_title)?.text = tvShow.title
        bindTitleChrome(root, tvShow.title, tvShow.logo)
        bindHeroTvShow(fragment, root, tvShow, retries = 2)
        wireDevLogoLongPress(root, tvShow.title) { tvShow.logo }
        onScrolled(root, lastScrollY(root))
        root.findViewById<ImageView>(R.id.btn_detail_list)?.visibility = View.GONE
    }

    private fun bindHeroMovie(fragment: Fragment, root: View, movie: Movie, retries: Int) {
        val heroLogo = fragment.view?.findViewById<ImageView>(R.id.iv_movie_logo)
        val heroTitle = fragment.view?.findViewById<TextView>(R.id.tv_movie_title)
        if (heroLogo == null) {
            if (retries > 0) {
                fragment.view?.post { bindHeroMovie(fragment, root, movie, retries - 1) }
            }
            return
        }
        TitleLogoSurface.bindAndMaybeResolve(
            anchor = fragment.view ?: root,
            imageView = heroLogo,
            titleView = heroTitle,
            movie = movie,
            persist = true,
            allowAlternateOnFail = true,
            stillCurrent = {
                root.findViewById<TextView>(R.id.tv_detail_header_title)?.text?.toString() ==
                    movie.title
            },
            onResolved = { url, _ ->
                bindTitleChrome(root, movie.title, url)
                onScrolled(root, lastScrollY(root))
            },
        )
    }

    private fun bindHeroTvShow(fragment: Fragment, root: View, tvShow: TvShow, retries: Int) {
        val heroLogo = fragment.view?.findViewById<ImageView>(R.id.iv_tv_show_logo)
        val heroTitle = fragment.view?.findViewById<TextView>(R.id.tv_tv_show_title)
        if (heroLogo == null) {
            if (retries > 0) {
                fragment.view?.post { bindHeroTvShow(fragment, root, tvShow, retries - 1) }
            }
            return
        }
        TitleLogoSurface.bindAndMaybeResolve(
            anchor = fragment.view ?: root,
            imageView = heroLogo,
            titleView = heroTitle,
            tvShow = tvShow,
            persist = true,
            allowAlternateOnFail = true,
            stillCurrent = {
                root.findViewById<TextView>(R.id.tv_detail_header_title)?.text?.toString() ==
                    tvShow.title
            },
            onResolved = { url, _ ->
                bindTitleChrome(root, tvShow.title, url)
                onScrolled(root, lastScrollY(root))
            },
        )
    }

    /** Soft bar + centered logo fade in only after the hero logo has scrolled away. */
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
        val showLogo = (logo?.tag as? String)?.isNotBlank() == true
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

    private fun bindTitleChrome(
        root: View,
        title: String,
        logoUrl: String?,
    ) {
        root.findViewById<TextView>(R.id.tv_detail_header_title)?.text = title
        val logo = root.findViewById<ImageView>(R.id.iv_detail_header_logo) ?: return
        if (logoUrl.isNullOrBlank()) {
            TmdbLogoGlide.clear(logo)
            logo.background = null
            logo.tag = null
            onScrolled(root, lastScrollY(root))
            return
        }
        TmdbLogoGlide.load(
            imageView = logo,
            logoUrl = logoUrl,
            hideUntilReady = false,
            contentDescription = title,
            onFailed = {
                // Decode fail on chrome: hide logo chrome; hero path already runs alternate resolve.
                logo.background = null
                logo.tag = null
                TmdbLogoGlide.clear(logo)
                logo.post { onScrolled(root, lastScrollY(root)) }
            },
            onReady = { readyUrl ->
                logo.setBackgroundResource(R.drawable.bg_title_logo_contrast)
                logo.tag = readyUrl
                // Re-apply after Glide finishes so a load at scrollY=0 stays invisible.
                logo.post { onScrolled(root, lastScrollY(root)) }
            },
        )
    }

    private fun wireDevLogoLongPress(root: View, title: String, logoUrl: () -> String?) {
        if (!com.dskja.betterstreamflix.BuildConfig.DEBUG) return
        val logo = root.findViewById<ImageView>(R.id.iv_detail_header_logo) ?: return
        logo.setOnLongClickListener {
            val url = logoUrl().orEmpty().ifBlank { "(none)" }
            android.widget.Toast.makeText(
                root.context,
                "Logo[$title]: $url",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            true
        }
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
