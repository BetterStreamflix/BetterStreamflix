package com.dskja.betterstreamflix.adapters.viewholders.detail

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.dskja.betterstreamflix.adapters.viewholders.TvShowViewHolder
import com.dskja.betterstreamflix.databinding.ContentTvShowMobileBinding
import com.dskja.betterstreamflix.databinding.ContentTvShowSeasonsMobileBinding
import com.dskja.betterstreamflix.databinding.ContentTvShowTvBinding
import com.dskja.betterstreamflix.download.DetailDownloadLabels
import com.dskja.betterstreamflix.download.ui.DownloadOptionsController
import com.dskja.betterstreamflix.fragments.player.PlayerViewModel
import com.dskja.betterstreamflix.fragments.tv_show.TvShowMobileFragment
import com.dskja.betterstreamflix.fragments.tv_show.TvShowTvFragment
import com.dskja.betterstreamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.ui.DetailTab
import com.dskja.betterstreamflix.ui.FeaturedSwiperChrome
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
import com.dskja.betterstreamflix.utils.ArtworkRepair
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.getCurrentFragment
import com.dskja.betterstreamflix.utils.loadTvShowPoster
import com.dskja.betterstreamflix.utils.toActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dskja.betterstreamflix.ui.DetailRating

internal fun TvShowViewHolder.bindTvShowTvDetail(binding: ContentTvShowTvBinding) {
    binding.ivTvShowPoster.run {
        loadTvShowPoster(tvShow) {
            fallback(R.drawable.glide_fallback_cover)
            transition(DrawableTransitionOptions.withCrossFade())
        }
        visibility = if (tvShow.poster.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
    binding.tvTvShowTitle.text = tvShow.title
    com.dskja.betterstreamflix.logo.TitleLogoSurface.bindAndMaybeResolve(
        anchor = binding.root,
        imageView = binding.ivTvShowLogo,
        titleView = binding.tvTvShowTitle,
        tvShow = tvShow,
        persist = true,
        allowAlternateOnFail = true,
    )

    binding.tvTvShowRating.apply {
        text = com.dskja.betterstreamflix.ui.DetailRating.format(tvShow.rating)
        isVisible = !text.isNullOrEmpty()
    }
    binding.ivTvShowRatingIcon.isVisible = binding.tvTvShowRating.isVisible

    binding.tvTvShowCertification.apply {
        val cert = tvShow.contentRating?.trim().orEmpty()
        val digits = cert.filter { it.isDigit() }
        val badge = when {
            digits.isNotEmpty() -> digits.take(2)
            cert.isNotEmpty() -> cert.take(3).uppercase(Locale.getDefault())
            else -> ""
        }
        text = badge
        isVisible = badge.isNotEmpty()
    }

    binding.tvTvShowQuality.apply {
        text = tvShow.quality
        isVisible = !text.isNullOrEmpty()
    }

    binding.tvTvShowReleased.apply {
        text = tvShow.released?.format("yyyy")
        isVisible = !text.isNullOrEmpty()
    }

    binding.tvTvShowRuntime.apply {
        text = tvShow.runtime?.let {
            val hours = it / 60
            val minutes = it % 60
            when {
                hours > 0 -> context.getString(R.string.tv_show_runtime_hours_minutes, hours, minutes)
                else -> context.getString(R.string.tv_show_runtime_minutes, minutes)
            }
        }
        isVisible = !text.isNullOrEmpty()
    }

    binding.tvTvShowGenres.apply {
        if (tvShow.genres.isEmpty()) {
            text = ""
            isVisible = false
            isFocusable = false
            setOnClickListener(null)
        } else {
            text = tvShow.genres.joinToString(", ") { it.name }
            isVisible = true
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = context.getString(R.string.genre_section_label)
            setOnClickListener { view ->
                ExpMotion.hapticTap(view)
                val genres = tvShow.genres
                fun openGenre(genre: com.dskja.betterstreamflix.models.Genre) {
                    checkProviderAndRun {
                        if (context.toActivity()?.getCurrentFragment() is TvShowTvFragment) {
                            findNavController().navigate(
                                TvShowTvFragmentDirections.actionTvShowToGenre(
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
            nextFocusDownId = binding.btnTvShowWatchNow.id
            binding.btnTvShowWatchNow.nextFocusUpId = id
        }
    }

    binding.tvTvShowOverview.text = tvShow.overview
    val episodeToWatch = tvShow.episodeToWatch
    val episodeSeason = resolveEpisodeSeason(episodeToWatch)

    binding.btnTvShowWatchNow.apply {
        isVisible = true
        setOnClickListener {
            ExpMotion.hapticTap(it)
            checkProviderAndRun {
                if (isIptvProvider()) {
                    handleDirectPlay(findNavController())
                    return@checkProviderAndRun
                }
                val episode = episodeToWatch
                if (episode == null) {
                    val adapter = bindingAdapter as? AppAdapter
                    if (adapter?.onDetailTabSelectedListener != null) {
                        adapter.onDetailTabSelectedListener?.invoke(DetailTab.EPISODES)
                    } else {
                        val season = tvShow.seasons.firstOrNull()
                        if (season != null) {
                            findNavController().navigate(
                                TvShowTvFragmentDirections.actionTvShowToSeason(
                                    tvShowId = tvShow.id,
                                    tvShowTitle = tvShow.title,
                                    tvShowPoster = tvShow.poster,
                                    tvShowBanner = tvShow.banner,
                                    seasonId = season.id,
                                    seasonNumber = season.number,
                                    seasonTitle = season.title ?: "Season ${season.number}",
                                ),
                            )
                        }
                    }
                    return@checkProviderAndRun
                }
                val videoType = Video.Type.Episode(
                    id = episode.id,
                    number = episode.number,
                    title = episode.title,
                    poster = episode.poster,
                    overview = episode.overview,
                    tvShow = Video.Type.Episode.TvShow(
                        id = tvShow.id,
                        title = tvShow.title,
                        poster = tvShow.poster,
                        banner = tvShow.banner,
                        releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                        imdbId = tvShow.imdbId,
                    ),
                    season = Video.Type.Episode.Season(
                        number = episodeSeason?.number ?: 1,
                        title = episodeSeason?.title ?: "",
                    ),
                )
                val args = Bundle().apply {
                    putString("id", episode.id)
                    putString("title", tvShow.title)
                    putString("subtitle", "S${videoType.season.number} E${videoType.number}  •  ${videoType.title}")
                    putSerializable("videoType", videoType)
                    // Dual CTA: Watch streams online; Offline uses the Download button.
                }
                findNavController().navigate(R.id.player, args)
            }
        }
        text = when {
            isIptvProvider() -> context.getString(R.string.movie_watch_now)
            episodeToWatch == null -> context.getString(R.string.movie_watch_now)
            else -> context.getString(
                R.string.tv_show_watch_season_episode,
                episodeSeason?.number ?: 1,
                episodeToWatch.number,
            )
        }
    }

    binding.pbTvShowProgressEpisode.apply {
        val watchHistory = episodeToWatch?.watchHistory
        progress = when {
            watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
            else -> 0
        }
        isVisible = watchHistory != null
    }

    binding.btnTvShowTrailer.apply {
        val year = tvShow.released?.format("yyyy")?.toIntOrNull()
        val canLookup = TmdbUtils.hasTrailerLookupKeys(
            tmdbId = tvShow.tmdbId,
            imdbId = tvShow.imdbId,
            title = tvShow.title,
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
                        handleTrailerClick(trailerUrl)
                    }
                }
            }
            isVisible = !trailerUrl.isNullOrBlank() || canLookup
        }
        bindTrailer(tvShow.trailer)
        if (tvShow.trailer.isNullOrBlank() && canLookup) {
            itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                val remote = withContext(Dispatchers.IO) {
                    TmdbUtils.listYoutubeTrailers(
                        tmdbId = tvShow.tmdbId,
                        isTv = true,
                        title = tvShow.title,
                        year = year,
                        imdbId = tvShow.imdbId,
                    )
                }
                val first = remote.firstOrNull()?.second
                if (!first.isNullOrBlank() && tvShow.trailer.isNullOrBlank()) {
                    tvShow.trailer = first
                    bindTrailer(first)
                }
            }
        }
    }

    binding.btnTvShowDownload.apply {
        if (isIptvProvider()) {
            isVisible = false
            setOnClickListener(null)
            setOnLongClickListener(null)
        } else {
            isVisible = true
            val seasonForDownload = tvShow.seasons.firstOrNull { it.episodes.isNotEmpty() }
            val playOffline = preferredOfflineServerNameForEpisode(episodeToWatch) != null
            text = if (playOffline) {
                context.getString(R.string.downloads_play_offline)
            } else {
                DetailDownloadLabels.seriesButton(
                    context,
                    tvShow,
                    episodeToWatch,
                    seasonForDownload,
                )
            }
            contentDescription = text
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    if (playOffline && episodeToWatch != null) {
                        val episode = episodeToWatch
                        val videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = tvShow.id,
                                title = tvShow.title,
                                poster = tvShow.poster,
                                banner = tvShow.banner,
                                releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                                imdbId = tvShow.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episodeSeason?.number ?: 1,
                                title = episodeSeason?.title ?: "",
                            ),
                        )
                        val args = Bundle().apply {
                            putString("id", episode.id)
                            putString("title", tvShow.title)
                            putString(
                                "subtitle",
                                "S${videoType.season.number} E${videoType.number}  •  ${videoType.title}",
                            )
                            putSerializable("videoType", videoType)
                            putString("preferredServerName", PlayerViewModel.OFFLINE_SERVER_NAME)
                        }
                        findNavController().navigate(R.id.player, args)
                    } else {
                        val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                            ?: return@checkProviderAndRun
                        DownloadOptionsController.offerTvShowDownload(fragment, tvShow, episodeToWatch)
                    }
                }
            }
            setOnLongClickListener {
                if (!playOffline) return@setOnLongClickListener false
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                        ?: return@checkProviderAndRun
                    DownloadOptionsController.offerTvShowDownload(fragment, tvShow, episodeToWatch)
                }
                true
            }
        }
    }

    binding.root.findViewById<View>(R.id.btn_tv_show_share)?.let { shareBtn ->
        shareBtn.setOnClickListener {
            ExpMotion.hapticTap(it)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, tvShow.title)
                putExtra(
                    Intent.EXTRA_TEXT,
                    buildString {
                        append(tvShow.title)
                        tvShow.released?.format("yyyy")?.let { year -> append(" ($year)") }
                        tvShow.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                            append("\n\n").append(overview.take(280))
                        }
                        tvShow.trailer?.let { trailer -> append("\n").append(trailer) }
                    },
                )
            }
            context.startActivity(
                Intent.createChooser(share, context.getString(R.string.detail_share)),
            )
        }
    }

    binding.root.findViewById<TextView>(R.id.btn_tv_show_watched)?.let { watchedBtn ->
        fun applyWatchingUi(watching: Boolean) {
            val completed = !watching
            watchedBtn.text = context.getString(
                if (completed) R.string.option_show_unwatched else R.string.option_show_watched,
            )
        }

        applyWatchingUi(tvShow.isWatching)
        watchedBtn.setOnClickListener {
            ExpMotion.hapticTap(it)
            checkProviderAndRun {
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                    val dao = database.tvShowDao()
                    val current = dao.getById(tvShow.id)
                    val currentlyWatching = current?.isWatching ?: tvShow.isWatching
                    val targetWatching = !currentlyWatching
                    val updated = (current ?: tvShow).copy().apply {
                        isWatching = targetWatching
                    }
                    dao.save(updated)
                    withContext(Dispatchers.Main) {
                        tvShow.isWatching = targetWatching
                        applyWatchingUi(targetWatching)
                    }
                }
            }
        }
    }

    binding.btnTvShowFavorite.apply {
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
                    val dao = database.tvShowDao()
                    val current = dao.getById(tvShow.id)?.isFavorite ?: false
                    val newValue = !current
                    val resolvedTvShow = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, newValue)

                    dao.upsertFavorite(resolvedTvShow, newValue)
                    com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks.onListToggle(
                        add = newValue,
                        imdbId = tvShow.imdbId,
                        tmdbId = tvShow.tmdbId,
                        isTv = true,
                    )

                    withContext(Dispatchers.Main) {
                        tvShow.poster = resolvedTvShow.poster
                        tvShow.banner = resolvedTvShow.banner
                        tvShow.isFavorite = newValue
                        applyFavoriteState(newValue)
                        ExpMotion.popIn(binding.btnTvShowFavorite)
                    }
                }
            }
        }

        applyFavoriteState(tvShow.isFavorite)
    }

    com.dskja.betterstreamflix.utils.TvFocusChain.linkHorizontal(
        binding.btnTvShowWatchNow,
        binding.btnTvShowTrailer,
        binding.btnTvShowDownload,
        binding.root.findViewById(R.id.btn_tv_show_watched),
        binding.root.findViewById(R.id.btn_tv_show_share),
        binding.btnTvShowFavorite,
    )
}


