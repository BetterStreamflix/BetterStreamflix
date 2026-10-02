package com.dskja.betterstreamflix.adapters.viewholders.detail

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
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.adapters.viewholders.MovieViewHolder
import com.dskja.betterstreamflix.databinding.ContentMovieMobileBinding
import com.dskja.betterstreamflix.databinding.ContentMovieTvBinding
import com.dskja.betterstreamflix.download.OfflineBadgeStore
import com.dskja.betterstreamflix.download.ui.DownloadOptionsController
import com.dskja.betterstreamflix.fragments.movie.MovieMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.movie.MovieTvFragment
import com.dskja.betterstreamflix.fragments.movie.MovieTvFragmentDirections
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.ui.DetailTab
import com.dskja.betterstreamflix.ui.FeaturedSwiperChrome
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
import com.dskja.betterstreamflix.utils.ArtworkRepair
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.getCurrentFragment
import com.dskja.betterstreamflix.utils.loadMoviePoster
import com.dskja.betterstreamflix.utils.toActivity
import com.dskja.betterstreamflix.utils.UserPreferences
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dskja.betterstreamflix.download.DownloadContentKey
import com.dskja.betterstreamflix.download.DetailDownloadLabels
import com.dskja.betterstreamflix.ui.DetailRating

internal fun MovieViewHolder.bindMovieTvDetail(binding: ContentMovieTvBinding) {
    binding.ivMoviePoster.run {
        loadMoviePoster(movie) {
            transition(DrawableTransitionOptions.withCrossFade())
        }
        visibility = when {
            movie.poster.isNullOrEmpty() -> View.GONE
            else -> View.VISIBLE
        }
    }

    binding.tvMovieTitle.text = movie.title
    com.dskja.betterstreamflix.logo.TitleLogoSurface.bindAndMaybeResolve(
        anchor = binding.root,
        imageView = binding.ivMovieLogo,
        titleView = binding.tvMovieTitle,
        movie = movie,
        persist = true,
        allowAlternateOnFail = true,
    )

    binding.tvMovieRating.apply {
        text = com.dskja.betterstreamflix.ui.DetailRating.format(movie.rating)
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
            text = movie.genres.joinToString(", ") { it.name }
            visibility = View.VISIBLE
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = context.getString(R.string.genre_section_label)
            setOnClickListener { view ->
                ExpMotion.hapticTap(view)
                val genres = movie.genres
                fun openGenre(genre: com.dskja.betterstreamflix.models.Genre) {
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
                    val labels = genres.map { it.name }.toTypedArray()
                    AlertDialog.Builder(context)
                        .setTitle(R.string.genre_section_label)
                        .setItems(labels) { _, which ->
                            genres.getOrNull(which)?.let(::openGenre)
                        }
                        .show()
                }
            }
            nextFocusDownId = binding.btnMovieWatchNow.id
            binding.btnMovieWatchNow.nextFocusUpId = id
        }
    }

    binding.tvMovieOverview.apply {
        text = movie.overview
        val collapsedLines = 4
        maxLines = collapsedLines
        ellipsize = android.text.TextUtils.TruncateAt.END
        var expanded = false
        fun applyExpand(open: Boolean) {
            expanded = open
            maxLines = if (open) Integer.MAX_VALUE else collapsedLines
            ellipsize = if (open) null else android.text.TextUtils.TruncateAt.END
        }
        if (!movie.overview.isNullOrBlank()) {
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
            nextFocusDownId = binding.btnMovieWatchNow.id
            binding.btnMovieWatchNow.nextFocusUpId = id
        } else {
            isFocusable = false
            setOnClickListener(null)
        }
    }

    binding.btnMovieWatchNow.apply {
        // Dual CTA: Watch now always streams online; completed downloads use the Download button.
        text = context.getString(R.string.movie_watch_now)
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
        val watchHistory = movie.watchHistory

        progress = when {
            watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
            else -> 0
        }
        visibility = when {
            watchHistory != null -> View.VISIBLE
            else -> View.GONE
        }
    }

    fun rewireTvCtaFocus() {
        com.dskja.betterstreamflix.utils.TvFocusChain.linkHorizontal(
            binding.btnMovieWatchNow,
            binding.btnMovieTrailer,
            binding.btnMovieDownload,
            binding.root.findViewById(R.id.btn_movie_watched),
        )
        com.dskja.betterstreamflix.utils.TvFocusChain.linkHorizontal(
            binding.root.findViewById(R.id.btn_movie_share),
            binding.btnMovieFavorite,
        )
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
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                    if (fragment != null) {
                        TrailerPlaybackController.play(fragment, trailerUrl)
                    } else {
                        handleTrailerClick(trailerUrl, "MovieTv")
                    }
                }
            }
            // Hide until a playable URL is ready — avoids dead clicks while lookup runs.
            visibility = if (!trailerUrl.isNullOrBlank()) View.VISIBLE else View.GONE
            rewireTvCtaFocus()
        }
        bindTrailer(movie.trailer)
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
                val first = remote.firstOrNull()?.second
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
                com.dskja.betterstreamflix.download.DetailDownloadLabels.movieButton(context, movie)
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
                                    com.dskja.betterstreamflix.fragments.player.PlayerViewModel.OFFLINE_SERVER_NAME,
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
                    val dao = database.movieDao()
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
        shareBtn.visibility = if (canShare) View.VISIBLE else View.GONE
        if (canShare) {
            shareBtn.setOnClickListener {
                ExpMotion.hapticTap(it)
                // Prefer direct send — Leanback remotes struggle with chooser UIs.
                runCatching { context.startActivity(share) }
                    .onFailure {
                        context.startActivity(
                            Intent.createChooser(share, context.getString(R.string.detail_share)),
                        )
                    }
            }
        } else {
            shareBtn.setOnClickListener(null)
        }
    }

    binding.btnMovieFavorite.apply {

        fun Boolean.drawable() = when (this) {
            true -> R.drawable.ic_favorite_enable
            false -> R.drawable.ic_favorite_disable
        }

        fun applyFavoriteState(inList: Boolean) {
            setImageDrawable(ContextCompat.getDrawable(context, inList.drawable()))
            contentDescription = context.getString(
                if (inList) R.string.detail_remove_from_list else R.string.detail_add_to_list,
            )
        }

        setOnClickListener {
            ExpMotion.hapticTap(it)
            checkProviderAndRun {
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                    val dao = database.movieDao()
                    val current = dao.getById(movie.id)?.isFavorite ?: false
                    val newValue = !current
                    val resolvedMovie = ArtworkRepair.resolveMovieForFavorite(context, movie, newValue)

                    dao.upsertFavorite(resolvedMovie, newValue)
                    com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks.onListToggle(
                        add = newValue,
                        imdbId = movie.imdbId,
                        tmdbId = movie.tmdbId,
                        isTv = false,
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


