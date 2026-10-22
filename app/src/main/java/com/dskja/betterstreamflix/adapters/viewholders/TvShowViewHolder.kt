package com.dskja.betterstreamflix.adapters.viewholders

import com.dskja.betterstreamflix.models.TrailerCatalog
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
import com.dskja.betterstreamflix.utils.AccessibilityPreferences
import com.dskja.betterstreamflix.utils.TvFocusZoom
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.adapters.submitAppList
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.*
import com.dskja.betterstreamflix.fragments.home.HomeTvFragment
import com.dskja.betterstreamflix.fragments.home.HomeTvFragmentDirections
import com.dskja.betterstreamflix.fragments.live.LiveHubTvFragment
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import com.dskja.betterstreamflix.utils.DeviceCapabilities
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
import com.dskja.betterstreamflix.ui.DetailTrailerPlayHooks
import com.dskja.betterstreamflix.ui.DetailTrailerTvController
import com.dskja.betterstreamflix.ui.TrailerPlayPipeline
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
import com.dskja.betterstreamflix.logo.FeaturedLogoEnrich
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
import com.dskja.betterstreamflix.utils.ArtworkUrls
import com.dskja.betterstreamflix.providers.Provider
import java.util.Locale
import com.dskja.betterstreamflix.models.Trailer

class TvShowViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    internal val context = itemView.context
    internal val database: AppDatabase?
        get() = AppDatabase.getInstanceOrNull(context)

    init {
        if ((_binding is ItemTvShowMobileBinding ||
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
        if (isIptvProvider()) {
            // EPG-lite: prefer “now playing” on Live Hub / IPTV tiles.
            val now = com.dskja.betterstreamflix.utils.LiveHubCatalog.programNowOf(tvShow)
                ?.trim()
                .orEmpty()
            if (now.isNotBlank()) {
                return if (now.length > 28) now.take(27).trimEnd() + "…" else now
            }
            return context.getString(R.string.player_live_badge)
        }
        return tvShow.lastPlayedEpisode?.let {
            context.getString(R.string.episode_badge_format, it.number)
        }
            ?: tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let {
                context.getString(R.string.episode_badge_format, it.number)
            }
            ?: tvShow.released?.format("yyyy")
            ?: context.getString(R.string.tv_show_item_type)
    }

    private fun bindEpisodeBadge(badge: android.widget.TextView) {
        val text = episodeBadgeText()
        val wasVisible = badge.isVisible
        badge.text = text
        badge.isVisible = text.isNotBlank()
        if (isIptvProvider()) {
            badge.setBackgroundResource(R.drawable.bg_meta_pill)
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
        } else {
            badge.clearAnimation()
            badge.setBackgroundResource(R.drawable.bg_meta_pill)
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
        FeaturedProviderSwitch.switchSticky(tvShow.providerName, context)
        action()
    }

    internal fun handleDirectPlay(navController: NavController) {
        if (isIptvProvider() &&
            com.dskja.betterstreamflix.utils.ParentalControlUtils.isFilteringActive()
        ) {
            android.widget.Toast.makeText(
                itemView.context,
                R.string.live_hub_restricted,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
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
                title = itemView.context.getString(R.string.player_live_season),
            ),
        )
        
        val args = Bundle().apply {
            putString("id", tvShow.id)
            putString("title", tvShow.title)
            putString("subtitle", tvShow.title)
            putSerializable("videoType", videoType)
        }
        val provider = com.dskja.betterstreamflix.providers.Provider
            .findByName(tvShow.providerName.orEmpty())
            ?: com.dskja.betterstreamflix.utils.UserPreferences.currentProvider
        // Live Hub: replaceWindow with the tapped shelf only (never merge all categories).
        val fromLiveHub = com.dskja.betterstreamflix.fragments.live.LiveHubSectionZap
            .preparePlayback(tvShow, provider)
        if (!fromLiveHub && isIptvProvider()) {
            // Do not collapse an existing zap window to one channel.
            if (!com.dskja.betterstreamflix.iptv.IptvLiveSession.contains(tvShow.id)) {
                com.dskja.betterstreamflix.iptv.IptvLiveSession.rememberShows(
                    listOf(tvShow),
                    provider,
                    clearOnProviderChange = false,
                )
            }
            com.dskja.betterstreamflix.iptv.IptvLiveSession.setCurrent(tvShow.id)
        } else if (fromLiveHub) {
            com.dskja.betterstreamflix.iptv.IptvLiveSession.setCurrent(tvShow.id)
        }
        val liveAction = R.id.action_live_hub_to_player
        if (navController.currentDestination?.getAction(liveAction) != null) {
            navController.navigate(liveAction, args)
        } else {
            navController.navigate(R.id.player, args)
        }
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

    private fun setPoster(
        imageView: ImageView,
        shelfDensity: Boolean = false,
        liveShelf: Boolean = false,
    ) {
        imageView.scaleType = if (isIptvProvider()) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        imageView.loadTvShowPoster(tvShow) {
            val dens = imageView.resources.displayMetrics.density
            val (w, h) = when {
                liveShelf ->
                    com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.liveShelfPosterOverridePx(dens)
                shelfDensity ->
                    com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.shelfPosterOverridePx(dens)
                else ->
                    com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.gridPosterOverridePx(dens)
            }
            val reduce = DeviceCapabilities.shouldReduceHomeEffects(imageView.context)
            fallback(R.drawable.glide_fallback_cover)
                .error(R.drawable.glide_fallback_cover)
                .override(w, h)
                .centerCrop()
                .let { request ->
                    if (reduce) request.dontAnimate()
                    else request.transition(DrawableTransitionOptions.withCrossFade(120))
                }
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
            if (show) {
                setBackgroundResource(R.drawable.bg_meta_pill)
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }
        bindTvShowProgress(binding.pbTvShowProgress)
        bindEpisodeBadge(binding.tvTvShowLastEpisode)
        binding.tvTvShowTitle.text = tvShow.title
        com.dskja.betterstreamflix.logo.TitleLogoSurface.bindAndMaybeResolve(
            anchor = binding.root,
            imageView = binding.ivTvShowLogo,
            titleView = null,
            tvShow = tvShow,
            hideUntilReady = true,
        )
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true
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
                        fragment.pinBackground(
                            ArtworkUrls.featuredBannerOrPoster(tvShow.banner, tvShow.poster),
                        )
                    } else {
                        fragment.releasePinnedBackground()
                    }
                }
            }
        }
        // LD-026: Live Hub shelves use denser Compact poster height.
        val onLiveHub = context.toActivity()?.getCurrentFragment() is LiveHubTvFragment
        if (onLiveHub) {
            com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.applyLiveShelfPosterHeight(
                binding.ivTvShowPoster,
            )
            setPoster(binding.ivTvShowPoster, shelfDensity = true, liveShelf = true)
        } else {
            com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.applyShelfPosterHeight(
                binding.ivTvShowPoster,
            )
            setPoster(binding.ivTvShowPoster, shelfDensity = true)
        }
        bindRibbons(binding.ivTvShowFavoriteRibbon, binding.ivTvShowWatchedRibbon, binding.root.findViewById(R.id.iv_tv_show_download_ribbon))
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            val show = !text.isNullOrEmpty()
            val wasVisible = isVisible
            isVisible = show
        }
        bindTvShowProgress(binding.pbTvShowProgress)
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
            if (show) {
                setBackgroundResource(R.drawable.bg_meta_pill)
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }
        bindTvShowProgress(binding.pbTvShowProgress)
        bindEpisodeBadge(binding.tvTvShowLastEpisode)
        binding.tvTvShowTitle.text = tvShow.title
        com.dskja.betterstreamflix.logo.TitleLogoSurface.bindAndMaybeResolve(
            anchor = binding.root,
            imageView = binding.ivTvShowLogo,
            titleView = null,
            tvShow = tvShow,
            hideUntilReady = true,
        )
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true
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
        bindTvShowProgress(binding.pbTvShowProgress)
        bindEpisodeBadge(binding.tvTvShowLastEpisode)
        binding.tvTvShowTitle.text = tvShow.title
    }

    /**
     * Prefer [TvShow.lastPlayedEpisode] (hydrated on Recently Watched Home rows)
     * over [TvShow.episodeToWatch], which needs loaded seasons.
     */
    private fun bindTvShowProgress(bar: android.widget.ProgressBar) {
        val watchHistory = tvShow.lastPlayedEpisode?.watchHistory
            ?: tvShow.episodeToWatch?.watchHistory
        val target = when {
            watchHistory != null && watchHistory.durationMillis > 0 ->
                (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
            else -> 0
        }
        val show = watchHistory != null && watchHistory.durationMillis > 0
        val wasVisible = bar.isVisible
        bar.isVisible = show
        if (show && (!wasVisible || bar.progress != target)) {
            android.animation.ObjectAnimator.ofInt(bar, "progress", 0, target)
                .setDuration(420L)
                .start()
        } else {
            bar.progress = target
        }
        if (show && !wasVisible) {
            ExpMotion.popIn(bar)
        }
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
            val stroke = com.google.android.material.color.MaterialColors.getColor(
                    view,
                    androidx.appcompat.R.attr.colorPrimary,
                )
            view.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(width, stroke)
                cornerRadius = 10f * context.resources.displayMetrics.density
            }
            view.setPadding(width, width, width, width)
            if (view.getTag(R.id.exp_enter_animated_tag) != "selected"
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
        val (artW, artH) = FeaturedSwiperChrome.artworkOverride(binding.ivSwiperBackground)
        val reduceArt = DeviceCapabilities.shouldReduceHomeEffects(binding.ivSwiperBackground.context)
        binding.ivSwiperBackground.loadTvShowBanner(tvShow, hero = false) {
            override(artW, artH)
                .centerCrop()
                .error(R.drawable.glide_fallback_cover)
                .let { request ->
                    if (reduceArt) request.dontAnimate()
                    else request.transition(DrawableTransitionOptions.withCrossFade(160))
                }
        }

        applyPosterA11y()
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
            FeaturedProviderSwitch.runWithProvider(tvShow, context) {
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                }
            }
        }

        binding.btnSwiperWatchNow.apply {
            FeaturedSwiperChrome.wireWatchButton(this)
            com.dskja.betterstreamflix.player.PlayerCleanExit.tagFeaturedWatch(this)
            text = FeaturedHeroController.watchCtaLabel(context, tvShow)
            contentDescription = context.getString(R.string.a11y_featured_watch, tvShow.title)
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
                    val url = TrailerCatalog.preferredPlayableUrl(remote) ?: return@launch
                    if (tvShow.trailer.isNullOrBlank()) tvShow.trailer = url
                    playTrailer(url)
                }
                true
            }
            androidx.appcompat.widget.TooltipCompat.setTooltipText(
                this,
                if (!tvShow.trailer.isNullOrBlank() ||
                    TmdbUtils.hasTrailerLookupKeys(
                        tmdbId = tvShow.tmdbId,
                        imdbId = tvShow.imdbId,
                        title = tvShow.title,
                        year = tvShow.released?.format("yyyy")?.toIntOrNull(),
                    )
                ) {
                    context.getString(R.string.home_swiper_trailer)
                } else {
                    text
                },
            )
        }

        FeaturedSwiperChrome.bindMobileListButton(
            binding.btnSwiperAddToList,
            tvShow.isFavorite,
            title = tvShow.title,
        )
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
            FeaturedSwiperChrome.bindMobileListButton(
                binding.btnSwiperAddToList,
                favorite,
                title = tvShow.title,
            )
        }

        // Featured cover upgrade — poster-as-banner providers get a real TMDb backdrop.
        FeaturedLogoEnrich.enrichBackdropIfNeeded(
            anchor = itemView,
            imageView = binding.ivSwiperBackground,
            tvShow = tvShow,
        )

        // Nested clickables confuse TalkBack — keep chrome buttons primary.
        binding.root.isClickable = false
        binding.root.setOnClickListener(null)
        binding.ivSwiperBackground.apply {
            isClickable = true
            contentDescription = AccessibilityPreferences.posterLabel(
                context,
                tvShow.title,
                favorite = tvShow.isFavorite,
            )
            setOnClickListener(openTvShow)
        }
    }

    private fun setRibbonVisible(view: View, visible: Boolean) {
        val wasVisible = view.isVisible
        view.isVisible = visible
        if (visible) {
            view.setBackgroundResource(R.drawable.bg_detail_icon_chip)
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

        favoriteRibbon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        watchedRibbon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        downloadRibbon?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        val boundTvShowId = tvShow.id
        val downloaded = tvShowHasOffline()
        setRibbonVisible(favoriteRibbon, tvShow.isFavorite)
        setRibbonVisible(watchedRibbon, false)
        downloadRibbon?.let { setRibbonVisible(it, downloaded) }
        applyPosterA11y(tvShow.isFavorite, watched = false, downloaded = downloaded)
        val lifecycleOwner = itemView.findViewTreeLifecycleOwner()
            ?: context.toActivity()
            ?: return

        ribbonStateJob = lifecycleOwner.lifecycleScope.launch {
            launch {
                val db = database ?: return@launch
                combine(
                    db.tvShowDao().getByIdAsFlow(boundTvShowId),
                    db.episodeDao().isTvShowFullyWatchedAsFlow(boundTvShowId),
                ) { persistedTvShow, isFullyWatched ->
                    (persistedTvShow?.isFavorite ?: tvShow.isFavorite) to isFullyWatched
                }.collect { (isFavorite, isFullyWatched) ->
                    if (tvShow.id == boundTvShowId) {
                        tvShow.isFavorite = isFavorite
                        setRibbonVisible(favoriteRibbon, isFavorite)
                        setRibbonVisible(watchedRibbon, isFullyWatched)
                        applyPosterA11y(isFavorite, isFullyWatched, tvShowHasOffline())
                    }
                }
            }
            if (downloadRibbon != null) {
                launch {
                    OfflineBadgeStore.completedKeys(context).collect { keys ->
                        if (tvShow.id != boundTvShowId) return@collect
                        val isDownloaded = tvShowHasOffline(keys)
                        setRibbonVisible(downloadRibbon, isDownloaded)
                        applyPosterA11y(tvShow.isFavorite, downloaded = isDownloaded)
                    }
                }
            }
        }
    }

    private fun applyPosterA11y(
        favorite: Boolean = tvShow.isFavorite,
        watched: Boolean = false,
        downloaded: Boolean = false,
    ) {
        itemView.contentDescription = AccessibilityPreferences.posterLabel(
            context,
            tvShow.title,
            favorite = favorite,
            watched = watched,
            downloaded = downloaded,
        )
        AccessibilityPreferences.announceSelected(itemView, itemSelected || favorite)
    }

    private fun displayTvShowMobile(binding: ContentTvShowMobileBinding) =
        bindTvShowMobileDetail(binding)

    private fun displayTvShowTv(binding: ContentTvShowTvBinding) =
        bindTvShowTvDetail(binding)

    private fun displaySeasonsMobile(binding: ContentTvShowSeasonsMobileBinding) =
        bindTvShowSeasonsMobile(binding)

    private fun displaySeasonsTv(binding: ContentTvShowSeasonsTvBinding) {
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
        binding.hgvTvShowSeasons.apply {
            // Always pin after bind — Leanback WRAP_CONTENT HGV focus bugs on Fire OS.
            setRowHeight((210 * resources.displayMetrics.density).toInt())
            setTag(R.id.tv_shelf_row_height_tag, true)
            submitAppList(tvShow.seasons.onEach { it.itemType = AppAdapter.Type.SEASON_TV_ITEM })
            setItemSpacing(20)
        }
    }

    private fun displayDirectorsMobile(binding: ContentTvShowDirectorsMobileBinding) {
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_tv_show_directors_label),
                binding.root.findViewById(R.id.v_tv_show_directors_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_tv_show_directors_rule))
        }
        binding.rvTvShowDirectors.apply {
            submitAppList(tvShow.directors.onEach {
                it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
            })
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(20.dp(context)))
            }
        }
    }
    private fun displayDirectorsTv(binding: ContentTvShowDirectorsTvBinding) {
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
        binding.hgvTvShowDirectors.apply {
            // Always pin after bind — Leanback WRAP_CONTENT HGV focus bugs on Fire OS.
            setRowHeight((210 * resources.displayMetrics.density).toInt())
            setTag(R.id.tv_shelf_row_height_tag, true)
            submitAppList(tvShow.directors.onEach {
                it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
            })
            setItemSpacing(80)
        }
    }
    private fun displayCastMobile(binding: ContentTvShowCastMobileBinding) {
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true
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
            submitAppList(tvShow.cast.onEach {
                it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
            })
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayCastTv(binding: ContentTvShowCastTvBinding) {
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
        binding.hgvTvShowCast.apply {
            // Always pin after bind — Leanback WRAP_CONTENT HGV focus bugs on Fire OS.
            setRowHeight((210 * resources.displayMetrics.density).toInt())
            setTag(R.id.tv_shelf_row_height_tag, true)
            submitAppList(tvShow.cast.onEach {
                it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
            })
            setItemSpacing(20)
        }
    }
    private fun displayRecommendationsMobile(binding: ContentTvShowRecommendationsMobileBinding) {
        binding.root.tag = DETAIL_SECTION_RECOMMENDATIONS
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true
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
            submitAppList(tvShow.recommendations.onEach {
                when (it) {
                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                }
            })
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayRecommendationsTv(binding: ContentTvShowRecommendationsTvBinding) {
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
        binding.hgvTvShowRecommendations.apply {
            // Always pin after bind — Leanback WRAP_CONTENT HGV focus bugs on Fire OS.
            setRowHeight((210 * resources.displayMetrics.density).toInt())
            setTag(R.id.tv_shelf_row_height_tag, true)
            submitAppList(tvShow.recommendations.onEach {
                when (it) {
                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                }
            })
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
                        handleDirectPlay(binding.root.findNavController())
                    } else {
                        base.onStreamNow?.invoke()
                    }
                }
            },
        )
        player.bind(
            seedUrl = tvShow.trailer,
            title = tvShow.title,
            trailerLabel = context.getString(R.string.tv_show_trailer),
            tmdbId = tvShow.tmdbId,
            isTv = true,
            year = tvShow.released?.format("yyyy")?.toIntOrNull(),
            imdbId = tvShow.imdbId,
            sectionTag = DETAIL_SECTION_TRAILER,
            pipelineSession = session,
        )
    }

    private fun displayTrailerTv(binding: ContentDetailTrailerTvBinding) {
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
                        handleDirectPlay(binding.root.findNavController())
                    } else {
                        base.onStreamNow?.invoke()
                    }
                }
            },
        )
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
            pipelineSession = session,
        )
    }

    private fun displayAboutMobile(binding: ContentDetailAboutMobileBinding) {
        binding.root.tag = DETAIL_SECTION_ABOUT
        // Hero already shows synopsis — About keeps extras (quality / provider / IDs).
        binding.tvDetailAboutOverview.visibility = View.GONE
        binding.tvDetailAboutOverviewLabel.visibility = View.GONE

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

        val tmdbPart = tvShow.tmdbId?.takeIf { it.isNotBlank() }?.let {
            context.getString(R.string.detail_about_id_tmdb, it)
        }
        val imdbPart = tvShow.imdbId?.takeIf { it.isNotBlank() }?.let {
            context.getString(R.string.detail_about_id_imdb, it)
        }
        val ids = listOfNotNull(tmdbPart, imdbPart).joinToString(" · ")
        bindFact(binding.tvDetailAboutIdsLabel, binding.tvDetailAboutIds, ids.ifBlank { null })
        if (!tmdbPart.isNullOrBlank() && binding.tvDetailAboutIds.visibility == View.VISIBLE) {
            val spanned = android.text.SpannableString(ids)
            val tmdbColor = ContextCompat.getColor(context, R.color.cinema_tmdb)
            spanned.setSpan(
                android.text.style.ForegroundColorSpan(tmdbColor),
                0,
                tmdbPart.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            binding.tvDetailAboutIds.text = spanned
        }

        val anyFact = listOf(
            binding.tvDetailAboutQuality,
            binding.tvDetailAboutProvider,
            binding.tvDetailAboutIds,
        ).any { it.visibility == View.VISIBLE }
        binding.tvDetailAboutFactsLabel.visibility =
            if (anyFact) View.VISIBLE else View.GONE
        binding.llDetailAboutFacts.visibility =
            if (anyFact) View.VISIBLE else View.GONE
        binding.vDetailAboutDivider.visibility =
            if (anyFact) View.VISIBLE else View.GONE
        if (!anyFact) {
            val overview = tvShow.overview?.trim().orEmpty()
            binding.tvDetailAboutOverview.visibility = View.VISIBLE
            binding.tvDetailAboutOverview.text = overview.ifEmpty {
                context.getString(R.string.detail_about_empty)
            }
            binding.tvDetailAboutOverviewLabel.visibility =
                if (overview.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    companion object {
        const val DETAIL_SECTION_SEASONS = "detail_section_seasons"
        const val DETAIL_SECTION_RECOMMENDATIONS = "detail_section_recommendations"
        const val DETAIL_SECTION_TRAILER = "detail_section_trailer"
        const val DETAIL_SECTION_ABOUT = "detail_section_about"
    }

}
