package com.dskja.betterstreamflix.adapters.viewholders

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
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
import com.dskja.betterstreamflix.ui.DetailTab
import com.dskja.betterstreamflix.ui.TmdbLogoGlide
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.databinding.ItemDetailTrailerRowMobileBinding
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

    private val context = itemView.context
    private val database: AppDatabase
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

    private lateinit var tvShow: TvShow
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

    private fun isIptvProvider(): Boolean {
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

    private fun checkProviderAndRun(action: () -> Unit) {
        val providerName = tvShow.providerName
        if (!providerName.isNullOrBlank() && providerName != UserPreferences.currentProvider?.name) {
            Provider.findByName(providerName)?.let {
                UserPreferences.setCurrentProviderForPlayback(it)
            }
        }
        action()
    }

    private fun handleDirectPlay(navController: NavController) {
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

    private fun resolveEpisodeSeason(episode: Episode?): Season? {
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

    private fun preferredOfflineServerNameForEpisode(episode: Episode?): String? {
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

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getInstalledSmartTubePackages(): List<String> {
        val installed = mutableListOf<String>()
        if (isPackageInstalled("org.smarttube.stable")) installed.add("org.smarttube.stable")
        if (isPackageInstalled("org.smarttube.beta")) installed.add("org.smarttube.beta")
        return installed
    }

    private fun launchSmartTube(packageName: String, trailerUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, trailerUrl.toUri())
        intent.setPackage(packageName)
        context.startActivity(intent)
    }

    private fun showSmartTubeVersionDialog(packages: List<String>, trailerUrl: String, shouldSavePreference: Boolean) {
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

    private fun handleSmartTubeSelection(trailerUrl: String) {
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

    private fun safeLaunchYoutube(intent: Intent) {
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

    private fun handleTrailerClick(trailer: String) {
        val youtubeIntent = Intent(Intent.ACTION_VIEW, trailer.toUri())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val preferredPlayer = prefs.getString("preferred_player", "ask")

        when (preferredPlayer) {
            "smarttube" -> {
                handleSmartTubeSelection(trailer)
            }
            "smarttube_stable" -> {
                launchSmartTube("org.smarttube.stable", trailer)
            }
            "smarttube_beta" -> {
                launchSmartTube("org.smarttube.beta", trailer)
            }
            "youtube" -> {
                safeLaunchYoutube(youtubeIntent)
            }
            else -> {
                val stPackages = getInstalledSmartTubePackages()
                if (stPackages.isNotEmpty()) {
                    (if (ExperimentalMobileDesign.enabled()) MaterialAlertDialogBuilder(context) else AlertDialog.Builder(context))
                        .setTitle(context.getString(R.string.watch_trailer_with))
                        .setItems(arrayOf(context.getString(R.string.youtube), context.getString(R.string.smarttube))) { _, which ->
                            if (which == 0) {
                                safeLaunchYoutube(youtubeIntent)
                            } else {
                                if (stPackages.size > 1) {
                                    showSmartTubeVersionDialog(stPackages, trailer, false)
                                } else {
                                    launchSmartTube(stPackages[0], trailer)
                                }
                            }
                        }
                        .create()
                        .also { com.dskja.betterstreamflix.ui.TrailerPlaybackController.polishChooserDialog(it) }
                } else {
                    safeLaunchYoutube(youtubeIntent)
                }
            }
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

        binding.tvSwiperOverview.visibility = View.GONE
        binding.tvSwiperTvShowLastEpisode.visibility = View.GONE
        binding.tvSwiperQuality.visibility = View.GONE
        binding.tvSwiperReleased.visibility = View.GONE
        binding.tvSwiperRating.visibility = View.GONE
        binding.ivSwiperRatingIcon.visibility = View.GONE
        binding.pbSwiperProgress.visibility = View.GONE

        binding.tvSwiperStatus.apply {
            text = FeaturedHeroController.statusLine(context, tvShow)
                ?: context.getString(R.string.home_swiper_all_episodes)
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        binding.tvSwiperGenres.apply {
            val labels = FeaturedHeroController.genresLine(tvShow)
            text = labels
            visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
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

    private fun displayTvShowMobile(binding: ContentTvShowMobileBinding) {
        binding.ivTvShowPoster.visibility = View.GONE
        Glide.with(binding.ivTvShowPoster).clear(binding.ivTvShowPoster)

        binding.tvTvShowTitle.text = tvShow.title
        // Hero logo bind is owned by DetailHeaderController (after body submit).
        binding.ivTvShowLogo.visibility = View.INVISIBLE
        binding.tvTvShowTitle.visibility = View.VISIBLE

        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(
                binding.tvTvShowTitle,
                binding.ivTvShowLogo,
                binding.btnTvShowWatchNow,
                binding.btnTvShowTrailer,
                binding.btnTvShowDownload,
            )
        }

        binding.tvTvShowRating.visibility = View.GONE
        binding.ivTvShowRatingIcon.visibility = View.GONE
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
                text = tvShow.genres.joinToString(" · ") {
                    it.name.uppercase(Locale.getDefault())
                }
                isVisible = true
                movementMethod = null
                isClickable = true
                contentDescription = context.getString(R.string.genre_section_label)
                setOnClickListener { view ->
                    ExpMotion.hapticTap(view)
                    val genres = tvShow.genres
                    fun openGenre(genre: com.dskja.betterstreamflix.models.Genre) {
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

        binding.tvTvShowOverview.apply {
            text = tvShow.overview
            val more = binding.tvTvShowOverviewMore
            val hasText = !tvShow.overview.isNullOrBlank()
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            var expanded = false
            fun applyExpand(open: Boolean) {
                expanded = open
                maxLines = if (open) Integer.MAX_VALUE else 3
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
                    val overflowing = lineCount > 3 ||
                        (text?.length ?: 0) > 160 ||
                        (layout != null && maxLines == 3 && layout.getEllipsisCount(lineCount.coerceAtLeast(1) - 1) > 0)
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
                        val adapter = bindingAdapter as? AppAdapter
                        if (adapter?.onDetailTabSelectedListener != null) {
                            adapter.onDetailTabSelectedListener?.invoke(
                                com.dskja.betterstreamflix.ui.DetailTab.EPISODES,
                            )
                        } else {
                            val season = tvShow.seasons.firstOrNull()
                            if (season != null) {
                                findNavController().navigate(
                                    TvShowMobileFragmentDirections.actionTvShowToSeason(
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
            isVisible = !tvShow.trailer.isNullOrBlank() || canLookup
            applyExpPress()
            setOnClickListener {
                ExpMotion.hapticTap(it)
                fun play(url: String) {
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                    if (fragment != null) {
                        TrailerPlaybackController.play(fragment, url)
                    } else {
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
                    val first = remote.firstOrNull()?.second?.takeIf { it.isNotBlank() }
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
            if (isIptvProvider()) {
                downloadBtn.isVisible = false
                downloadBtn.setOnClickListener(null)
                downloadBtn.setOnLongClickListener(null)
            } else {
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
            shareBtn.applyExpPress()
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
                            tvShow.overview?.takeIf { it.isNotBlank() }?.let { overview -> append("\n\n").append(overview.take(280)) }
                            tvShow.trailer?.let { trailer -> append("\n").append(trailer) }
                        },
                    )
                }
                context.startActivity(Intent.createChooser(share, context.getString(R.string.detail_share)))
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
                watchedBtn.alpha = if (completed) 1f else 0.55f
            }

            watchedBtn.applyExpPress()
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
            fun applyState(inList: Boolean) {
                setImageDrawable(
                    ContextCompat.getDrawable(
                        context,
                        if (inList) R.drawable.ic_list_added else R.drawable.ic_list_add,
                    )
                )
                val description = context.getString(
                    if (inList) R.string.detail_remove_from_list else R.string.detail_add_to_list,
                )
                contentDescription = description
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, description)
            }

            applyExpPress()
            applyState(tvShow.isFavorite)
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.tvShowDao()
                        val target = !(dao.getById(tvShow.id)?.isFavorite ?: tvShow.isFavorite)
                        val resolved =
                            ArtworkRepair.resolveTvShowForFavorite(context, tvShow, target)
                        dao.upsertFavorite(resolved, target)
                        com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks.onListToggle(
                            add = target,
                            imdbId = tvShow.imdbId,
                            tmdbId = tvShow.tmdbId,
                            isTv = true,
                        )
                        withContext(Dispatchers.Main) {
                            tvShow.poster = resolved.poster
                            tvShow.banner = resolved.banner
                            tvShow.isFavorite = target
                            applyState(target)
                            ExpMotion.softScale(binding.btnTvShowFavorite)
                        }
                    }
                }
            }
        }
    }

    private fun displayTvShowTv(binding: ContentTvShowTvBinding) {
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
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) }
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
            binding.root.findViewById(R.id.btn_tv_show_share),
            binding.btnTvShowFavorite,
        )
    }

    private fun displaySeasonsMobile(binding: ContentTvShowSeasonsMobileBinding) {
        binding.root.tag = DETAIL_SECTION_SEASONS

        val seasons = tvShow.seasons
        if (seasons.isEmpty()) {
            binding.btnTvShowSeasonPicker.visibility = View.GONE
            binding.rvTvShowEpisodes.visibility = View.GONE
            binding.pbTvShowEpisodesLoading.visibility = View.GONE
            binding.tvTvShowEpisodesEmpty.visibility = View.VISIBLE
            return
        }

        val selectedSeason = resolveSelectedSeason(seasons)
        selectedSeasonIdByShow[tvShow.id] = selectedSeason.id

        binding.btnTvShowSeasonPicker.apply {
            visibility = View.VISIBLE
            text = selectedSeason.title
                ?: context.getString(R.string.season_number, selectedSeason.number)
            setOnClickListener {
                ExpMotion.hapticTap(it)
                showSeasonPicker(binding, seasons)
            }
            if (ExperimentalMobileDesign.enabled()) {
                applyExpPress()
            }
        }

        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.popIn(binding.btnTvShowSeasonPicker)
            ExpMotion.staggerFirstFill(binding.rvTvShowEpisodes)
        }

        bindSeasonEpisodes(binding, selectedSeason)
    }

    private fun resolveSelectedSeason(seasons: List<Season>): Season {
        val rememberedId = selectedSeasonIdByShow[tvShow.id]
        if (rememberedId != null) {
            seasons.firstOrNull { it.id == rememberedId }?.let { return it }
        }
        // Prefer the season that contains the next episode to watch, else first non-zero.
        val episodeSeasonId = tvShow.episodeToWatch?.season?.id
        if (episodeSeasonId != null) {
            seasons.firstOrNull { it.id == episodeSeasonId }?.let { return it }
        }
        return seasons.firstOrNull { it.number != 0 } ?: seasons.first()
    }

    private fun showSeasonPicker(
        binding: ContentTvShowSeasonsMobileBinding,
        seasons: List<Season>,
    ) {
        val labels = seasons.map { season ->
            season.title ?: context.getString(R.string.season_number, season.number)
        }.toTypedArray()
        val selectedIndex = seasons.indexOfFirst { it.id == selectedSeasonIdByShow[tvShow.id] }
            .coerceAtLeast(0)
        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(context)
        } else {
            AlertDialog.Builder(context)
        }
        builder
            .setTitle(R.string.tv_show_seasons)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val season = seasons.getOrNull(which) ?: return@setSingleChoiceItems
                selectedSeasonIdByShow[tvShow.id] = season.id
                binding.btnTvShowSeasonPicker.text = labels[which]
                bindSeasonEpisodes(binding, season)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun bindSeasonEpisodes(
        binding: ContentTvShowSeasonsMobileBinding,
        season: Season,
    ) {
        val episodes = season.episodes.sortedBy { it.number }
        val adapter = (binding.rvTvShowEpisodes.adapter as? AppAdapter) ?: AppAdapter().also {
            binding.rvTvShowEpisodes.adapter = it
            if (binding.rvTvShowEpisodes.itemDecorationCount == 0) {
                binding.rvTvShowEpisodes.addItemDecoration(SpacingItemDecoration(8.dp(context)))
            }
        }

        when {
            episodes.isNotEmpty() -> {
                loadingSeasonIds.remove(season.id)
                binding.pbTvShowEpisodesLoading.visibility = View.GONE
                binding.tvTvShowEpisodesEmpty.visibility = View.GONE
                binding.rvTvShowEpisodes.visibility = View.VISIBLE
                adapter.submitList(
                    episodes.onEach { episode ->
                        val show = episode.tvShow ?: tvShow
                        if (show.contentRating.isNullOrBlank()) {
                            show.contentRating = tvShow.contentRating
                        }
                        episode.tvShow = show
                        episode.season = episode.season ?: season
                        episode.itemType = AppAdapter.Type.EPISODE_DETAIL_MOBILE_ITEM
                    },
                )
                binding.rvTvShowEpisodes.post {
                    binding.rvTvShowEpisodes.requestLayout()
                    (binding.rvTvShowEpisodes.parent as? View)?.requestLayout()
                }
            }
            loadingSeasonIds.contains(season.id) -> {
                binding.rvTvShowEpisodes.visibility = View.GONE
                binding.tvTvShowEpisodesEmpty.visibility = View.GONE
                binding.pbTvShowEpisodesLoading.visibility = View.VISIBLE
                adapter.submitList(emptyList())
            }
            else -> {
                binding.rvTvShowEpisodes.visibility = View.GONE
                binding.tvTvShowEpisodesEmpty.visibility = View.GONE
                binding.pbTvShowEpisodesLoading.visibility = View.VISIBLE
                adapter.submitList(emptyList())
                requestSeasonEpisodes(binding, season)
            }
        }
    }

    private fun requestSeasonEpisodes(
        binding: ContentTvShowSeasonsMobileBinding,
        season: Season,
    ) {
        if (!loadingSeasonIds.add(season.id)) return
        val fragment = context.toActivity()?.getCurrentFragment() as? TvShowMobileFragment
        if (fragment != null) {
            fragment.loadSeasonEpisodes(season)
            // When episodes land via ViewModel flow, the section rebinds.
            // Clear loading flag after a short window so a failed load can show empty.
            itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                kotlinx.coroutines.delay(12_000)
                loadingSeasonIds.remove(season.id)
                if (selectedSeasonIdByShow[tvShow.id] == season.id &&
                    season.episodes.isEmpty() &&
                    binding.pbTvShowEpisodesLoading.isVisible
                ) {
                    binding.pbTvShowEpisodesLoading.visibility = View.GONE
                    binding.tvTvShowEpisodesEmpty.visibility = View.VISIBLE
                }
            }
            return
        }

        // Fallback: load directly if fragment isn't available.
        itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val provider = UserPreferences.currentProvider
                        ?: return@runCatching emptyList<Episode>()
                    val episodes = provider.getEpisodesBySeason(season.id)
                    val episodeMap = episodes.associateBy { it.id }
                    episodes.map { it.id }.chunked(400).forEach { chunk ->
                        database.episodeDao().getByIds(chunk).forEach { db ->
                            episodeMap[db.id]?.merge(db)
                        }
                    }
                    episodes.forEach {
                        it.tvShow = tvShow
                        it.season = season
                    }
                    database.episodeDao().insertAll(episodes)
                    episodes
                }.getOrDefault(emptyList())
            }
            loadingSeasonIds.remove(season.id)
            season.episodes = loaded
            tvShow.seasons.firstOrNull { it.id == season.id }?.episodes = loaded
            if (selectedSeasonIdByShow[tvShow.id] == season.id) {
                bindSeasonEpisodes(binding, season)
            }
        }
    }

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
        binding.tabDetailEpisodes.visibility = View.VISIBLE
        val adapter = bindingAdapter as? AppAdapter
        val selected = adapter?.selectedDetailTab ?: DetailTab.EPISODES
        fun select(active: android.view.View) {
            listOf(
                binding.tabDetailEpisodes,
                binding.tabDetailSimilar,
                binding.tabDetailTrailer,
                binding.tabDetailAbout,
            ).forEach { tab ->
                val on = tab === active
                tab.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0x8AFFFFFF.toInt())
                tab.setBackgroundResource(if (on) R.drawable.bg_detail_tab_underline else 0)
            }
        }
        val active = when (selected) {
            com.dskja.betterstreamflix.ui.DetailTab.EPISODES -> binding.tabDetailEpisodes
            com.dskja.betterstreamflix.ui.DetailTab.SIMILAR -> binding.tabDetailSimilar
            com.dskja.betterstreamflix.ui.DetailTab.TRAILER -> binding.tabDetailTrailer
            com.dskja.betterstreamflix.ui.DetailTab.ABOUT -> binding.tabDetailAbout
        }
        select(active)

        binding.tabDetailEpisodes.setOnClickListener {
            ExpMotion.hapticTap(it)
            select(binding.tabDetailEpisodes)
            (bindingAdapter as? AppAdapter)?.onDetailTabSelectedListener?.invoke(DetailTab.EPISODES)
        }
        binding.tabDetailSimilar.setOnClickListener {
            ExpMotion.hapticTap(it)
            select(binding.tabDetailSimilar)
            adapter?.onDetailTabSelectedListener?.invoke(com.dskja.betterstreamflix.ui.DetailTab.SIMILAR)
        }
        binding.tabDetailTrailer.setOnClickListener {
            ExpMotion.hapticTap(it)
            select(binding.tabDetailTrailer)
            adapter?.onDetailTabSelectedListener?.invoke(com.dskja.betterstreamflix.ui.DetailTab.TRAILER)
        }
        binding.tabDetailAbout.setOnClickListener {
            ExpMotion.hapticTap(it)
            select(binding.tabDetailAbout)
            adapter?.onDetailTabSelectedListener?.invoke(com.dskja.betterstreamflix.ui.DetailTab.ABOUT)
        }
    }

    private fun displayTrailerMobile(binding: ContentDetailTrailerMobileBinding) {
        binding.root.tag = DETAIL_SECTION_TRAILER
        binding.llDetailTrailerList.removeAllViews()
        binding.llDetailTrailerRow.visibility = View.GONE
        binding.tvDetailTrailerEmpty.visibility = View.GONE

        fun bindRows(trailers: List<Triple<String, String, String>>) {
            binding.llDetailTrailerList.removeAllViews()
            if (trailers.isEmpty()) {
                binding.tvDetailTrailerEmpty.visibility = View.VISIBLE
                return
            }
            binding.tvDetailTrailerEmpty.visibility = View.GONE
            val inflater = LayoutInflater.from(context)
            trailers.take(5).forEach { (title, url, type) ->
                val row = ItemDetailTrailerRowMobileBinding.inflate(
                    inflater,
                    binding.llDetailTrailerList,
                    false,
                )
                row.tvDetailTrailerTitle.text = title
                row.tvDetailTrailerMeta.text = type
                row.tvDetailTrailerDesc.visibility = View.GONE
                val ytId = TrailerPlaybackController.youtubeVideoId(url)
                if (ytId != null) {
                    Glide.with(row.ivDetailTrailerThumb)
                        .load("https://img.youtube.com/vi/$ytId/hqdefault.jpg")
                        .centerCrop()
                        .into(row.ivDetailTrailerThumb)
                } else {
                    row.ivDetailTrailerThumb.setImageDrawable(null)
                }
                val play = View.OnClickListener {
                    ExpMotion.hapticTap(it)
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                    if (fragment != null) {
                        TrailerPlaybackController.play(fragment, url)
                    } else {
                        handleTrailerClick(url)
                    }
                }
                row.root.setOnClickListener(play)
                row.ivDetailTrailerPlay.setOnClickListener(play)
                binding.llDetailTrailerList.addView(row.root)
            }
        }

        val seed = tvShow.trailer?.takeIf { it.isNotBlank() }?.let { url ->
            listOf(
                Triple(
                    "${tvShow.title} ${context.getString(R.string.tv_show_trailer)}",
                    url,
                    context.getString(R.string.tv_show_trailer),
                ),
            )
        }.orEmpty()
        if (seed.isNotEmpty()) bindRows(seed)

        itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val remote = withContext(Dispatchers.IO) {
                TmdbUtils.listYoutubeTrailers(
                    tmdbId = tvShow.tmdbId,
                    isTv = true,
                    title = tvShow.title,
                    year = tvShow.released?.format("yyyy")?.toIntOrNull(),
                    imdbId = tvShow.imdbId,
                )
            }
            val trailers = (seed + remote).distinctBy { it.second }
            bindRows(trailers)
        }
    }


    private fun displayTrailerTv(binding: ContentDetailTrailerTvBinding) {
        binding.root.tag = DETAIL_SECTION_TRAILER
        if (ExperimentalMobileDesign.enabled()) {
            binding.tvDetailTrailerLabel.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvDetailTrailerLabel,
                    androidx.appcompat.R.attr.colorPrimary,
                ),
            )
            binding.root.findViewById<View>(R.id.v_detail_trailer_rule)?.visibility = View.VISIBLE
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(
                    binding.tvDetailTrailerLabel,
                    binding.root.findViewById(R.id.v_detail_trailer_rule),
                )
                ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_detail_trailer_rule))
            }
        }

        fun bindRows(trailers: List<Triple<String, String, String>>) {
            binding.root.visibility = if (trailers.isEmpty()) View.GONE else View.VISIBLE
            binding.hgvDetailTrailers.apply {
                setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
                adapter = AppAdapter().apply {
                    submitList(
                        trailers.take(5).map { (title, url, type) ->
                            Trailer(title = title, url = url, type = type).also {
                                it.itemType = AppAdapter.Type.TRAILER_TV_ITEM
                            }
                        },
                    )
                }
                setItemSpacing(24)
            }
            if (ExperimentalMobileDesign.enabled() && trailers.isNotEmpty()) {
                ExpMotion.staggerFirstFill(binding.hgvDetailTrailers)
            }
        }

        val seed = tvShow.trailer?.takeIf { it.isNotBlank() }?.let { url ->
            listOf(
                Triple(
                    "${tvShow.title} ${context.getString(R.string.tv_show_trailer)}",
                    url,
                    context.getString(R.string.tv_show_trailer),
                ),
            )
        }.orEmpty()
        if (seed.isNotEmpty()) bindRows(seed) else binding.root.visibility = View.GONE

        itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val remote = withContext(Dispatchers.IO) {
                TmdbUtils.listYoutubeTrailers(
                    tmdbId = tvShow.tmdbId,
                    isTv = true,
                    title = tvShow.title,
                    year = tvShow.released?.format("yyyy")?.toIntOrNull(),
                    imdbId = tvShow.imdbId,
                )
            }
            val trailers = (seed + remote).distinctBy { it.second }
            if (trailers.isNotEmpty() && tvShow.trailer.isNullOrBlank()) {
                tvShow.trailer = trailers.first().second
            }
            bindRows(trailers)
        }
    }

    private fun displayAboutMobile(binding: ContentDetailAboutMobileBinding) {
        binding.root.tag = DETAIL_SECTION_ABOUT
        val overview = tvShow.overview.orEmpty()
        binding.tvDetailAboutOverview.text = overview
        binding.tvDetailAboutOverview.visibility =
            if (overview.isBlank()) View.GONE else View.VISIBLE
        binding.tvDetailAboutOverviewLabel.visibility =
            if (overview.isBlank()) View.GONE else View.VISIBLE

        val featuringNames = tvShow.cast
            .mapNotNull { it.name.takeIf { name -> name.isNotBlank() } }
            .take(5)
            .joinToString(", ")
        binding.tvDetailAboutFeaturing.text = featuringNames
        val featuringVisible = featuringNames.isNotBlank()
        binding.tvDetailAboutFeaturing.visibility =
            if (featuringVisible) View.VISIBLE else View.GONE
        binding.tvDetailAboutFeaturingLabel.visibility =
            if (featuringVisible) View.VISIBLE else View.GONE

        val directorNames = tvShow.directors
            .mapNotNull { it.name.takeIf { name -> name.isNotBlank() } }
            .joinToString(", ")
        binding.tvDetailAboutDirectors.text = directorNames
        val directorsVisible = directorNames.isNotBlank()
        binding.tvDetailAboutDirectors.visibility =
            if (directorsVisible) View.VISIBLE else View.GONE
        binding.tvDetailAboutDirectorsLabel.visibility =
            if (directorsVisible) View.VISIBLE else View.GONE

        val castNames = tvShow.cast
            .mapNotNull { it.name.takeIf { name -> name.isNotBlank() } }
            .joinToString(", ")
        binding.tvDetailAboutCast.text = castNames
        val castVisible = castNames.isNotBlank()
        binding.tvDetailAboutCast.visibility =
            if (castVisible) View.VISIBLE else View.GONE
        binding.tvDetailAboutCastLabel.visibility =
            if (castVisible) View.VISIBLE else View.GONE
    }

    companion object {
        const val DETAIL_SECTION_SEASONS = "detail_section_seasons"
        const val DETAIL_SECTION_RECOMMENDATIONS = "detail_section_recommendations"
        const val DETAIL_SECTION_TRAILER = "detail_section_trailer"
        const val DETAIL_SECTION_ABOUT = "detail_section_about"

        private val selectedSeasonIdByShow = mutableMapOf<String, String>()
        private val loadingSeasonIds = mutableSetOf<String>()
    }

}
