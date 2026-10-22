package com.betterstreamflix.app.adapters.viewholders.detail

import com.betterstreamflix.app.models.TrailerCatalog
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
import com.betterstreamflix.app.R
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.adapters.viewholders.TvShowViewHolder
import com.betterstreamflix.app.databinding.ContentTvShowMobileBinding
import com.betterstreamflix.app.databinding.ContentTvShowSeasonsMobileBinding
import com.betterstreamflix.app.databinding.ContentTvShowTvBinding
import com.betterstreamflix.app.download.DetailDownloadLabels
import com.betterstreamflix.app.download.ui.DownloadOptionsController
import com.betterstreamflix.app.fragments.player.PlayerViewModel
import com.betterstreamflix.app.fragments.tv_show.TvShowMobileFragment
import com.betterstreamflix.app.fragments.tv_show.TvShowTvFragment
import com.betterstreamflix.app.fragments.tv_show.TvShowTvFragmentDirections
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Season
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.ui.DetailTab
import com.betterstreamflix.app.ui.DetailWatchLabels
import com.betterstreamflix.app.ui.FeaturedSwiperChrome
import com.betterstreamflix.app.ui.SpacingItemDecoration
import com.betterstreamflix.app.ui.DetailTrailerPlayHooks
import com.betterstreamflix.app.ui.TrailerPlayPipeline
import com.betterstreamflix.app.ui.TrailerPlaybackController
import com.betterstreamflix.app.utils.ArtworkRepair
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.ExpPressEffects.applyExpPress
import com.betterstreamflix.app.utils.TmdbUtils
import com.betterstreamflix.app.utils.UserPreferences
import com.betterstreamflix.app.utils.dp
import com.betterstreamflix.app.utils.format
import com.betterstreamflix.app.utils.getCurrentFragment
import com.betterstreamflix.app.utils.loadTvShowPoster
import com.betterstreamflix.app.utils.toActivity
import com.betterstreamflix.app.spoilers.SpoilerShield
import com.betterstreamflix.app.spoilers.SpoilerShieldUi
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.betterstreamflix.app.fragments.tv_show.TvShowMobileFragmentDirections
import com.betterstreamflix.app.ui.DetailRating
import com.bumptech.glide.Glide

internal fun TvShowViewHolder.bindTvShowMobileDetail(binding: ContentTvShowMobileBinding) {
    DetailParentalGate.guardTvShow(
        itemView,
        tvShow,
        binding.btnTvShowWatchNow,
        binding.btnTvShowDownload,
    )
    binding.ivTvShowPoster.visibility = View.GONE
    Glide.with(binding.ivTvShowPoster).clear(binding.ivTvShowPoster)

    com.betterstreamflix.app.ui.DetailCoverAtmosphere.bindTvShow(
        cover = binding.ivTvShowCover,
        soft = binding.ivTvShowCoverSoft,
        tvShow = tvShow,
    )

    val logoReady = com.betterstreamflix.app.ui.DetailHeroLogo.restore(
        logoView = binding.ivTvShowLogo,
        titleView = binding.tvTvShowTitle,
        logoUrl = tvShow.logo,
        title = tvShow.title,
    )
    if (!logoReady) {
        val fragment = context.toActivity()?.getCurrentFragment() as? TvShowMobileFragment
        val pageRoot = fragment?.view
        if (fragment != null && pageRoot != null && fragment.isAdded) {
            itemView.post {
                if (!itemView.isAttachedToWindow || !fragment.isAdded) return@post
                com.betterstreamflix.app.ui.DetailHeaderController.bindTvShow(
                    fragment,
                    pageRoot,
                    tvShow,
                )
            }
        }
    }

    val ratingText = DetailRating.format(tvShow.rating)
    binding.tvTvShowRating.apply {
        text = ratingText
        visibility = if (ratingText.isNullOrBlank()) View.GONE else View.VISIBLE
    }
    binding.ivTvShowRatingIcon.visibility = binding.tvTvShowRating.visibility
    binding.tvTvShowQuality.visibility = View.GONE

    binding.tvTvShowReleased.apply {
        text = tvShow.released?.format("yyyy")
        isVisible = !text.isNullOrEmpty()
    }

    binding.tvTvShowRuntime.apply {
        val seasonCount = tvShow.seasons.count { it.number > 0 }
            .takeIf { it > 0 }
            ?: tvShow.seasons.size.takeIf { it > 0 }
        text = when {
            seasonCount != null -> {
                if (seasonCount == 1) {
                    context.getString(R.string.tv_show_season_count_one, seasonCount)
                } else {
                    context.getString(R.string.tv_show_seasons_count, seasonCount)
                }
            }
            else -> tvShow.runtime?.let {
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
        }
        isVisible = !text.isNullOrEmpty()
    }

    binding.tvTvShowGenres.apply {
        if (tvShow.genres.isEmpty()) {
            text = ""
            isVisible = false
            movementMethod = null
            isClickable = false
            setOnClickListener(null)
        } else {
            text = tvShow.genres.joinToString(" · ") { it.name }
            isVisible = true
            movementMethod = null
            isClickable = true
            contentDescription = context.getString(R.string.genre_section_label)
            setOnClickListener { view ->
                ExpMotion.hapticTap(view)
                val genres = tvShow.genres
                fun openGenre(genre: com.betterstreamflix.app.models.Genre) {
                    checkProviderAndRun {
                        if (context.toActivity()?.getCurrentFragment() is TvShowMobileFragment) {
                            findNavController().navigate(
                                TvShowMobileFragmentDirections.actionTvShowToGenre(
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
        }
    }

    binding.tvTvShowOverview.apply {
        val more = binding.tvTvShowOverviewMore
        val hasText = !tvShow.overview.isNullOrBlank()
        if (!hasText) {
            text = ""
            visibility = View.GONE
            more.visibility = View.GONE
            setOnClickListener(null)
            return@apply
        }
        visibility = View.VISIBLE
        text = tvShow.overview
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
    val episodeToWatch = tvShow.episodeToWatch
    val episodeSeason = resolveEpisodeSeason(episodeToWatch)
    binding.btnTvShowWatchNow.apply {
        isVisible = true
        FeaturedSwiperChrome.wireWatchButton(this)
        applyExpPress()
        setOnClickListener {
            ExpMotion.hapticTap(it)
            checkProviderAndRun {
                if (isIptvProvider()) {
                    handleDirectPlay(findNavController())
                    return@checkProviderAndRun
                }
                val episode = episodeToWatch
                if (episode == null) {
                    // Prefer in-page Episodes tab; do not open the legacy Season screen.
                    (bindingAdapter as? AppAdapter)?.onDetailTabSelectedListener?.invoke(
                        com.betterstreamflix.app.ui.DetailTab.EPISODES,
                    )
                    return@checkProviderAndRun
                }
                val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                SpoilerShieldUi.gateEpisodePlay(fragment, episode) {
                    val displayTitle = SpoilerShield.displayTitle(
                        episode,
                        episodeNumberFallback = {
                            context.getString(R.string.episode_number, it)
                        },
                    )
                    val videoType = Video.Type.Episode(
                        id = episode.id,
                        number = episode.number,
                        title = displayTitle,
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
                            "S${videoType.season.number} E${videoType.number}  •  $displayTitle",
                        )
                        putSerializable("videoType", videoType)
                    }
                    findNavController().navigate(R.id.player, args)
                }
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

    binding.btnTvShowTrailer.apply {
        val year = tvShow.released?.format("yyyy")?.toIntOrNull()
        val canLookup = TmdbUtils.hasTrailerLookupKeys(
            tmdbId = tvShow.tmdbId,
            imdbId = tvShow.imdbId,
            title = tvShow.title,
            year = year,
        )
        val trailerAvailable = !tvShow.trailer.isNullOrBlank() || canLookup
        val trailerColumn = binding.root.findViewById<View>(R.id.ll_tv_show_trailer_column)
        if (trailerColumn != null) {
            trailerColumn.isVisible = trailerAvailable
        } else {
            isVisible = trailerAvailable
        }
        applyExpPress()
        setOnClickListener {
            ExpMotion.hapticTap(it)
            fun play(url: String) {
                val seasonNumber = resolveEpisodeSeason(tvShow.episodeToWatch)?.number
                val base = DetailTrailerPlayHooks.tvShowSession(
                    tvShow = tvShow,
                    host = binding.root,
                    onNoEpisode = {
                        (bindingAdapter as? AppAdapter)?.onDetailTabSelectedListener?.invoke(
                            DetailTab.EPISODES,
                        )
                    },
                    seasonNumber = seasonNumber,
                    isIptv = isIptvProvider(),
                )
                val session = TrailerPlayPipeline.Session(
                    target = base.target,
                    onStreamNow = {
                        checkProviderAndRun {
                            if (isIptvProvider()) {
                                handleDirectPlay(findNavController())
                            } else {
                                base.onStreamNow?.invoke()
                            }
                        }
                    },
                )
                val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                if (fragment != null) {
                    TrailerPlaybackController.play(fragment, url, session)
                } else {
                    TrailerPlayPipeline.beginSession(session)
                    handleTrailerClick(url)
                }
            }
            val existing = tvShow.trailer
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
                        tmdbId = tvShow.tmdbId,
                        isTv = true,
                        title = tvShow.title,
                        year = year,
                        imdbId = tvShow.imdbId,
                    )
                }
                val first = TrailerCatalog.preferredPlayableUrl(remote)
                if (!first.isNullOrBlank()) {
                    if (tvShow.trailer.isNullOrBlank()) tvShow.trailer = first
                    play(first)
                } else {
                    (bindingAdapter as? AppAdapter)?.onDetailTabSelectedListener?.invoke(
                        DetailTab.TRAILER,
                    )
                }
            }
        }
    }

    binding.btnTvShowDownload.let { downloadBtn ->
        val downloadColumn = binding.root.findViewById<View>(R.id.ll_tv_show_download_column)
        if (isIptvProvider()) {
            if (downloadColumn != null) {
                downloadColumn.isVisible = false
            } else {
                downloadBtn.isVisible = false
            }
            downloadBtn.setOnClickListener(null)
            downloadBtn.setOnLongClickListener(null)
        } else {
            if (downloadColumn != null) {
                downloadColumn.isVisible = true
            }
            downloadBtn.isVisible = true
            downloadBtn.applyExpPress()
            val seasonForDownload = tvShow.seasons.firstOrNull { it.episodes.isNotEmpty() }
            val playOffline = preferredOfflineServerNameForEpisode(episodeToWatch) != null
            downloadBtn.contentDescription = if (playOffline) {
                context.getString(R.string.downloads_play_offline)
            } else {
                DetailDownloadLabels.seriesButton(
                    context,
                    tvShow,
                    episodeToWatch,
                    seasonForDownload,
                )
            }
            androidx.appcompat.widget.TooltipCompat.setTooltipText(
                downloadBtn,
                downloadBtn.contentDescription,
            )
            downloadBtn.setOnClickListener {
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
                        downloadBtn.findNavController().navigate(R.id.player, args)
                    } else {
                        val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                            ?: return@checkProviderAndRun
                        DownloadOptionsController.offerTvShowDownload(fragment, tvShow, episodeToWatch)
                    }
                }
            }
            downloadBtn.setOnLongClickListener {
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

    binding.tvTvShowCertification.apply {
        val cert = tvShow.contentRating?.trim().orEmpty()
        val digits = cert.filter { it.isDigit() }
        val badge = when {
            digits.isNotEmpty() -> digits.take(2)
            cert.isNotEmpty() -> cert.take(3).uppercase(Locale.getDefault())
            else -> ""
        }
        text = badge
        visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
    }

    binding.root.findViewById<View>(R.id.btn_tv_show_share)?.let { shareBtn ->
        val shareColumn = binding.root.findViewById<View>(R.id.ll_tv_show_share_column)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, tvShow.title)
            putExtra(
                Intent.EXTRA_TEXT,
                buildString {
                    append(tvShow.title)
                    tvShow.released?.format("yyyy")?.let { year -> append(" ($year)") }
                    tvShow.overview?.takeIf { it.isNotBlank() }?.let { overview -> append("\n\n").append(overview.take(280)) }
                    tvShow.trailer?.let { trailer -> append("\n").append(trailer) }
                },
            )
        }
        val canShare = share.resolveActivity(context.packageManager) != null
        if (canShare || com.betterstreamflix.app.sync.CastRoomSession.isActive()) {
            shareBtn.visibility = View.VISIBLE
            shareColumn?.visibility = View.VISIBLE
            shareBtn.applyExpPress()
            shareBtn.setOnClickListener {
                ExpMotion.hapticTap(it)
                val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                val episode = tvShow.episodeToWatch
                val doShare = {
                    if (canShare) {
                        context.startActivity(
                            Intent.createChooser(share, context.getString(R.string.detail_share)),
                        )
                    }
                }
                if (fragment != null &&
                    com.betterstreamflix.app.sync.CastRoomSession.isActive() &&
                    episode != null
                ) {
                    com.betterstreamflix.app.sync.CastRoomUi.maybeAugmentShareClick(
                        fragment,
                        com.betterstreamflix.app.sync.CastRoomUi.videoTypeFromEpisode(episode),
                        doShare,
                    )
                } else {
                    doShare()
                }
            }
        } else {
            shareBtn.visibility = View.GONE
            shareColumn?.visibility = View.GONE
            shareBtn.setOnClickListener(null)
        }
    }

    binding.root.findViewById<View>(R.id.btn_tv_show_watched)?.let { watchedBtn ->
        // Series has no single isWatched flag; toggle isWatching (false = watching complete).
        fun applyWatchingUi(watching: Boolean) {
            val completed = !watching
            val description = context.getString(
                if (completed) R.string.option_show_unwatched else R.string.option_show_watched,
            )
            watchedBtn.contentDescription = description
            androidx.appcompat.widget.TooltipCompat.setTooltipText(watchedBtn, description)
            watchedBtn.alpha = 1f
            (watchedBtn as? android.widget.ImageView)?.setImageDrawable(
                ContextCompat.getDrawable(
                    context,
                    if (completed) R.drawable.ic_watched_filled else R.drawable.ic_watched,
                ),
            )
        }

        watchedBtn.applyExpPress()
        applyWatchingUi(tvShow.isWatching)
        watchedBtn.setOnClickListener {
            ExpMotion.hapticTap(it)
            checkProviderAndRun {
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                    val db = database ?: return@launch
                    val dao = db.tvShowDao()
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
                        ExpMotion.softScale(watchedBtn)
                    }
                }
            }
        }
    }

    binding.btnTvShowFavorite.apply {
        fun applyState(inList: Boolean, animate: Boolean = false) {
            FeaturedSwiperChrome.bindListButton(this, inList, animate)
            contentDescription = context.getString(
                if (inList) R.string.detail_remove_from_list else R.string.detail_add_to_list,
            )
        }

        applyExpPress()
        applyState(tvShow.isFavorite)
        setOnClickListener {
            ExpMotion.hapticTap(it)
            checkProviderAndRun {
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                    val provider = UserPreferences.currentProvider ?: return@launch
                    val db = database ?: return@launch
                    val target = !(db.tvShowDao().getById(tvShow.id)?.isFavorite ?: tvShow.isFavorite)
                    val resolved = com.betterstreamflix.app.sync.FavoriteMutations.setTvShowFavorite(
                        context = context,
                        provider = provider,
                        tvShow = tvShow,
                        favorite = target,
                        database = db,
                    )
                    withContext(Dispatchers.Main) {
                        tvShow.poster = resolved.poster
                        tvShow.banner = resolved.banner
                        tvShow.isFavorite = target
                        applyState(target, animate = true)
                    }
                }
            }
        }
    }
}


