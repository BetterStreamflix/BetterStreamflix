package com.dskja.betterstreamflix.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.ArtworkUrls
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.loadMovieBanner
import com.dskja.betterstreamflix.utils.loadTvShowBanner

/**
 * Cinematic detail cover: sharp backdrop + soft lower wash.
 * Soft blur decode runs only on API 31+ to avoid a second full-res bitmap on older devices.
 */
object DetailCoverAtmosphere {

    fun bindMovie(cover: ImageView, soft: ImageView?, movie: Movie) {
        val sharpUrl = ArtworkUrls.bannerOrPoster(movie.banner, movie.poster, hero = false)
        if (cover.getTag(R.id.detail_cover_url_tag) != sharpUrl) {
            cover.setTag(R.id.detail_cover_url_tag, sharpUrl)
            cover.loadMovieBanner(movie, hero = false) {
                centerCrop()
                transition(DrawableTransitionOptions.withCrossFade(320))
            }
        }
        bindSoft(soft, softUrlKey = ArtworkUrls.bannerOrPoster(movie.banner, movie.poster, hero = true)) { softCover ->
            // Soft wash uses w1280 — cheaper decode under the blur.
            softCover.loadMovieBanner(movie, hero = true) {
                centerCrop()
                transition(DrawableTransitionOptions.withCrossFade(320))
            }
        }
        maybeKenBurns(cover)
    }

    fun bindTvShow(cover: ImageView, soft: ImageView?, tvShow: TvShow) {
        val sharpUrl = ArtworkUrls.bannerOrPoster(tvShow.banner, tvShow.poster, hero = false)
        if (cover.getTag(R.id.detail_cover_url_tag) != sharpUrl) {
            cover.setTag(R.id.detail_cover_url_tag, sharpUrl)
            cover.loadTvShowBanner(tvShow, hero = false) {
                centerCrop()
                transition(DrawableTransitionOptions.withCrossFade(320))
            }
        }
        bindSoft(soft, softUrlKey = ArtworkUrls.bannerOrPoster(tvShow.banner, tvShow.poster, hero = true)) { softCover ->
            softCover.loadTvShowBanner(tvShow, hero = true) {
                centerCrop()
                transition(DrawableTransitionOptions.withCrossFade(320))
            }
        }
        maybeKenBurns(cover)
    }

    private fun bindSoft(soft: ImageView?, softUrlKey: String?, load: (ImageView) -> Unit) {
        soft ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            soft.visibility = View.VISIBLE
            soft.alpha = 0.38f
            if (soft.getTag(R.id.detail_cover_url_tag) != softUrlKey) {
                soft.setTag(R.id.detail_cover_url_tag, softUrlKey)
                load(soft)
            }
            if (soft.getTag(R.id.detail_cover_blur_tag) != true) {
                soft.setRenderEffect(
                    RenderEffect.createBlurEffect(22f, 22f, Shader.TileMode.CLAMP),
                )
                soft.setTag(R.id.detail_cover_blur_tag, true)
            }
        } else {
            // Skip the second decode; veil alone handles the fade into tabs.
            soft.setImageDrawable(null)
            soft.visibility = View.GONE
        }
    }

    private fun maybeKenBurns(cover: ImageView) {
        if (!ExperimentalMobileDesign.enabled()) return
        if (cover.getTag(R.id.exp_enter_animated_tag) == true) return
        cover.setTag(R.id.exp_enter_animated_tag, true)
        ExpMotion.kenBurns(cover, drift = !ExperimentalMobileDesign.heroParallax())
    }
}
