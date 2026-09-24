package com.dskja.betterstreamflix.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.widget.ImageView
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.loadMovieBanner
import com.dskja.betterstreamflix.utils.loadTvShowBanner

/**
 * Cinematic detail cover: sharp backdrop + softer lower wash (blur on API 31+).
 */
object DetailCoverAtmosphere {

    fun bindMovie(cover: ImageView, soft: ImageView?, movie: Movie) {
        cover.loadMovieBanner(movie, hero = false) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade(320))
        }
        soft?.let { softCover ->
            softCover.loadMovieBanner(movie, hero = false) {
                centerCrop()
                transition(DrawableTransitionOptions.withCrossFade(320))
            }
            applySoftLayer(softCover)
        }
        maybeKenBurns(cover)
    }

    fun bindTvShow(cover: ImageView, soft: ImageView?, tvShow: TvShow) {
        cover.loadTvShowBanner(tvShow, hero = false) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade(320))
        }
        soft?.let { softCover ->
            softCover.loadTvShowBanner(tvShow, hero = false) {
                centerCrop()
                transition(DrawableTransitionOptions.withCrossFade(320))
            }
            applySoftLayer(softCover)
        }
        maybeKenBurns(cover)
    }

    private fun applySoftLayer(soft: ImageView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            soft.alpha = 1f
            soft.setRenderEffect(
                RenderEffect.createBlurEffect(32f, 32f, Shader.TileMode.CLAMP),
            )
        } else {
            soft.alpha = 0.62f
        }
    }

    private fun maybeKenBurns(cover: ImageView) {
        if (!ExperimentalMobileDesign.enabled()) return
        if (cover.getTag(R.id.exp_enter_animated_tag) == true) return
        cover.setTag(R.id.exp_enter_animated_tag, true)
        ExpMotion.kenBurns(cover, drift = !ExperimentalMobileDesign.heroParallax())
    }
}
