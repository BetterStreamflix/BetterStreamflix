package com.dskja.betterstreamflix.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
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
 *
 * Header chrome mirrors hero decode success/fail only — never an independent Glide race
 * that can leave logo in the bar while the cover shows title text (or the reverse).
 */
object DetailHeaderController {

    /** Fade starts only after the overlaid hero logo has scrolled away. */
    private const val SCROLL_START = 160f
    private const val COLLAPSE_RANGE = 260f

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
        val generation = bumpGeneration(root)
        root.findViewById<TextView>(R.id.tv_detail_header_title)?.text = movie.title
        // Title-only until hero Glide confirms — keeps header and cover in lockstep.
        clearTitleChrome(root)
        bindHeroMovie(fragment, root, movie, retries = 4, generation = generation)
        wireDevLogoLongPress(root, movie.title) { movie.logo }
        onScrolled(root, lastScrollY(root))
        // Header list affordance retired — My List lives in the hero CTA row.
        root.findViewById<ImageView>(R.id.btn_detail_list)?.visibility = View.GONE
    }

    fun bindTvShow(fragment: Fragment, root: View, tvShow: TvShow) {
        val generation = bumpGeneration(root)
        root.findViewById<TextView>(R.id.tv_detail_header_title)?.text = tvShow.title
        clearTitleChrome(root)
        bindHeroTvShow(fragment, root, tvShow, retries = 4, generation = generation)
        wireDevLogoLongPress(root, tvShow.title) { tvShow.logo }
        onScrolled(root, lastScrollY(root))
        root.findViewById<ImageView>(R.id.btn_detail_list)?.visibility = View.GONE
    }

    private fun bumpGeneration(root: View): Int {
        val next = ((root.getTag(R.id.detail_header_bind_generation) as? Int) ?: 0) + 1
        root.setTag(R.id.detail_header_bind_generation, next)
        return next
    }

    private fun isCurrentGeneration(root: View, generation: Int): Boolean =
        (root.getTag(R.id.detail_header_bind_generation) as? Int) == generation

    private fun bindHeroMovie(
        fragment: Fragment,
        root: View,
        movie: Movie,
        retries: Int,
        generation: Int,
    ) {
        if (!isCurrentGeneration(root, generation)) return
        val heroLogo = fragment.view?.findViewById<ImageView>(R.id.iv_movie_logo)
        val heroTitle = fragment.view?.findViewById<TextView>(R.id.tv_movie_title)
        if (heroLogo == null) {
            if (retries > 0) {
                fragment.view?.post {
                    bindHeroMovie(fragment, root, movie, retries - 1, generation)
                }
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
                isCurrentGeneration(root, generation) &&
                    root.findViewById<TextView>(R.id.tv_detail_header_title)?.text?.toString() ==
                    movie.title
            },
            onLogoReady = { url ->
                if (!isCurrentGeneration(root, generation)) return@bindAndMaybeResolve
                bindTitleChrome(root, movie.title, url)
                onScrolled(root, lastScrollY(root))
            },
            onLogoFailed = {
                if (!isCurrentGeneration(root, generation)) return@bindAndMaybeResolve
                clearTitleChrome(root)
                onScrolled(root, lastScrollY(root))
            },
        )
    }

    private fun bindHeroTvShow(
        fragment: Fragment,
        root: View,
        tvShow: TvShow,
        retries: Int,
        generation: Int,
    ) {
        if (!isCurrentGeneration(root, generation)) return
        val heroLogo = fragment.view?.findViewById<ImageView>(R.id.iv_tv_show_logo)
        val heroTitle = fragment.view?.findViewById<TextView>(R.id.tv_tv_show_title)
        if (heroLogo == null) {
            if (retries > 0) {
                fragment.view?.post {
                    bindHeroTvShow(fragment, root, tvShow, retries - 1, generation)
                }
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
                isCurrentGeneration(root, generation) &&
                    root.findViewById<TextView>(R.id.tv_detail_header_title)?.text?.toString() ==
                    tvShow.title
            },
            onLogoReady = { url ->
                if (!isCurrentGeneration(root, generation)) return@bindAndMaybeResolve
                bindTitleChrome(root, tvShow.title, url)
                onScrolled(root, lastScrollY(root))
            },
            onLogoFailed = {
                if (!isCurrentGeneration(root, generation)) return@bindAndMaybeResolve
                clearTitleChrome(root)
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

    private fun lastScrollY(root: View): Int =
        (root.getTag(R.id.v_detail_header_scrim) as? Int) ?: 0

    private fun clearTitleChrome(root: View) {
        val logo = root.findViewById<ImageView>(R.id.iv_detail_header_logo) ?: return
        TmdbLogoGlide.clear(logo)
        logo.background = null
        logo.tag = null
        logo.visibility = View.INVISIBLE
        logo.alpha = 0f
    }

    private fun bindTitleChrome(
        root: View,
        title: String,
        logoUrl: String?,
    ) {
        root.findViewById<TextView>(R.id.tv_detail_header_title)?.text = title
        val logo = root.findViewById<ImageView>(R.id.iv_detail_header_logo) ?: return
        if (logoUrl.isNullOrBlank()) {
            clearTitleChrome(root)
            onScrolled(root, lastScrollY(root))
            return
        }
        // Hero already decoded this URL — tag immediately so scroll chrome matches cover
        // even if the smaller header ImageView briefly misses the Glide race.
        logo.tag = logoUrl
        com.dskja.betterstreamflix.logo.TitleLogoPresentation.clearImagePlate(logo)
        com.dskja.betterstreamflix.logo.TitleLogoPresentation.polishLogoImage(logo)
        TmdbLogoGlide.load(
            imageView = logo,
            logoUrl = logoUrl,
            hideUntilReady = false,
            contentDescription = title,
            onFailed = {
                // Only drop this request's tag. A newer bind may already own the slot.
                if (logo.tag == logoUrl) {
                    logo.tag = null
                    logo.setImageDrawable(null)
                    logo.visibility = View.INVISIBLE
                }
                logo.post { onScrolled(root, lastScrollY(root)) }
            },
            onReady = { readyUrl ->
                com.dskja.betterstreamflix.logo.TitleLogoPresentation.clearImagePlate(logo)
                com.dskja.betterstreamflix.logo.TitleLogoPresentation.polishLogoImage(logo)
                logo.tag = readyUrl
                logo.post { onScrolled(root, lastScrollY(root)) }
            },
        )
        onScrolled(root, lastScrollY(root))
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
}
