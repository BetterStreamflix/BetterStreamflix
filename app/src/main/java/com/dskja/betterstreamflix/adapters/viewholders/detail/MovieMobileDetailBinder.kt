package com.dskja.betterstreamflix.adapters.viewholders.detail

import com.dskja.betterstreamflix.models.TrailerCatalog
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
import com.bumptech.glide.Glide
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dskja.betterstreamflix.download.DownloadContentKey
import com.dskja.betterstreamflix.fragments.movie.MovieMobileFragment
import com.dskja.betterstreamflix.ui.DetailRating
import com.dskja.betterstreamflix.ui.DetailWatchLabels

internal fun MovieViewHolder.bindMovieMobileDetail(binding: ContentMovieMobileBinding) {
    // Poster stub stays gone; cover lives inside this hero block.
    binding.ivMoviePoster.visibility = View.GONE
    Glide.with(binding.ivMoviePoster).clear(binding.ivMoviePoster)

    com.dskja.betterstreamflix.ui.DetailCoverAtmosphere.bindMovie(
        cover = binding.ivMovieCover,
        soft = binding.ivMovieCoverSoft,
        movie = movie,
    )

    binding.tvMovieTitle.text = movie.title
    // Hero logo bind is owned by DetailHeaderController (after body submit).
    // A known logo stays hidden until it decodes — don't flash the title over it.
    when (com.dskja.betterstreamflix.logo.TitleLogoSlot.state(movie.logo, hideUntilReady = true)) {
        com.dskja.betterstreamflix.logo.TitleLogoSlot.State.SHOW_TITLE -> {
            binding.ivMovieLogo.visibility = View.INVISIBLE
            binding.tvMovieTitle.visibility = View.VISIBLE
        }
        com.dskja.betterstreamflix.logo.TitleLogoSlot.State.LOADING_LOGO,
        com.dskja.betterstreamflix.logo.TitleLogoSlot.State.SHOW_LOGO -> {
            binding.ivMovieLogo.visibility = View.INVISIBLE
            binding.tvMovieTitle.visibility = View.INVISIBLE
        }
    }

    if (ExperimentalMobileDesign.enabled() &&
        binding.root.getTag(R.id.exp_enter_animated_tag) != true
    ) {
        binding.root.setTag(R.id.exp_enter_animated_tag, true)
        ExpMotion.revealHeader(
            binding.tvMovieTitle,
            binding.ivMovieLogo,
            binding.btnMovieWatchNow,
            binding.btnMovieTrailer,
            binding.btnMovieDownload,
        )
    }

    val ratingText = DetailRating.format(movie.rating)
    binding.tvMovieRating.apply {
        text = ratingText
        visibility = if (ratingText.isNullOrBlank()) View.GONE else View.VISIBLE
    }
    binding.ivMovieRatingIcon.visibility = binding.tvMovieRating.visibility
    binding.tvMovieQuality.visibility = View.GONE

    binding.tvMovieReleased.apply {
        text = movie.released?.format("yyyy")
        visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    binding.tvMovieRuntime.apply {
        text = movie.runtime?.let {
            val hours = it / 60
            val minutes = it % 60
            when {
                hours > 0 -> context.getString(
                    R.string.movie_runtime_hours_minutes_short,
                    hours,
                    minutes,
                )
                else -> context.getString(R.string.movie_runtime_minutes_short, minutes)
            }
        }
        visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    binding.tvMovieGenres.apply {
        if (movie.genres.isEmpty()) {
            text = ""
            visibility = View.GONE
            movementMethod = null
            isClickable = false
            setOnClickListener(null)
        } else {
            text = movie.genres.joinToString(" · ") { it.name }
            visibility = View.VISIBLE
            movementMethod = null
            isClickable = true
            contentDescription = context.getString(R.string.genre_section_label)
            setOnClickListener { view ->
                ExpMotion.hapticTap(view)
                val genres = movie.genres
                fun openGenre(genre: com.dskja.betterstreamflix.models.Genre) {
                    checkProviderAndRun {
                        if (context.toActivity()?.getCurrentFragment() is MovieMobileFragment) {
                            findNavController().navigate(
                                MovieMobileFragmentDirections.actionMovieToGenre(
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
                    val builder = if (ExperimentalMobileDesign.enabled()) {
                        MaterialAlertDialogBuilder(context)
                    } else {
                        AlertDialog.Builder(context)
                    }
                    builder
                        .setTitle(R.string.genre_section_label)
                        .setItems(labels) { _, which ->
                            genres.getOrNull(which)?.let(::openGenre)
                        }
                        .show()
                }
            }
        }
    }

    binding.tvMovieOverview.apply {
        text = movie.overview
        val more = binding.tvMovieOverviewMore
        val hasText = !movie.overview.isNullOrBlank()
        val collapsedLines = 2
        maxLines = collapsedLines
        ellipsize = android.text.TextUtils.TruncateAt.END
        var expanded = false
        fun applyExpand(open: Boolean) {
            expanded = open
            maxLines = if (open) Integer.MAX_VALUE else collapsedLines
            more.text = context.getString(
                if (open) R.string.detail_overview_less else R.string.detail_overview_more,
            )
        }
        val toggle = View.OnClickListener {
            ExpMotion.hapticTap(it)
            applyExpand(!expanded)
        }
        if (hasText) {
            setOnClickListener(toggle)
            more.setOnClickListener(toggle)
            post {
                val overflowing = lineCount > collapsedLines ||
                    (text?.length ?: 0) > 120 ||
                    (layout != null && maxLines == collapsedLines && layout.getEllipsisCount(lineCount.coerceAtLeast(1) - 1) > 0)
                more.visibility = if (overflowing) View.VISIBLE else View.GONE
                if (!overflowing) setOnClickListener(null)
            }
        } else {
            more.visibility = View.GONE
            setOnClickListener(null)
        }
    }

    binding.btnMovieWatchNow.apply {
        // Dual CTA: Watch streams online; completed downloads use the Download button.
        text = DetailWatchLabels.movie(context, movie)
        FeaturedSwiperChrome.wireWatchButton(this)
        applyExpPress()
        setOnClickListener {
            ExpMotion.hapticTap(it)
            checkProviderAndRun {
                findNavController().navigate(MovieMobileFragmentDirections.actionMovieToPlayer(
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
        val percent = DetailWatchLabels.progressPercent(watchHistory)
        progress = percent
        visibility = if (percent in 1..95) View.VISIBLE else View.GONE
    }

    binding.btnMovieTrailer.apply {
        val year = movie.released?.format("yyyy")?.toIntOrNull()
        val canLookup = TmdbUtils.hasTrailerLookupKeys(
            tmdbId = movie.tmdbId,
            imdbId = movie.imdbId,
            title = movie.title,
            year = year,
        )
        val trailerAvailable = !movie.trailer.isNullOrBlank() || canLookup
        val trailerColumn = binding.root.findViewById<View>(R.id.ll_movie_trailer_column)
        if (trailerColumn != null) {
            trailerColumn.visibility = if (trailerAvailable) View.VISIBLE else View.GONE
        } else {
            visibility = if (trailerAvailable) View.VISIBLE else View.GONE
        }
        applyExpPress()
        setOnClickListener {
            ExpMotion.hapticTap(it)
            fun play(url: String) {
                val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                if (fragment != null) {
                    TrailerPlaybackController.play(fragment, url)
                } else {
                    handleTrailerClick(url, "MovieMobile")
                }
            }
            val existing = movie.trailer
            if (!existing.isNullOrBlank()) {
                play(existing)
                return@setOnClickListener
            }
            if (!canLookup) {
                (bindingAdapter as? AppAdapter)?.onDetailTabSelectedListener?.invoke(
                    DetailTab.TRAILER,
                )
                return@setOnClickListener
            }
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
                if (!first.isNullOrBlank()) {
                    if (movie.trailer.isNullOrBlank()) movie.trailer = first
                    play(first)
                } else {
                    (bindingAdapter as? AppAdapter)?.onDetailTabSelectedListener?.invoke(
                        DetailTab.TRAILER,
                    )
                }
            }
        }
    }

    binding.btnMovieDownload.apply {
        applyExpPress()
        val downloadColumn = binding.root.findViewById<View>(R.id.ll_movie_download_column)
        if (isIptvProvider()) {
            if (downloadColumn != null) {
                downloadColumn.visibility = View.GONE
            } else {
                visibility = View.GONE
            }
            setOnClickListener(null)
            setOnLongClickListener(null)
        } else {
            if (downloadColumn != null) {
                downloadColumn.visibility = View.VISIBLE
            }
            visibility = View.VISIBLE
            val contentKey = movieDownloadContentKey()
            val playOffline = contentKey != null && OfflineBadgeStore.isCompleted(context, contentKey)
            val label = if (playOffline) {
                context.getString(R.string.downloads_play_offline)
            } else {
                com.dskja.betterstreamflix.download.DetailDownloadLabels.movieButton(context, movie)
            }
            contentDescription = label
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    if (playOffline) {
                        findNavController().navigate(
                            MovieMobileFragmentDirections.actionMovieToPlayer(
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

    binding.root.findViewById<View>(R.id.btn_movie_share)?.let { shareBtn ->
        val shareColumn = binding.root.findViewById<View>(R.id.ll_movie_share_column)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, movie.title)
            putExtra(
                Intent.EXTRA_TEXT,
                buildString {
                    append(movie.title)
                    movie.released?.format("yyyy")?.let { year -> append(" ($year)") }
                    movie.overview?.takeIf { it.isNotBlank() }?.let { overview -> append("\n\n").append(overview.take(280)) }
                    movie.trailer?.let { trailer -> append("\n").append(trailer) }
                },
            )
        }
        val canShare = share.resolveActivity(context.packageManager) != null
        shareBtn.visibility = if (canShare) View.VISIBLE else View.GONE
        shareColumn?.visibility = if (canShare) View.VISIBLE else View.GONE
        if (canShare) {
            shareBtn.applyExpPress()
            shareBtn.setOnClickListener {
                ExpMotion.hapticTap(it)
                context.startActivity(Intent.createChooser(share, context.getString(R.string.detail_share)))
            }
        } else {
            shareBtn.setOnClickListener(null)
        }
    }

    binding.root.findViewById<View>(R.id.btn_movie_watched)?.let { watchedBtn ->
        fun applyWatchedUi(watched: Boolean) {
            val description = context.getString(
                if (watched) R.string.option_show_unwatched else R.string.option_show_watched,
            )
            watchedBtn.contentDescription = description
            androidx.appcompat.widget.TooltipCompat.setTooltipText(watchedBtn, description)
            watchedBtn.alpha = 1f
            (watchedBtn as? android.widget.ImageView)?.setImageDrawable(
                ContextCompat.getDrawable(
                    context,
                    if (watched) R.drawable.ic_watched_filled else R.drawable.ic_watched,
                ),
            )
            (watchedBtn as? TextView)?.text = description
        }

        watchedBtn.applyExpPress()
        applyWatchedUi(movie.isWatched)
        watchedBtn.setOnClickListener {
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
                        applyWatchedUi(target)
                        ExpMotion.softScale(watchedBtn)
                    }
                }
            }
        }
    }

    binding.btnMovieFavorite.apply {
        fun applyState(inList: Boolean, animate: Boolean = false) {
            FeaturedSwiperChrome.bindListButton(this, inList, animate)
            contentDescription = context.getString(
                if (inList) R.string.detail_remove_from_list else R.string.detail_add_to_list,
            )
        }

        applyExpPress()
        applyState(movie.isFavorite)
        setOnClickListener {
            ExpMotion.hapticTap(it)
            checkProviderAndRun {
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                    val dao = database.movieDao()
                    val target = !(dao.getById(movie.id)?.isFavorite ?: movie.isFavorite)
                    val resolved =
                        ArtworkRepair.resolveMovieForFavorite(context, movie, target)
                    dao.upsertFavorite(resolved, target)
                    com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks.onListToggle(
                        add = target,
                        imdbId = movie.imdbId,
                        tmdbId = movie.tmdbId,
                        isTv = false,
                    )
                    withContext(Dispatchers.Main) {
                        movie.poster = resolved.poster
                        movie.banner = resolved.banner
                        movie.isFavorite = target
                        applyState(target, animate = true)
                    }
                }
            }
        }
    }
}


