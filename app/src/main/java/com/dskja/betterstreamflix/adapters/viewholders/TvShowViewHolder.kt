package com.dskja.betterstreamflix.adapters.viewholders

import com.dskja.betterstreamflix.adapters.viewholders.detail.bindTvShowMobileDetail
import com.dskja.betterstreamflix.adapters.viewholders.detail.bindTvShowTvDetail
import com.dskja.betterstreamflix.adapters.viewholders.detail.bindTvShowSeasonsMobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.KeyEvent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.Toast
import android.util.Log
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.viewbinding.ViewBinding
import com.dskja.betterstreamflix.providers.IptvProvider
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.TvFocusZoom
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.*
import com.dskja.betterstreamflix.fragments.home.HomeTvFragment
import com.dskja.betterstreamflix.fragments.home.HomeTvFragmentDirections
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.download.DownloadContentKey
import com.dskja.betterstreamflix.download.OfflineBadgeStore
import com.dskja.betterstreamflix.download.DetailDownloadLabels
import com.dskja.betterstreamflix.download.ui.DownloadOptionsController
import com.dskja.betterstreamflix.fragments.movie.MovieMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.player.PlayerViewModel
import com.dskja.betterstreamflix.fragments.tv_show.TvShowMobileFragment
import com.dskja.betterstreamflix.fragments.tv_show.TvShowMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.tv_show.TvShowTvFragment
import com.dskja.betterstreamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.dskja.betterstreamflix.ui.DetailRating
import com.dskja.betterstreamflix.ui.DetailTabsController
import com.dskja.betterstreamflix.ui.DetailTrailerMobilePlayer
import com.dskja.betterstreamflix.ui.DetailTrailerTvController
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
import com.dskja.betterstreamflix.ui.DetailTab
import com.dskja.betterstreamflix.ui.TmdbLogoGlide
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.fragments.movies.MoviesMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.movies.MoviesTvFragmentDirections
import com.dskja.betterstreamflix.fragments.search.SearchMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.search.SearchTvFragmentDirections
import com.dskja.betterstreamflix.fragments.genre.GenreMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.genre.GenreTvFragmentDirections
import com.dskja.betterstreamflix.fragments.people.PeopleMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.people.PeopleTvFragmentDirections
import com.dskja.betterstreamflix.fragments.home.HomeMobileFragmentDirections
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.ui.FeaturedHeroController
import com.dskja.betterstreamflix.ui.FeaturedProviderSwitch
import com.dskja.betterstreamflix.ui.FeaturedSwiperChrome
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.ui.ShowOptionsMobileDialog
import com.dskja.betterstreamflix.ui.ShowOptionsTvDialog
import com.dskja.betterstreamflix.utils.ExpAmbientGlow
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.toActivity
import com.dskja.betterstreamflix.utils.getCurrentFragment
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.loadTvShowBanner
import com.dskja.betterstreamflix.utils.loadTvShowPoster
import com.dskja.betterstreamflix.utils.ArtworkRepair
import com.dskja.betterstreamflix.providers.Provider
import java.util.Locale
import com.dskja.betterstreamflix.models.Trailer

class TvShowViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    internal val context = itemView.context
    internal val database: AppDatabase
        get() = AppDatabase.getInstance(context)

    init {
        if (ExperimentalMobileDesign.enabled() &&
            (_binding is ItemTvShowMobileBinding ||
                _binding is ItemTvShowGridMobileBinding ||
                _binding is ItemCategorySwiperMobileBinding ||
                _binding is ItemTvShowTvBinding ||
                _binding is ItemTvShowGridBinding)
        ) {
            itemView.applyExpPress()
        }
    }

    internal lateinit var tvShow: TvShow
    private var onTvShowClick: ((TvShow) -> Unit)? = null
    private var onTvShowLongClick: ((TvShow) -> Unit)? = null
    private var onTvShowKey: ((TvShow, KeyEvent) -> Boolean)? = null
    private var itemSelected: Boolean = false
    private var ribbonStateJob: Job? = null

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ContentTvShowSeasonsMobileBinding -> _binding.rvTvShowEpisodes
            is ContentTvShowSeasonsTvBinding -> _binding.hgvTvShowSeasons
            is ContentTvShowCastMobileBinding -> _binding.rvTvShowCast
            is ContentTvShowCastTvBinding -> _binding.hgvTvShowCast
            is ContentTvShowDirectorsTvBinding -> _binding.hgvTvShowDirectors
            is ContentTvShowRecommendationsMobileBinding -> _binding.rvTvShowRecommendations
            is ContentTvShowRecommendationsTvBinding -> _binding.hgvTvShowRecommendations
            is ContentDetailTrailerTvBinding -> _binding.hgvDetailTrailers
            else -> null
        }

    fun bind(
        tvShow: TvShow,
        onTvShowClick: ((TvShow) -> Unit)? = null,
        onTvShowLongClick: ((TvShow) -> Unit)? = null,
        onTvShowKey: ((TvShow, KeyEvent) -> Boolean)? = null,
        itemSelected: Boolean = false,
    ) {
        this.tvShow = tvShow
        this.onTvShowClick = onTvShowClick
        this.onTvShowLongClick = onTvShowLongClick
        this.onTvShowKey = onTvShowKey
        this.itemSelected = itemSelected

        when (_binding) {
            is ItemTvShowMobileBinding -> displayMobileItem(_binding)
            is ItemTvShowTvBinding -> displayTvItem(_binding)
            is ItemTvShowGridMobileBinding -> displayGridMobileItem(_binding)
            is ItemTvShowGridBinding -> displayGridTvItem(_binding)
            is ItemCategorySwiperMobileBinding -> displaySwiperMobileItem(_binding)

            is ContentTvShowMobileBinding -> displayTvShowMobile(_binding)
            is ContentTvShowTvBinding -> displayTvShowTv(_binding)
            is ContentTvShowSeasonsMobileBinding -> displaySeasonsMobile(_binding)
            is ContentTvShowSeasonsTvBinding -> displaySeasonsTv(_binding)
            is ContentTvShowDirectorsMobileBinding -> displayDirectorsMobile(_binding)
            is ContentTvShowDirectorsTvBinding -> displayDirectorsTv(_binding)
            is ContentTvShowCastMobileBinding -> displayCastMobile(_binding)
            is ContentTvShowCastTvBinding -> displayCastTv(_binding)
            is ContentTvShowRecommendationsMobileBinding -> displayRecommendationsMobile(_binding)
            is ContentDetailTabsMobileBinding -> displayTabsMobile(_binding)
            is ContentDetailTrailerMobileBinding -> displayTrailerMobile(_binding)
            is ContentDetailTrailerTvBinding -> displayTrailerTv(_binding)
            is ContentDetailAboutMobileBinding -> displayAboutMobile(_binding)
            is ContentTvShowRecommendationsTvBinding -> displayRecommendationsTv(_binding)
        }
    }

    fun setItemSelected(selected: Boolean) {
        itemSelected = selected
        when (_binding) {
            is ItemTvShowGridMobileBinding -> {
                _binding.root.isActivated = selected
                applyMobileSelection(_binding.root)
            }
            is ItemTvShowGridBinding -> _binding.root.isActivated = selected
        }
    }

    internal fun isIptvProvider(): Boolean {
        val name = tvShow.providerName ?: UserPreferences.currentProvider?.name ?: ""
        val provider = Provider.providers.keys.find { it.name == name }
        return provider is IptvProvider
    }

    private fun episodeBadgeText(): String {
        if (isIptvProvider()) return context.getString(R.string.player_live_badge)
        return tvShow.lastPlayedEpisode?.let { "E${it.number}" }
            ?: tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let { "E${it.number}" }
            ?: tvShow.released?.format("yyyy")
            ?: context.getString(R.string.tv_show_item_type)
    }

    private fun bindEpisodeBadge(badge: android.widget.TextView) {
        val text = episodeBadgeText()
        val wasVisible = badge.isVisible
        badge.text = text
        badge.isVisible = text.isNotBlank()
        if (isIptvProvider() && ExperimentalMobileDesign.enabled()) {
            badge.setBackgroundResource(ExperimentalMobileDesign.liveIndicatorBackground())
            badge.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    badge,
                    com.google.android.material.R.attr.colorOnPrimary,
                ),
            )
            badge.setPadding(
                (8 * context.resources.displayMetrics.density).toInt(),
                (3 * context.resources.displayMetrics.density).toInt(),
                (8 * context.resources.displayMetrics.density).toInt(),
                (3 * context.resources.displayMetrics.density).toInt(),
            )
            badge.textSize = 9f
            badge.setTypeface(badge.typeface, android.graphics.Typeface.BOLD)
            if (badge.isVisible && !wasVisible) {
                ExpMotion.popIn(badge)
                runCatching {
                    badge.startAnimation(
                        android.view.animation.AnimationUtils.loadAnimation(
                            context,
                            R.anim.live_badge_pulse,
                        ),
                    )
                }
            }
        } else if (ExperimentalMobileDesign.enabled()) {
            badge.clearAnimation()
            badge.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
            badge.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    badge, com.google.android.material.R.attr.colorOnSecondaryContainer,
                )
            )
            badge.setPadding(
                (6 * context.resources.displayMetrics.density).toInt(),
                (2 * context.resources.displayMetrics.density).toInt(),
                (6 * context.resources.displayMetrics.density).toInt(),
                (2 * context.resources.displayMetrics.density).toInt(),
            )
            badge.textSize = 9f
            if (badge.isVisible && !wasVisible) ExpMotion.popIn(badge)
        }
    }

    internal fun checkProviderAndRun(action: () -> Unit) {
        val providerName = tvShow.providerName
        if (!providerName.isNullOrBlank() && providerName != UserPreferences.currentProvider?.name) {
            Provider.findByName(providerName)?.let {
                UserPreferences.setCurrentProviderForPlayback(it)
            }
        }
        action()
    }

    internal fun handleDirectPlay(navController: NavController) {
        val videoType = Video.Type.Episode(
            id = tvShow.id,
            number = 1,
            title = tvShow.title,
            poster = tvShow.poster,
            overview = tvShow.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = tvShow.id,
                title = tvShow.title,
                poster = tvShow.poster,
                banner = tvShow.banner,
                releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                imdbId = tvShow.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = 1,
                title = "Live",
            ),
        )
        
        val args = Bundle().apply {
            putString("id", tvShow.id)
            putString("title", tvShow.title)
            putString("subtitle", tvShow.title)
            putSerializable("videoType", videoType)
        }
        if (isIptvProvider()) {
            com.dskja.betterstreamflix.iptv.IptvLiveSession.rememberShows(
                listOf(tvShow),
                com.dskja.betterstreamflix.utils.UserPreferences.currentProvider,
            )
            com.dskja.betterstreamflix.iptv.IptvLiveSession.setCurrent(tvShow.id)
        }
        navController.navigate(R.id.player, args)
    }

    private fun tvShowArgs(): Bundle {
        return Bundle().apply {
            putString("id", tvShow.id)
            putString("poster", tvShow.poster)
            putString("banner", tvShow.banner)
        }
    }

    internal fun resolveEpisodeSeason(episode: Episode?): Season? {
        if (episode == null) return null

        val currentSeason = episode.season
        val seasonKey = episode.id.substringBeforeLast("/", "")
            .takeIf { it.isNotBlank() }
        if (currentSeason != null && currentSeason.number != 0) {
            return currentSeason
        }

        return tvShow.seasons.firstOrNull { season ->
            season.id == seasonKey ||
                season.id == currentSeason?.id ||
                season.episodes.any { it.id == episode.id } ||
                (episode.number != 0 && season.episodes.any { it.number == episode.number && it.title == episode.title })
        } ?: currentSeason
    }

    internal fun preferredOfflineServerNameForEpisode(episode: Episode?): String? {
        episode ?: return null
        val providerName = tvShow.providerName
            ?: UserPreferences.currentProvider?.name
            ?: return null
        val seasonNumber = resolveEpisodeSeason(episode)?.number ?: episode.season?.number ?: return null
        val contentKey = DownloadContentKey.episode(
            providerName = providerName,
            tvShowId = tvShow.id,
            seasonNumber = seasonNumber,
            episodeNumber = episode.number,
            episodeId = episode.id,
        )
        return if (OfflineBadgeStore.isCompleted(itemView.context, contentKey)) {
            PlayerViewModel.OFFLINE_SERVER_NAME
        } else {
            null
        }
    }

    private fun setPoster(imageView: ImageView) {
        imageView.scaleType = if (isIptvProvider()) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        imageView.loadTvShowPoster(tvShow) {
            fallback(R.drawable.glide_fallback_cover)
            transition(DrawableTransitionOptions.withCrossFade())
        }
    }

    private fun displayMobileItem(binding: ItemTvShowMobileBinding) {
        binding.root.setOnClickListener {
            ExpMotion.hapticTap(it)
            onTvShowClick?.let { listener ->
                listener(tvShow)
                return@setOnClickListener
            }
            checkProviderAndRun {
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                }
            }
        }
        binding.root.setOnLongClickListener {
            onTvShowLongClick?.let { listener ->
                listener(tvShow)
                return@setOnLongClickListener true
            }
            ShowOptionsMobileDialog(context, tvShow).show()
            true
        }
        setPoster(binding.ivTvShowPoster)
        bindRibbons(binding.ivTvShowFavoriteRibbon, binding.ivTvShowWatchedRibbon, binding.root.findViewById(R.id.iv_tv_show_download_ribbon))
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            val show = !text.isNullOrEmpty()
            val wasVisible = isVisible
            isVisible = show
            if (ExperimentalMobileDesign.enabled() && show) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            val target = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            val show = watchHistory != null && watchHistory.durationMillis > 0
            val wasVisible = isVisible
            isVisible = show
            if (show && ExperimentalMobileDesign.enabled() && (!wasVisible || progress != target)) {
                android.animation.ObjectAnimator.ofInt(this, "progress", 0, target)
                    .setDuration(420L)
                    .start()
            } else {
                progress = target
            }
            if (ExperimentalMobileDesign.enabled() && show && !wasVisible) {
                ExpMotion.popIn(this)
            }
        }
        bindEpisodeBadge(binding.tvTvShowLastEpisode)
        binding.tvTvShowTitle.text = tvShow.title
        com.dskja.betterstreamflix.logo.TitleLogoSurface.bindCachedOnly(
            imageView = binding.ivTvShowLogo,
            titleView = null,
            logoUrl = tvShow.logo,
            title = tvShow.title,
            hideUntilReady = true,
        )
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.kenBurns(binding.ivTvShowPoster)
            ExpMotion.revealHeader(binding.tvTvShowTitle)
            ExpMotion.popIn(binding.root)
        }
    }

    private fun displayTvItem(binding: ItemTvShowTvBinding) {
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                onTvShowClick?.let { listener ->
                    listener(tvShow)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    if (isIptvProvider()) {
                        handleDirectPlay(findNavController())
                    } else {
                        findNavController().navigate(R.id.tv_show, tvShowArgs())
                    }
                }
            }
            setOnLongClickListener {
                ExpMotion.hapticTap(it)
                onTvShowLongClick?.let { listener ->
                    listener(tvShow)
                    return@setOnLongClickListener true
                }
                ShowOptionsTvDialog(context, tvShow).show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                TvFocusZoom.apply(this, hasFocus)
                (context.toActivity()?.getCurrentFragment() as? HomeTvFragment)?.let { fragment ->
                    if (hasFocus) {
                        fragment.pinBackground(tvShow.banner)
                    } else {
                        fragment.releasePinnedBackground()
                    }
                }
            }
        }
        setPoster(binding.ivTvShowPoster)
        bindRibbons(binding.ivTvShowFavoriteRibbon, binding.ivTvShowWatchedRibbon, binding.root.findViewById(R.id.iv_tv_show_download_ribbon))
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            val show = !text.isNullOrEmpty()
            val wasVisible = isVisible
            isVisible = show
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        bindEpisodeBadge(binding.tvTvShowLastEpisode)
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun displayGridMobileItem(binding: ItemTvShowGridMobileBinding) {
        binding.root.alpha = 1f
        binding.root.isActivated = itemSelected
        applyMobileSelection(binding.root)
        binding.root.setOnKeyListener { _, _, event -> onTvShowKey?.invoke(tvShow, event) ?: false }
        binding.root.setOnClickListener {
            ExpMotion.hapticTap(it)
            onTvShowClick?.let { listener ->
                listener(tvShow)
                return@setOnClickListener
            }
            checkProviderAndRun {
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                }
            }
        }
        binding.root.setOnLongClickListener {
            onTvShowLongClick?.let { listener ->
                listener(tvShow)
                return@setOnLongClickListener true
            }
            ShowOptionsMobileDialog(context, tvShow).show()
            true
        }
        setPoster(binding.ivTvShowPoster)
        bindRibbons(binding.ivTvShowFavoriteRibbon, binding.ivTvShowWatchedRibbon, binding.root.findViewById(R.id.iv_tv_show_download_ribbon))
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            val show = !text.isNullOrEmpty()
            val wasVisible = isVisible
            isVisible = show
            if (ExperimentalMobileDesign.enabled() && show) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            val target = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            val show = watchHistory != null && watchHistory.durationMillis > 0
            val wasVisible = isVisible
            isVisible = show
            if (show && ExperimentalMobileDesign.enabled() && (!wasVisible || progress != target)) {
                android.animation.ObjectAnimator.ofInt(this, "progress", 0, target)
                    .setDuration(420L)
                    .start()
            } else {
                progress = target
            }
        }
        bindEpisodeBadge(binding.tvTvShowLastEpisode)
        binding.tvTvShowTitle.text = tvShow.title
        com.dskja.betterstreamflix.logo.TitleLogoSurface.bindCachedOnly(
            imageView = binding.ivTvShowLogo,
            titleView = null,
            logoUrl = tvShow.logo,
            title = tvShow.title,
            hideUntilReady = true,
        )
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.kenBurns(binding.ivTvShowPoster)
            ExpMotion.revealHeader(binding.tvTvShowTitle)
            ExpMotion.popIn(binding.root)
        }
    }

    private fun displayGridTvItem(binding: ItemTvShowGridBinding) {
        binding.root.apply {
            alpha = 1f
            isActivated = itemSelected
            setOnKeyListener { _, _, event -> onTvShowKey?.invoke(tvShow, event) ?: false }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                onTvShowClick?.let { listener ->
                    listener(tvShow)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    if (isIptvProvider()) {
                        handleDirectPlay(findNavController())
                    } else {
                        findNavController().navigate(R.id.tv_show, tvShowArgs())
                    }
                }
            }
            setOnLongClickListener {
                ExpMotion.hapticTap(it)
                onTvShowLongClick?.let { listener ->
                    listener(tvShow)
                    return@setOnLongClickListener true
                }
                ShowOptionsTvDialog(context, tvShow).show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                TvFocusZoom.apply(this, hasFocus)
            }
        }
        setPoster(binding.ivTvShowPoster)
        bindRibbons(binding.ivTvShowFavoriteRibbon, binding.ivTvShowWatchedRibbon, binding.root.findViewById(R.id.iv_tv_show_download_ribbon))
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            val show = !text.isNullOrEmpty()
            val wasVisible = isVisible
            isVisible = show
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        bindEpisodeBadge(binding.tvTvShowLastEpisode)
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun applyMobileSelection(view: View) {
        view.findViewById<View?>(R.id.v_tv_show_select_ring)?.let { ring ->
            val selectionMode = onTvShowClick != null
            if (selectionMode) {
                ring.visibility = View.VISIBLE
                ring.setBackgroundResource(
                    if (itemSelected) R.drawable.bg_mylist_select_checked
                    else R.drawable.bg_mylist_select_ring,
                )
            } else {
                ring.visibility = View.GONE
            }
        }
        if (itemSelected) {
            val width = (3 * context.resources.displayMetrics.density).toInt()
            val stroke = if (ExperimentalMobileDesign.enabled()) {
                com.google.android.material.color.MaterialColors.getColor(
                    view,
                    androidx.appcompat.R.attr.colorPrimary,
                )
            } else {
                ContextCompat.getColor(context, R.color.favorite_selected)
            }
            view.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(width, stroke)
                if (ExperimentalMobileDesign.enabled()) {
                    cornerRadius = 10f * context.resources.displayMetrics.density
                }
            }
            view.setPadding(width, width, width, width)
            if (ExperimentalMobileDesign.enabled() &&
                view.getTag(R.id.exp_enter_animated_tag) != "selected"
            ) {
                view.setTag(R.id.exp_enter_animated_tag, "selected")
                ExpMotion.popIn(view)
            }
        } else {
            view.background = null
            view.setPadding(0, 0, 0, 0)
            view.setTag(R.id.exp_enter_animated_tag, null)
        }
    }

    internal fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    internal fun getInstalledSmartTubePackages(): List<String> {
        val installed = mutableListOf<String>()
        if (isPackageInstalled("org.smarttube.stable")) installed.add("org.smarttube.stable")
        if (isPackageInstalled("org.smarttube.beta")) installed.add("org.smarttube.beta")
        return installed
    }

    internal fun launchSmartTube(packageName: String, trailerUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, trailerUrl.toUri())
        intent.setPackage(packageName)
        context.startActivity(intent)
    }

    internal fun showSmartTubeVersionDialog(packages: List<String>, trailerUrl: String, shouldSavePreference: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()

        val items = packages.map { pkg ->
            if (pkg == "org.smarttube.stable") context.getString(R.string.smarttube_stable)
            else context.getString(R.string.smarttube_beta)
        }.toTypedArray()

        (if (ExperimentalMobileDesign.enabled()) MaterialAlertDialogBuilder(context) else AlertDialog.Builder(context))
            .setTitle(context.getString(R.string.choose_smarttube_version))
            .setItems(items) { _, which ->
                val selectedPackage = packages[which]

                if (shouldSavePreference) {
                    editor.putString("preferred_smarttube_package", selectedPackage).apply()
                }

                launchSmartTube(selectedPackage, trailerUrl)
            }
            .create()
            .also { com.dskja.betterstreamflix.ui.TrailerPlaybackController.polishChooserDialog(it) }
    }

    internal fun handleSmartTubeSelection(trailerUrl: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedPackage = prefs.getString("preferred_smarttube_package", null)
        val stPackages = getInstalledSmartTubePackages()

        if (stPackages.isEmpty()) {
            context.startActivity(Intent(Intent.ACTION_VIEW, trailerUrl.toUri()))
            return
        }

        if (stPackages.size == 1) {
            launchSmartTube(stPackages[0], trailerUrl)
            return
        }

        if (savedPackage != null && stPackages.contains(savedPackage)) {
            launchSmartTube(savedPackage, trailerUrl)
        } else {
            showSmartTubeVersionDialog(stPackages, trailerUrl, true)
        }
    }

    internal fun safeLaunchYoutube(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("TvShowViewHolder", "Failed to launch YouTube intent", e)
            val message = context.getString(R.string.player_external_player_error_video)
            if (ExperimentalMobileDesign.enabled()) {
                ExpDialogChrome.showInfo(
                    context,
                    R.string.youtube,
                    message,
                ) { ctx ->
                    MaterialAlertDialogBuilder(ctx)
                }
            } else {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun handleTrailerClick(trailer: String) {
        val activity = context.toActivity()
        val fragment = activity?.getCurrentFragment() as? Fragment
        if (fragment != null) {
            TrailerPlaybackController.play(fragment, trailer)
        } else {
            TrailerPlaybackController.play(
                context,
                activity,
                trailer,
                activity?.supportFragmentManager,
            )
        }
    }

    private fun displaySwiperMobileItem(binding: ItemCategorySwiperMobileBinding) {
        binding.ivSwiperBackground.loadTvShowBanner(tvShow) {
            override(FeaturedSwiperChrome.ARTWORK_WIDTH, FeaturedSwiperChrome.ARTWORK_HEIGHT)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
        }

        itemView.contentDescription = tvShow.title
        FeaturedSwiperChrome.resolveAndBindLogo(binding, tvShow)
        binding.tvSwiperTitle.setTextColor(0xFFF7F7F8.toInt())
        binding.tvSwiperGenres.setTextColor(0xFFE8E8EC.toInt())

        binding.tvSwiperStatus.visibility = View.GONE
        binding.tvSwiperOverview.visibility = View.GONE
        binding.tvSwiperTvShowLastEpisode.visibility = View.GONE
        binding.tvSwiperQuality.visibility = View.GONE
        binding.tvSwiperReleased.visibility = View.GONE
        binding.tvSwiperRating.visibility = View.GONE
        binding.ivSwiperRatingIcon.visibility = View.GONE
        binding.pbSwiperProgress.visibility = View.GONE

        binding.tvSwiperGenres.apply {
            val genres = FeaturedHeroController.genresLine(tvShow)
            val status = FeaturedHeroController.statusLine(context, tvShow)
            text = when {
                genres.isNotEmpty() -> genres
                !status.isNullOrBlank() -> status
                else -> context.getString(R.string.home_swiper_all_episodes)
            }
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        val openTvShow = View.OnClickListener {
            ExpMotion.hapticTap(it)
            FeaturedProviderSwitch.runWithProvider(tvShow) {
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                }
            }
        }

        binding.btnSwiperWatchNow.apply {
            FeaturedSwiperChrome.wireWatchButton(this)
            text = FeaturedHeroController.watchCtaLabel(context, tvShow)
            applyExpPress()
            setOnClickListener(openTvShow)
            setOnLongClickListener { view ->
                fun playTrailer(url: String) {
                    ExpMotion.hapticTap(view)
                    val activity = context.toActivity() as? androidx.fragment.app.FragmentActivity
                    TrailerPlaybackController.play(
                        context = view.context,
                        activity = activity,
                        trailerUrl = url,
                    )
                }
                val existing = tvShow.trailer
                if (!existing.isNullOrBlank()) {
                    playTrailer(existing)
                    return@setOnLongClickListener true
                }
                val year = tvShow.released?.format("yyyy")?.toIntOrNull()
                if (!TmdbUtils.hasTrailerLookupKeys(
                        tmdbId = tvShow.tmdbId,
                        imdbId = tvShow.imdbId,
                        title = tvShow.title,
                        year = year,
                    )
                ) {
                    return@setOnLongClickListener false
                }
                ExpMotion.hapticTap(view)
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
                    val url = remote.firstOrNull()?.second?.takeIf { it.isNotBlank() } ?: return@launch
                    if (tvShow.trailer.isNullOrBlank()) tvShow.trailer = url
                    playTrailer(url)
                }
                true
            }
        }

        FeaturedSwiperChrome.bindListButton(binding.btnSwiperAddToList, tvShow.isFavorite)
        binding.btnSwiperAddToList.apply {
            applyExpPress()
            setOnClickListener {
                ExpMotion.hapticTap(it)
                FeaturedSwiperChrome.toggleTvShowFavorite(
                    anchor = itemView,
                    button = this,
                    tvShow = tvShow,
                )
            }
        }
        ribbonStateJob?.cancel()
        val boundTvShowId = tvShow.id
        ribbonStateJob = FeaturedSwiperChrome.observeListStateTv(
            anchor = itemView,
            button = binding.btnSwiperAddToList,
            tvShowId = boundTvShowId,
        ) { favorite ->
            if (tvShow.id != boundTvShowId) return@observeListStateTv
            tvShow.isFavorite = favorite
            FeaturedSwiperChrome.bindListButton(binding.btnSwiperAddToList, favorite)
        }

        // Nested clickables confuse TalkBack — keep chrome buttons primary.
        binding.root.isClickable = false
        binding.root.setOnClickListener(null)
        binding.ivSwiperBackground.apply {
            isClickable = true
            contentDescription = tvShow.title
            setOnClickListener(openTvShow)
        }
    }

    private fun setRibbonVisible(view: View, visible: Boolean) {
        val wasVisible = view.isVisible
        view.isVisible = visible
        if (visible && ExperimentalMobileDesign.enabled()) {
            view.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
            val pad = (4 * context.resources.displayMetrics.density).toInt()
            view.setPadding(pad, pad, pad, pad)
            if (!wasVisible || view.getTag(R.id.exp_enter_animated_tag) != true) {
                view.setTag(R.id.exp_enter_animated_tag, true)
                if (!wasVisible) ExpMotion.popIn(view)
            }
        } else if (!visible) {
            view.background = null
            view.setTag(R.id.exp_enter_animated_tag, null)
        }
    }

    private fun tvShowHasOffline(keys: Set<String> = OfflineBadgeStore.completedKeys(context).value): Boolean {
        val providerName = tvShow.providerName
            ?: UserPreferences.currentProvider?.name
            ?: return false
        val episodePrefix = "episode|$providerName|${tvShow.id}|"
        val seasonPrefix = "season|$providerName|${tvShow.id}|"
        return keys.any { it.startsWith(episodePrefix) || it.startsWith(seasonPrefix) }
    }

    private fun bindRibbons(favoriteRibbon: View, watchedRibbon: View, downloadRibbon: View? = null) {
        ribbonStateJob?.cancel()

        val boundTvShowId = tvShow.id
        setRibbonVisible(favoriteRibbon, tvShow.isFavorite)
        setRibbonVisible(watchedRibbon, false)
        downloadRibbon?.let { setRibbonVisible(it, tvShowHasOffline()) }
        val lifecycleOwner = itemView.findViewTreeLifecycleOwner()
            ?: context.toActivity()
            ?: return

        ribbonStateJob = lifecycleOwner.lifecycleScope.launch {
            launch {
                combine(
                    database.tvShowDao().getByIdAsFlow(boundTvShowId),
                    database.episodeDao().isTvShowFullyWatchedAsFlow(boundTvShowId),
                ) { persistedTvShow, isFullyWatched ->
                    (persistedTvShow?.isFavorite ?: tvShow.isFavorite) to isFullyWatched
                }.collect { (isFavorite, isFullyWatched) ->
                    if (tvShow.id == boundTvShowId) {
                        setRibbonVisible(favoriteRibbon, isFavorite)
                        setRibbonVisible(watchedRibbon, isFullyWatched)
                    }
                }
            }
            if (downloadRibbon != null) {
                launch {
                    OfflineBadgeStore.completedKeys(context).collect { keys ->
                        if (tvShow.id != boundTvShowId) return@collect
                        setRibbonVisible(downloadRibbon, tvShowHasOffline(keys))
                    }
                }
            }
        }
    }

    private fun displayTvShowMobile(binding: ContentTvShowMobileBinding) =
        bindTvShowMobileDetail(binding)

    private fun displayTvShowTv(binding: ContentTvShowTvBinding) =
        bindTvShowTvDetail(binding)

    private fun displaySeasonsMobile(binding: ContentTvShowSeasonsMobileBinding) =
        bindTvShowSeasonsMobile(binding)

    private fun displaySeasonsTv(binding: ContentTvShowSeasonsTvBinding) {
        if (ExperimentalMobileDesign.enabled()) {
            binding.tvTvShowSeasonsLabel.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvTvShowSeasonsLabel,
                    androidx.appcompat.R.attr.colorPrimary,
                ),
            )
            binding.root.findViewById<View>(R.id.v_tv_show_seasons_rule)?.visibility = View.VISIBLE
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(
                    binding.tvTvShowSeasonsLabel,
                    binding.root.findViewById(R.id.v_tv_show_seasons_rule),
                )
                ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_tv_show_seasons_rule))
                ExpMotion.staggerFirstFill(binding.hgvTvShowSeasons)
            }
        }
        binding.hgvTvShowSeasons.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply { submitList(tvShow.seasons.onEach { it.itemType = AppAdapter.Type.SEASON_TV_ITEM }) }
            setItemSpacing(20)
        }
    }

    private fun displayDirectorsMobile(binding: ContentTvShowDirectorsMobileBinding) {
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_tv_show_directors_label),
                binding.root.findViewById(R.id.v_tv_show_directors_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_tv_show_directors_rule))
        }
        binding.rvTvShowDirectors.apply {
            adapter = AppAdapter().apply {
                submitList(tvShow.directors.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(20.dp(context)))
            }
        }
    }
    private fun displayDirectorsTv(binding: ContentTvShowDirectorsTvBinding) {
        if (ExperimentalMobileDesign.enabled()) {
            binding.tvTvShowDirectorsLabel.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvTvShowDirectorsLabel,
                    androidx.appcompat.R.attr.colorPrimary,
                ),
            )
            binding.root.findViewById<View>(R.id.v_tv_show_directors_rule)?.visibility = View.VISIBLE
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(
                    binding.tvTvShowDirectorsLabel,
                    binding.root.findViewById(R.id.v_tv_show_directors_rule),
                )
                ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_tv_show_directors_rule))
                ExpMotion.staggerFirstFill(binding.hgvTvShowDirectors)
            }
        }
        binding.hgvTvShowDirectors.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(tvShow.directors.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                })
            }
            setItemSpacing(80)
        }
    }
    private fun displayCastMobile(binding: ContentTvShowCastMobileBinding) {
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_tv_show_cast_label),
                binding.root.findViewById(R.id.v_tv_show_cast_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_tv_show_cast_rule))
            ExpMotion.staggerFirstFill(binding.rvTvShowCast)
        }
        binding.rvTvShowCast.apply {
            adapter = AppAdapter().apply {
                submitList(tvShow.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayCastTv(binding: ContentTvShowCastTvBinding) {
        if (ExperimentalMobileDesign.enabled()) {
            binding.tvTvShowCastLabel.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvTvShowCastLabel,
                    androidx.appcompat.R.attr.colorPrimary,
                ),
            )
            binding.root.findViewById<View>(R.id.v_tv_show_cast_rule)?.visibility = View.VISIBLE
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(
                    binding.tvTvShowCastLabel,
                    binding.root.findViewById(R.id.v_tv_show_cast_rule),
                )
                ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_tv_show_cast_rule))
                ExpMotion.staggerFirstFill(binding.hgvTvShowCast)
            }
        }
        binding.hgvTvShowCast.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(tvShow.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                })
            }
            setItemSpacing(20)
        }
    }
    private fun displayRecommendationsMobile(binding: ContentTvShowRecommendationsMobileBinding) {
        binding.root.tag = DETAIL_SECTION_RECOMMENDATIONS
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_tv_show_recommendations_label),
                binding.root.findViewById(R.id.v_tv_show_recommendations_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_tv_show_recommendations_rule))
            ExpMotion.staggerFirstFill(binding.rvTvShowRecommendations)
        }
        val empty = tvShow.recommendations.isEmpty()
        binding.root.findViewById<View>(R.id.tv_tv_show_recommendations_empty)?.visibility =
            if (empty) View.VISIBLE else View.GONE
        binding.rvTvShowRecommendations.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) return
        binding.rvTvShowRecommendations.apply {
            val grid = layoutManager as? GridLayoutManager
            if (grid == null) {
                layoutManager = GridLayoutManager(context, 3)
            } else if (grid.spanCount != 3) {
                grid.spanCount = 3
            }
            adapter = AppAdapter().apply {
                submitList(tvShow.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                    }
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayRecommendationsTv(binding: ContentTvShowRecommendationsTvBinding) {
        if (ExperimentalMobileDesign.enabled()) {
            binding.tvTvShowRecommendationsLabel.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvTvShowRecommendationsLabel,
                    androidx.appcompat.R.attr.colorPrimary,
                ),
            )
            binding.root.findViewById<View>(R.id.v_tv_show_recommendations_rule)?.visibility = View.VISIBLE
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(
                    binding.tvTvShowRecommendationsLabel,
                    binding.root.findViewById(R.id.v_tv_show_recommendations_rule),
                )
                ExpMotion.pulseAccentRule(
                    binding.root.findViewById(R.id.v_tv_show_recommendations_rule),
                )
                ExpMotion.staggerFirstFill(binding.hgvTvShowRecommendations)
            }
        }
        binding.hgvTvShowRecommendations.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(tvShow.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                })
            }
            setItemSpacing(20)
        }
    }

    private fun displayTabsMobile(binding: ContentDetailTabsMobileBinding) {
        val adapter = bindingAdapter as? AppAdapter
        DetailTabsController.bind(
            binding = binding,
            selected = adapter?.selectedDetailTab ?: DetailTab.EPISODES,
            showEpisodes = tvShow.seasons.isNotEmpty(),
            showSimilar = tvShow.recommendations.isNotEmpty(),
            onSelect = { tab -> adapter?.onDetailTabSelectedListener?.invoke(tab) },
        )
    }

    private fun displayTrailerMobile(binding: ContentDetailTrailerMobileBinding) {
        val player = binding.root.getTag(R.id.detail_trailer_player_tag) as? DetailTrailerMobilePlayer
            ?: DetailTrailerMobilePlayer(binding).also {
                binding.root.setTag(R.id.detail_trailer_player_tag, it)
            }
        player.bind(
            seedUrl = tvShow.trailer,
            title = tvShow.title,
            trailerLabel = context.getString(R.string.tv_show_trailer),
            tmdbId = tvShow.tmdbId,
            isTv = true,
            year = tvShow.released?.format("yyyy")?.toIntOrNull(),
            imdbId = tvShow.imdbId,
            sectionTag = DETAIL_SECTION_TRAILER,
        )
    }

    private fun displayTrailerTv(binding: ContentDetailTrailerTvBinding) {
        DetailTrailerTvController.bind(
            binding = binding,
            seedUrl = tvShow.trailer,
            title = tvShow.title,
            trailerLabel = context.getString(R.string.tv_show_trailer),
            tmdbId = tvShow.tmdbId,
            isTv = true,
            year = tvShow.released?.format("yyyy")?.toIntOrNull(),
            imdbId = tvShow.imdbId,
            onTrailerSeeded = { url -> tvShow.trailer = url },
        )
    }

    private fun displayAboutMobile(binding: ContentDetailAboutMobileBinding) {
        binding.root.tag = DETAIL_SECTION_ABOUT
        val overview = tvShow.overview.orEmpty()
        binding.tvDetailAboutOverview.text = overview
        binding.tvDetailAboutOverview.visibility =
            if (overview.isBlank()) View.GONE else View.VISIBLE
        binding.tvDetailAboutOverviewLabel.visibility =
            if (overview.isBlank()) View.GONE else View.VISIBLE

        // Legacy cast/crew walls stay gone — Actor / Cast / Director systems cover them.
        binding.tvDetailAboutFeaturing.visibility = View.GONE
        binding.tvDetailAboutFeaturingLabel.visibility = View.GONE
        binding.tvDetailAboutDirectors.visibility = View.GONE
        binding.tvDetailAboutDirectorsLabel.visibility = View.GONE
        binding.tvDetailAboutCast.visibility = View.GONE
        binding.tvDetailAboutCastLabel.visibility = View.GONE

        fun bindFact(label: View, value: android.widget.TextView, text: String?) {
            val visible = !text.isNullOrBlank()
            label.visibility = if (visible) View.VISIBLE else View.GONE
            value.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) value.text = text
        }

        // Hero already shows genres / seasons / year / rating / cert — keep About for extras.
        binding.tvDetailAboutGenresLabel.visibility = View.GONE
        binding.tvDetailAboutGenres.visibility = View.GONE
        binding.tvDetailAboutRuntimeLabel.visibility = View.GONE
        binding.tvDetailAboutRuntime.visibility = View.GONE
        binding.tvDetailAboutYearLabel.visibility = View.GONE
        binding.tvDetailAboutYear.visibility = View.GONE
        binding.tvDetailAboutRatingLabel.visibility = View.GONE
        binding.tvDetailAboutRating.visibility = View.GONE
        binding.tvDetailAboutCertLabel.visibility = View.GONE
        binding.tvDetailAboutCert.visibility = View.GONE
        binding.tvDetailAboutSeasonsLabel.visibility = View.GONE
        binding.tvDetailAboutSeasons.visibility = View.GONE

        bindFact(
            binding.tvDetailAboutQualityLabel,
            binding.tvDetailAboutQuality,
            tvShow.quality?.takeIf { it.isNotBlank() },
        )
        bindFact(
            binding.tvDetailAboutProviderLabel,
            binding.tvDetailAboutProvider,
            tvShow.providerName?.takeIf { it.isNotBlank() },
        )

        val ids = buildList {
            tvShow.tmdbId?.takeIf { it.isNotBlank() }?.let {
                add(context.getString(R.string.detail_about_id_tmdb, it))
            }
            tvShow.imdbId?.takeIf { it.isNotBlank() }?.let {
                add(context.getString(R.string.detail_about_id_imdb, it))
            }
        }.joinToString(" · ")
        bindFact(binding.tvDetailAboutIdsLabel, binding.tvDetailAboutIds, ids.ifBlank { null })

        val anyFact = listOf(
            binding.tvDetailAboutQuality,
            binding.tvDetailAboutProvider,
            binding.tvDetailAboutIds,
        ).any { it.visibility == View.VISIBLE }
        binding.tvDetailAboutFactsLabel.visibility =
            if (anyFact) View.VISIBLE else View.GONE
        binding.llDetailAboutFacts.visibility =
            if (anyFact) View.VISIBLE else View.GONE
    }

    companion object {
        const val DETAIL_SECTION_SEASONS = "detail_section_seasons"
        const val DETAIL_SECTION_RECOMMENDATIONS = "detail_section_recommendations"
        const val DETAIL_SECTION_TRAILER = "detail_section_trailer"
        const val DETAIL_SECTION_ABOUT = "detail_section_about"
    }

}
