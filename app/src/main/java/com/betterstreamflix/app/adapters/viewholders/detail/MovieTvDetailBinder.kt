package com.betterstreamflix.app.adapters.viewholders.detail

import com.betterstreamflix.app.models.TrailerCatalog
import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.betterstreamflix.app.R
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.adapters.viewholders.MovieViewHolder
import com.betterstreamflix.app.databinding.ContentMovieMobileBinding
import com.betterstreamflix.app.databinding.ContentMovieTvBinding
import com.betterstreamflix.app.download.OfflineBadgeStore
import com.betterstreamflix.app.download.ui.DownloadOptionsController
import com.betterstreamflix.app.fragments.movie.MovieMobileFragmentDirections
import com.betterstreamflix.app.fragments.movie.MovieTvFragment
import com.betterstreamflix.app.fragments.movie.MovieTvFragmentDirections
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.ui.DetailTab
import com.betterstreamflix.app.ui.FeaturedSwiperChrome
import com.betterstreamflix.app.ui.DetailTrailerPlayHooks
import com.betterstreamflix.app.ui.TrailerPlayPipeline
import com.betterstreamflix.app.ui.TrailerPlaybackController
import com.betterstreamflix.app.utils.ArtworkRepair
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.ExpPressEffects.applyExpPress
import com.betterstreamflix.app.utils.TmdbUtils
import com.betterstreamflix.app.utils.format
import com.betterstreamflix.app.utils.getCurrentFragment
import com.betterstreamflix.app.utils.loadMoviePoster
import com.betterstreamflix.app.utils.toActivity
import com.betterstreamflix.app.utils.UserPreferences
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.betterstreamflix.app.download.DownloadContentKey
import com.betterstreamflix.app.download.DetailDownloadLabels
import com.betterstreamflix.app.ui.DetailRating
import com.betterstreamflix.app.ui.DetailWatchLabels

internal fun MovieViewHolder.bindMovieTvDetail(binding: ContentMovieTvBinding) {
    DetailParentalGate.guardMovie(
        itemView,
        movie,
        binding.btnMovieWatchNow,
        binding.btnMovieDownload,
    )
    binding.ivMoviePoster.run {
        loadMoviePoster(movie) {
            transition(DrawableTransitionOptions.withCrossFade())
        }
    }

    binding.tvMovieTitle.text = movie.title
    fun applyTvBrandChrome(logoReady: Boolean) {
        // Logo-first Leanback hero: banner owns the stage; poster only when title text falls back.
        binding.ivMoviePoster.visibility = when {
            logoReady -> View.GONE
            movie.poster.isNullOrEmpty() -> View.GONE
            else -> View.VISIBLE
        }
        binding.ivMoviePoster.alpha = if (logoReady) 0f else 0.92f
    }
    applyTvBrandChrome(logoReady = false)
    com.betterstreamflix.app.logo.TitleLogoSurface.bindAndMaybeResolve(
        anchor = binding.root,
        imageView = binding.ivMovieLogo,
        titleView = binding.tvMovieTitle,
        movie = movie,
        persist = true,
        allowAlternateOnFail = true,
        onLogoReady = { applyTvBrandChrome(logoReady = true) },
        onLogoFailed = { applyTvBrandChrome(logoReady = false) },
    )

    binding.tvMovieRating.apply {
        text = com.betterstreamflix.app.ui.DetailRating.format(movie.rating)
        visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
    binding.ivMovieRatingIcon.visibility = binding.tvMovieRating.visibility

    binding.tvMovieCertification.apply {
        val cert = movie.contentRating?.trim().orEmpty()
        val digits = cert.filter { it.isDigit() }
        val badge = when {
            digits.isNotEmpty() -> digits.take(2)
            cert.isNotEmpty() -> cert.take(3).uppercase(Locale.getDefault())
            else -> ""
        }
        text = badge
        visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
    }

    binding.tvMovieQuality.apply {
        text = movie.quality
        visibility = when {
            text.isNullOrEmpty() -> View.GONE
            else -> View.VISIBLE
        }
    }

    binding.tvMovieReleased.apply {
        text = movie.released?.format("yyyy")
        visibility = when {
            text.isNullOrEmpty() -> View.GONE
            else -> View.VISIBLE
        }
    }

    binding.tvMovieRuntime.apply {
        text = movie.runtime?.let {
            val hours = it / 60
            val minutes = it % 60
            when {
                hours > 0 -> context.getString(
                    R.string.movie_runtime_hours_minutes,
                    hours,
                    minutes
                )
                else -> context.getString(R.string.movie_runtime_minutes, minutes)
            }
        }
        visibility = when {
            text.isNullOrEmpty() -> View.GONE
            else -> View.VISIBLE
        }
    }

    binding.tvMovieGenres.apply {
        if (movie.genres.isEmpty()) {
            text = ""
            visibility = View.GONE
            isFocusable = false
            setOnClickListener(null)
        } else {
            text = movie.genres.joinToString(" · ") { it.name }
            visibility = View.VISIBLE
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = context.getString(R.string.genre_section_label)
            setOnClickListener { view ->
                ExpMotion.hapticTap(view)
                val genres = movie.genres
                fun openGenre(genre: com.betterstreamflix.app.models.Genre) {
                    checkProviderAndRun {
                        if (context.toActivity()?.getCurrentFragment() is MovieTvFragment) {
                            findNavController().navigate(
                                MovieTvFragmentDirections.actionMovieToGenre(
                                    id = genre.id,
                                    name = genre.name,
                                ),
                            )
                        }
                    }
                }
                if (genres.size == 1) {
                    openGenre(genres.first())
                } else {
                    // Leanback single-choice (Search year pattern) — not a raw AlertDialog list.
                    val labels = genres.map { it.name }.toTypedArray()
                    AlertDialog.Builder(com.betterstreamflix.app.utils.ExpDialogChrome.themedContext(context))
                        .setTitle(R.string.genre_section_label)
                        .setItems(labels) { _, which ->
                            genres.getOrNull(which)?.let(::openGenre)
                        }
                        .create()
                        .also { dialog ->
                            dialog.setOnShowListener {
                                com.betterstreamflix.app.utils.ExpDialogChrome.polishShown(dialog)
                                dialog.listView?.apply {
                                    isFocusable = true
                                    isFocusableInTouchMode = true
                                    requestFocus()
                                }
                            }
                            dialog.show()
                        }
                }
            }
        }
    }

    binding.tvMovieOverview.apply {
        text = movie.overview
        val collapsedLines = 5
        maxLines = collapsedLines
        ellipsize = android.text.TextUtils.TruncateAt.END
        var expanded = false
        fun applyExpand(open: Boolean) {
            expanded = open
            maxLines = if (open) Integer.MAX_VALUE else collapsedLines
            ellipsize = if (open) null else android.text.TextUtils.TruncateAt.END
        }
        if (!movie.overview.isNullOrBlank()) {
            visibility = View.VISIBLE
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setOnClickListener {
                ExpMotion.hapticTap(it)
                applyExpand(!expanded)
            }
            post {
                val overflowing = lineCount > collapsedLines || (text?.length ?: 0) > 220
                if (!overflowing) {
                    isFocusable = false
                    isClickable = false
                    setOnClickListener(null)
                    maxLines = Integer.MAX_VALUE
                    ellipsize = null
                }
            }
        } else {
            text = ""
            visibility = View.GONE
            isFocusable = false
            setOnClickListener(null)
        }
        fun rewireOverviewWatch() {
            com.betterstreamflix.app.utils.TvFocusChain.linkVertical(
                *listOfNotNull(
                    binding.tvMovieOverview.takeIf { it.isFocusable && it.isVisible },
                    binding.tvMovieGenres.takeIf { it.isFocusable && it.isVisible },
                    binding.btnMovieWatchNow,
                ).toTypedArray(),
            )
        }
        rewireOverviewWatch()
        post { rewireOverviewWatch() }
    }

    binding.btnMovieWatchNow.apply {
        // Dual CTA: Watch streams online; completed downloads use the Download button.
        text = DetailWatchLabels.movie(context, movie)
        setOnClickListener {
            ExpMotion.hapticTap(it)
            checkProviderAndRun {
                findNavController().navigate(MovieTvFragmentDirections.actionMovieToPlayer(
                    id = movie.id,
                    title = movie.title,
                    subtitle = movie.released?.format("yyyy") ?: "",
                    videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: movie.banner ?: "", imdbId = movie.imdbId),
                    preferredServerName = null,
                ))
            }
        }
    }

    binding.pbMovieProgress.apply {
        val percent = DetailWatchLabels.progressPercent(movie.watchHistory)
        progress = percent
        visibility = if (percent in 1..95) View.VISIBLE else View.GONE
    }

    fun rewireTvCtaFocus() {
        val watched = binding.root.findViewById<View>(R.id.btn_movie_watched)
        val share = binding.root.findViewById<View>(R.id.btn_movie_share)
        com.betterstreamflix.app.utils.TvFocusChain.linkHorizontal(
            binding.btnMovieWatchNow,
            binding.btnMovieFavorite,
            binding.btnMovieTrailer,
            binding.btnMovieDownload,
        )
        val secondary = listOfNotNull(watched, share)
        if (secondary.isNotEmpty()) {
            com.betterstreamflix.app.utils.TvFocusChain.linkHorizontal(*secondary.toTypedArray())
        }
        val downTarget = listOfNotNull(watched, share).firstOrNull {
            it.visibility == View.VISIBLE && it.isFocusable
        }
        com.betterstreamflix.app.utils.TvFocusChain.linkDown(
            listOf(
                binding.btnMovieWatchNow,
                binding.btnMovieFavorite,
                binding.btnMovieTrailer,
                binding.btnMovieDownload,
            ),
            downTarget,
        )
        listOfNotNull(watched, share).forEach { button ->
            if (button.visibility == View.VISIBLE) {
                button.nextFocusUpId = binding.btnMovieWatchNow.id
            }
        }
    }

    binding.btnMovieTrailer.apply {
        val year = movie.released?.format("yyyy")?.toIntOrNull()
        val canLookup = TmdbUtils.hasTrailerLookupKeys(
            tmdbId = movie.tmdbId,
            imdbId = movie.imdbId,
            title = movie.title,
            year = year,
        )
        fun bindTrailer(trailerUrl: String?) {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                if (!trailerUrl.isNullOrBlank()) {
                    val session = TrailerPlayPipeline.Session(
                        target = DetailTrailerPlayHooks.movieSession(
                            movie = movie,
                            host = binding.root,
                            leanback = true,
                        ).target,
                        onStreamNow = {
                            checkProviderAndRun {
                                DetailTrailerPlayHooks.movieSession(
                                    movie = movie,
                                    host = binding.root,
                                    leanback = true,
                                ).onStreamNow?.invoke()
                            }
                        },
                    )
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                    if (fragment != null) {
                        TrailerPlaybackController.play(fragment, trailerUrl, session)
                    } else {
                        TrailerPlayPipeline.beginSession(session)
                        handleTrailerClick(trailerUrl, "MovieTv")
                    }
                }
            }
            // Hide until a playable URL is ready — avoids dead clicks while lookup runs.
            visibility = if (!trailerUrl.isNullOrBlank()) View.VISIBLE else View.GONE
            isFocusable = !trailerUrl.isNullOrBlank()
            isFocusableInTouchMode = isFocusable
            rewireTvCtaFocus()
        }
        bindTrailer(movie.trailer)
        rewireTvCtaFocus()
        if (movie.trailer.isNullOrBlank() && canLookup) {
            itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                val remote = withContext(Dispatchers.IO) {
                    TmdbUtils.listYoutubeTrailers(
                        tmdbId = movie.tmdbId,
                        isTv = false,
                        title = movie.title,
                        year = year,
                        imdbId = movie.imdbId,
                    )
                }
                val first = TrailerCatalog.preferredPlayableUrl(remote)
                if (!first.isNullOrBlank() && movie.trailer.isNullOrBlank()) {
                    movie.trailer = first
                    bindTrailer(first)
                }
            }
        }
    }

    binding.btnMovieDownload.apply {
        if (isIptvProvider()) {
            visibility = View.GONE
            setOnClickListener(null)
            setOnLongClickListener(null)
        } else {
            visibility = View.VISIBLE
            val contentKey = movieDownloadContentKey()
            val playOffline = contentKey != null && OfflineBadgeStore.isCompleted(context, contentKey)
            text = if (playOffline) {
                context.getString(R.string.downloads_play_offline)
            } else {
                com.betterstreamflix.app.download.DetailDownloadLabels.movieButton(context, movie)
            }
            contentDescription = text
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    if (playOffline) {
                        findNavController().navigate(
                            MovieTvFragmentDirections.actionMovieToPlayer(
                                id = movie.id,
                                title = movie.title,
                                subtitle = movie.released?.format("yyyy") ?: "",
                                videoType = Video.Type.Movie(
                                    id = movie.id,
                                    title = movie.title,
                                    releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                                    poster = movie.poster ?: movie.banner ?: "",
                                    imdbId = movie.imdbId,
                                ),
                                preferredServerName =
                                    com.betterstreamflix.app.fragments.player.PlayerViewModel.OFFLINE_SERVER_NAME,
                            ),
                        )
                    } else {
                        val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                            ?: return@checkProviderAndRun
                        DownloadOptionsController.enqueueMovie(fragment, movie)
                    }
                }
            }
            setOnLongClickListener {
                if (!playOffline) return@setOnLongClickListener false
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                        ?: return@checkProviderAndRun
                    DownloadOptionsController.enqueueMovie(fragment, movie)
                }
                true
            }
        }
    }

    binding.root.findViewById<TextView>(R.id.btn_movie_watched)?.apply {
        text = if (movie.isWatched) {
            context.getString(R.string.option_show_unwatched)
        } else {
            context.getString(R.string.option_show_watched)
        }
        setOnClickListener {
            ExpMotion.hapticTap(it)
            checkProviderAndRun {
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                    val db = database ?: return@launch
                    val dao = db.movieDao()
                    val current = dao.getById(movie.id)
                    val target = !(current?.isWatched ?: movie.isWatched)
                    val updated = (current ?: movie).copy().apply {
                        isWatched = target
                        watchedDate = if (target) java.util.Calendar.getInstance() else null
                    }
                    dao.save(updated)
                    withContext(Dispatchers.Main) {
                        movie.isWatched = target
                        text = if (target) {
                            context.getString(R.string.option_show_unwatched)
                        } else {
                            context.getString(R.string.option_show_watched)
                        }
                    }
                }
            }
        }
    }

    binding.root.findViewById<View>(R.id.btn_movie_share)?.let { shareBtn ->
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, movie.title)
            putExtra(
                Intent.EXTRA_TEXT,
                buildString {
                    append(movie.title)
                    movie.released?.format("yyyy")?.let { year -> append(" ($year)") }
                    movie.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                        append("\n\n").append(overview.take(280))
                    }
                    movie.trailer?.let { trailer -> append("\n").append(trailer) }
                },
            )
        }
        val canShare = share.resolveActivity(context.packageManager) != null
        if (canShare || com.betterstreamflix.app.sync.CastRoomSession.isActive()) {
            shareBtn.visibility = View.VISIBLE
            shareBtn.setOnClickListener {
                ExpMotion.hapticTap(it)
                val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                val videoType = Video.Type.Movie(
                    id = movie.id,
                    title = movie.title,
                    releaseDate = movie.released?.format("yyyy").orEmpty(),
                    poster = movie.poster.orEmpty(),
                    imdbId = movie.imdbId,
                )
                val doShare = {
                    if (canShare) {
                        runCatching { context.startActivity(share) }
                            .onFailure {
                                context.startActivity(
                                    Intent.createChooser(
                                        share,
                                        context.getString(R.string.detail_share),
                                    ),
                                )
                            }
                    }
                }
                if (fragment != null) {
                    com.betterstreamflix.app.sync.CastRoomUi.maybeAugmentShareClick(
                        fragment,
                        videoType,
                        doShare,
                    )
                } else {
                    doShare()
                }
            }
        } else {
            shareBtn.visibility = View.GONE
            shareBtn.setOnClickListener(null)
        }
    }

    binding.btnMovieFavorite.apply {
        fun applyFavoriteState(inList: Boolean) {
            text = context.getString(
                if (inList) R.string.home_swiper_in_my_list else R.string.detail_action_list,
            )
            contentDescription = context.getString(
                if (inList) R.string.detail_remove_from_list else R.string.detail_add_to_list,
            )
        }

        setOnClickListener {
            ExpMotion.hapticTap(it)
            checkProviderAndRun {
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                    val provider = UserPreferences.currentProvider ?: return@launch
                    val db = database ?: return@launch
                    val newValue = !(db.movieDao().getById(movie.id)?.isFavorite ?: false)
                    val resolvedMovie = com.betterstreamflix.app.sync.FavoriteMutations.setMovieFavorite(
                        context = context,
                        provider = provider,
                        movie = movie,
                        favorite = newValue,
                        database = db,
                    )

                    withContext(Dispatchers.Main) {
                        movie.poster = resolvedMovie.poster
                        movie.banner = resolvedMovie.banner
                        movie.isFavorite = newValue
                        applyFavoriteState(newValue)
                        ExpMotion.popIn(binding.btnMovieFavorite)
                    }
                }
            }
        }

        applyFavoriteState(movie.isFavorite)
    }

    rewireTvCtaFocus()
}


