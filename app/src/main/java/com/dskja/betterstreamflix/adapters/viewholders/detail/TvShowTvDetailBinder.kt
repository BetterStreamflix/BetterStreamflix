package com.dskja.betterstreamflix.adapters.viewholders.detail

import com.dskja.betterstreamflix.models.TrailerCatalog
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
import com.dskja.betterstreamflix.ui.DetailWatchLabels
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
    }
    binding.tvTvShowTitle.text = tvShow.title
    fun applyTvBrandChrome(logoReady: Boolean) {
        // Logo-first Leanback hero: banner owns the stage; poster only when title text falls back.
        binding.ivTvShowPoster.visibility = when {
            logoReady -> View.GONE
            tvShow.poster.isNullOrEmpty() -> View.GONE
            else -> View.VISIBLE
        }
        binding.ivTvShowPoster.alpha = if (logoReady) 0f else 0.92f
    }
    applyTvBrandChrome(logoReady = false)
    com.dskja.betterstreamflix.logo.TitleLogoSurface.bindAndMaybeResolve(
        anchor = binding.root,
        imageView = binding.ivTvShowLogo,
        titleView = binding.tvTvShowTitle,
        tvShow = tvShow,
        persist = true,
        allowAlternateOnFail = true,
        onLogoReady = { applyTvBrandChrome(logoReady = true) },
        onLogoFailed = { applyTvBrandChrome(logoReady = false) },
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
            text = tvShow.genres.joinToString(" · ") { it.name }
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

    binding.tvTvShowOverview.apply {
        text = tvShow.overview
        val collapsedLines = 5
        maxLines = collapsedLines
        ellipsize = android.text.TextUtils.TruncateAt.END
        var expanded = false
        fun applyExpand(open: Boolean) {
            expanded = open
            maxLines = if (open) Integer.MAX_VALUE else collapsedLines
            ellipsize = if (open) null else android.text.TextUtils.TruncateAt.END
        }
        if (!tvShow.overview.isNullOrBlank()) {
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
            nextFocusDownId = binding.btnTvShowWatchNow.id
            binding.btnTvShowWatchNow.nextFocusUpId = id
        } else {
            text = ""
            visibility = View.GONE
            isFocusable = false
            setOnClickListener(null)
            val genres = binding.tvTvShowGenres
            binding.btnTvShowWatchNow.nextFocusUpId =
                if (genres.isVisible && genres.isFocusable) genres.id else View.NO_ID
        }
    }
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
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                R.string.detail_watch_no_episodes,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
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
        text = DetailWatchLabels.tvShow(
            context = context,
            tvShow = tvShow,
            seasonNumber = episodeSeason?.number,
            episodeNumber = episodeToWatch?.number,
            iptv = isIptvProvider(),
        )
    }

    binding.pbTvShowProgressEpisode.apply {
        val percent = DetailWatchLabels.progressPercent(episodeToWatch?.watchHistory)
        progress = percent
        isVisible = percent in 1..95
    }

    fun rewireTvCtaFocus() {
        val watched = binding.root.findViewById<View>(R.id.btn_tv_show_watched)
        val share = binding.root.findViewById<View>(R.id.btn_tv_show_share)
        com.dskja.betterstreamflix.utils.TvFocusChain.linkHorizontal(
            binding.btnTvShowWatchNow,
            binding.btnTvShowFavorite,
            binding.btnTvShowTrailer,
            binding.btnTvShowDownload,
        )
        val secondary = listOfNotNull(watched, share)
        if (secondary.isNotEmpty()) {
            com.dskja.betterstreamflix.utils.TvFocusChain.linkHorizontal(*secondary.toTypedArray())
        }
        val downTarget = listOfNotNull(watched, share).firstOrNull {
            it.visibility == View.VISIBLE && it.isFocusable
        }
        com.dskja.betterstreamflix.utils.TvFocusChain.linkDown(
            listOf(
                binding.btnTvShowWatchNow,
                binding.btnTvShowFavorite,
                binding.btnTvShowTrailer,
                binding.btnTvShowDownload,
            ),
            downTarget,
        )
        listOfNotNull(watched, share).forEach { button ->
            if (button.visibility == View.VISIBLE) {
                button.nextFocusUpId = binding.btnTvShowWatchNow.id
            }
        }
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
            // Hide until a playable URL is ready — avoids dead clicks while lookup runs.
            isVisible = !trailerUrl.isNullOrBlank()
            rewireTvCtaFocus()
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
                val first = TrailerCatalog.preferredPlayableUrl(remote)
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
        val canShare = share.resolveActivity(context.packageManager) != null
        shareBtn.isVisible = canShare
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

    rewireTvCtaFocus()
}


