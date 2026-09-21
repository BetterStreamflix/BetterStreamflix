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
import androidx.viewbinding.ViewBinding
import com.dskja.betterstreamflix.providers.IptvProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
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
import com.dskja.betterstreamflix.download.ui.DownloadOptionsController
import com.dskja.betterstreamflix.fragments.movie.MovieMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.player.PlayerViewModel
import com.dskja.betterstreamflix.fragments.tv_show.TvShowMobileFragment
import com.dskja.betterstreamflix.fragments.tv_show.TvShowMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
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
            is ContentTvShowSeasonsMobileBinding -> _binding.rvTvShowSeasons
            is ContentTvShowSeasonsTvBinding -> _binding.hgvTvShowSeasons
            is ContentTvShowCastMobileBinding -> _binding.rvTvShowCast
            is ContentTvShowCastTvBinding -> _binding.hgvTvShowCast
            is ContentTvShowRecommendationsMobileBinding -> _binding.rvTvShowRecommendations
            is ContentTvShowRecommendationsTvBinding -> _binding.hgvTvShowRecommendations
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
                UserPreferences.currentProvider = it
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
        val providerName = UserPreferences.currentProvider?.name ?: return null
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
                val animation = if (hasFocus) AnimationUtils.loadAnimation(context, R.anim.zoom_in) else AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                startAnimation(animation)
                animation.fillAfter = true
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
            if (ExperimentalMobileDesign.enabled() && show) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
            if (ExperimentalMobileDesign.enabled() && watchHistory != null) {
                val primary = com.google.android.material.color.MaterialColors.getColor(
                    this, androidx.appcompat.R.attr.colorPrimary,
                )
                progressTintList = android.content.res.ColorStateList.valueOf(primary)
            }
        }
        bindEpisodeBadge(binding.tvTvShowLastEpisode)
        binding.tvTvShowTitle.text = tvShow.title
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.kenBurns(binding.ivTvShowPoster)
            ExpMotion.revealHeader(binding.tvTvShowTitle)
            ExpMotion.popIn(binding.root)
        }
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
                val animation = if (hasFocus) AnimationUtils.loadAnimation(context, R.anim.zoom_in) else AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                startAnimation(animation)
                animation.fillAfter = true
            }
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
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
            if (ExperimentalMobileDesign.enabled() && watchHistory != null) {
                val primary = com.google.android.material.color.MaterialColors.getColor(
                    this, androidx.appcompat.R.attr.colorPrimary,
                )
                progressTintList = android.content.res.ColorStateList.valueOf(primary)
            }
        }
        bindEpisodeBadge(binding.tvTvShowLastEpisode)
        binding.tvTvShowTitle.text = tvShow.title
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.kenBurns(binding.ivTvShowPoster)
            ExpMotion.revealHeader(binding.tvTvShowTitle)
            ExpMotion.popIn(binding.root)
        }
    }

    private fun applyMobileSelection(view: View) {
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
            centerCrop().transition(DrawableTransitionOptions.withCrossFade())
        }
        if (ExperimentalMobileDesign.enabled()) {
            ExpMotion.kenBurns(binding.ivSwiperBackground)
        }
        binding.tvSwiperTitle.text = tvShow.title
        itemView.contentDescription = tvShow.title
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(binding.tvSwiperTitle, binding.tvSwiperOverview)
        }
        bindEpisodeBadge(binding.tvSwiperTvShowLastEpisode)
        
        binding.tvSwiperQuality.apply {
            text = tvShow.quality
            val show = !text.isNullOrEmpty()
            val wasVisible = isVisible
            isVisible = show
            if (show && ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }

        binding.tvSwiperReleased.apply {
            text = tvShow.released?.format("yyyy")
            val show = !text.isNullOrEmpty()
            val wasVisible = isVisible
            isVisible = show
            if (show && ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }

        binding.tvSwiperRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) }
            val show = !text.isNullOrEmpty()
            val wasVisible = isVisible
            isVisible = show
            if (show && ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }
        binding.ivSwiperRatingIcon.isVisible = binding.tvSwiperRating.isVisible

        binding.tvSwiperOverview.text = tvShow.overview
        binding.btnSwiperWatchNow.apply {
            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                applyExpPress()
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                }
            }
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
        binding.ivTvShowPoster.run {
            loadTvShowPoster(
                tvShow,
                configure = {
                    fallback(R.drawable.glide_fallback_cover)
                    transition(DrawableTransitionOptions.withCrossFade())
                },
                onReady = { drawable ->
                    binding.root.findViewById<View>(R.id.v_tv_show_poster_glow)?.let { glow ->
                        ExpAmbientGlow.apply(
                            (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap,
                            glow,
                        )
                    }
                },
            )
            visibility = if (tvShow.poster.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.tvTvShowTitle.text = tvShow.title

        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(
                binding.tvTvShowTitle,
                binding.root.findViewById(R.id.v_tv_show_title_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_tv_show_title_rule))
            ExpMotion.revealHeader(
                binding.btnTvShowWatchNow,
                binding.btnTvShowTrailer,
                binding.root.findViewById(R.id.btn_tv_show_download),
            )
        }

        binding.tvTvShowRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            val show = !text.isNullOrEmpty()
            val wasVisible = isVisible
            isVisible = show
            if (ExperimentalMobileDesign.enabled() && show) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }
        binding.ivTvShowRatingIcon.isVisible = binding.tvTvShowRating.isVisible

        binding.tvTvShowQuality.apply {
            text = tvShow.quality
            val show = !text.isNullOrEmpty()
            val wasVisible = isVisible
            isVisible = show
            if (ExperimentalMobileDesign.enabled() && show) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }

        binding.tvTvShowReleased.apply {
            text = tvShow.released?.format("yyyy")
            val show = !text.isNullOrEmpty()
            val wasVisible = isVisible
            isVisible = show
            if (ExperimentalMobileDesign.enabled() && show) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
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
            val show = !text.isNullOrEmpty()
            val wasVisible = isVisible
            isVisible = show
            if (ExperimentalMobileDesign.enabled() && show) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }

        binding.tvTvShowGenres.apply {
            text = tvShow.genres.joinToString(", ") { it.name }
            val show = tvShow.genres.isNotEmpty()
            val wasVisible = isVisible
            isVisible = show
            if (ExperimentalMobileDesign.enabled() && show) {
                setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
            if (tvShow.genres.isNotEmpty()) {
                if (ExperimentalMobileDesign.enabled()) applyExpPress()
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    val genre = tvShow.genres.first()
                    checkProviderAndRun {
                        if (context.toActivity()?.getCurrentFragment() is TvShowMobileFragment) {
                            findNavController().navigate(
                                TvShowMobileFragmentDirections.actionTvShowToGenre(
                                    id = genre.id,
                                    name = genre.name,
                                )
                            )
                        }
                    }
                }
            } else {
                setOnClickListener(null)
            }
        }

        binding.tvTvShowOverview.apply {
            text = tvShow.overview
            if (ExperimentalMobileDesign.enabled() && !tvShow.overview.isNullOrBlank()) {
                maxLines = 5
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (getTag(R.id.exp_enter_animated_tag) != true) {
                    setTag(R.id.exp_enter_animated_tag, true)
                    ExpMotion.revealHeader(this)
                }
                var expanded = false
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    expanded = !expanded
                    maxLines = if (expanded) Integer.MAX_VALUE else 5
                    if (expanded) ExpMotion.revealHeader(this)
                    animate().alpha(0.82f).setDuration(90L).withEndAction {
                        animate().alpha(1f).setDuration(140L).start()
                    }.start()
                }
            }
        }
        val episodeToWatch = tvShow.episodeToWatch
        val episodeSeason = resolveEpisodeSeason(episodeToWatch)
        binding.btnTvShowWatchNow.apply {
            isVisible = episodeToWatch != null
            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                applyExpPress()
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                if (isIptvProvider()) {
                    handleDirectPlay(findNavController())
                } else {
                    val episode = episodeToWatch ?: return@setOnClickListener
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
                        preferredOfflineServerNameForEpisode(episode)?.let {
                            putString("preferredServerName", it)
                        }
                    }
                    findNavController().navigate(R.id.player, args)
                }
            }
            text = if (isIptvProvider()) context.getString(R.string.movie_watch_now) else context.getString(R.string.tv_show_watch_season_episode, episodeSeason?.number ?: 1, episodeToWatch?.number ?: 1)
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
            val trailer = tvShow.trailer
            val show = trailer != null
            val wasVisible = isVisible
            isVisible = show
            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                applyExpPress()
                if (show && !wasVisible) ExpMotion.popIn(this)
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                if (trailer != null) {
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                    if (fragment != null) {
                        TrailerPlaybackController.play(fragment, trailer)
                    } else {
                        handleTrailerClick(trailer)
                    }
                }
            }
        }

        binding.root.findViewById<android.widget.TextView>(R.id.btn_tv_show_download)?.let { downloadBtn ->
            if (ExperimentalMobileDesign.enabled()) {
                downloadBtn.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                downloadBtn.applyExpPress()
            }
            downloadBtn.setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment ?: return@checkProviderAndRun
                    when {
                        episodeToWatch != null -> DownloadOptionsController.enqueueEpisode(fragment, episodeToWatch)
                        else -> {
                            val season = tvShow.seasons.firstOrNull { it.episodes.isNotEmpty() }
                            if (season != null) {
                                DownloadOptionsController.enqueueSeason(
                                    fragment,
                                    tvShow,
                                    season.number,
                                    season.episodes,
                                )
                            } else {
                                ExpDialogChrome.notify(
                                    context,
                                    R.string.detail_download_season,
                                    R.string.season_download,
                                )
                            }
                        }
                    }
                }
            }
        }

        binding.root.findViewById<android.widget.TextView>(R.id.tv_tv_show_certification)?.apply {
            val cert = tvShow.contentRating
            text = cert
            val show = !cert.isNullOrBlank()
            val wasVisible = visibility == View.VISIBLE
            visibility = if (show) View.VISIBLE else View.GONE
            if (ExperimentalMobileDesign.enabled() && show) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }

        binding.root.findViewById<android.widget.TextView>(R.id.btn_tv_show_share)?.let { shareBtn ->
            if (ExperimentalMobileDesign.enabled()) {
                shareBtn.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                shareBtn.applyExpPress()
            }
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

        binding.btnTvShowFavorite.apply {
            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
                applyExpPress()
                fun tintFor(favorite: Boolean) {
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        if (favorite) {
                            com.google.android.material.color.MaterialColors.getColor(
                                this,
                                androidx.appcompat.R.attr.colorPrimary,
                            )
                        } else {
                            com.google.android.material.color.MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorOnSurfaceVariant,
                            )
                        },
                    )
                }
                tintFor(tvShow.isFavorite)
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    checkProviderAndRun {
                        itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                            val dao = database.tvShowDao()
                            val current = dao.getById(tvShow.id)?.isFavorite ?: false
                            val newValue = !current
                            val resolvedTvShow = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, newValue)

                            dao.upsertFavorite(resolvedTvShow, newValue)

                            withContext(Dispatchers.Main) {
                                tvShow.poster = resolvedTvShow.poster
                                tvShow.banner = resolvedTvShow.banner
                                tvShow.isFavorite = newValue
                                setImageDrawable(
                                    ContextCompat.getDrawable(context, newValue.drawable())
                                )
                                tintFor(newValue)
                                ExpMotion.popIn(binding.btnTvShowFavorite)
                            }
                        }
                    }
                }

                setImageDrawable(
                    ContextCompat.getDrawable(context, tvShow.isFavorite.drawable())
                )
            } else {
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    checkProviderAndRun {
                        itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                            val dao = database.tvShowDao()
                            val current = dao.getById(tvShow.id)?.isFavorite ?: false
                            val newValue = !current
                            val resolvedTvShow = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, newValue)

                            dao.upsertFavorite(resolvedTvShow, newValue)

                            withContext(Dispatchers.Main) {
                                tvShow.poster = resolvedTvShow.poster
                                tvShow.banner = resolvedTvShow.banner
                                tvShow.isFavorite = newValue
                                setImageDrawable(
                                    ContextCompat.getDrawable(context, newValue.drawable())
                                )
                                ExpMotion.popIn(binding.btnTvShowFavorite)
                            }
                        }
                    }
                }

                setImageDrawable(
                    ContextCompat.getDrawable(context, tvShow.isFavorite.drawable())
                )
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

        binding.tvTvShowRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivTvShowRatingIcon.isVisible = binding.tvTvShowRating.isVisible

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
            text = tvShow.genres.joinToString(", ") { it.name }
            isVisible = tvShow.genres.isNotEmpty()
        }

        binding.tvTvShowOverview.text = tvShow.overview
        val episodeToWatch = tvShow.episodeToWatch
        val episodeSeason = resolveEpisodeSeason(episodeToWatch)

        if (ExperimentalMobileDesign.enabled()) {
            val onSurface = com.google.android.material.color.MaterialColors.getColor(
                binding.tvTvShowTitle, com.google.android.material.R.attr.colorOnSurface,
            )
            val onVariant = com.google.android.material.color.MaterialColors.getColor(
                binding.tvTvShowOverview, com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
            binding.tvTvShowTitle.setTextColor(onSurface)
            binding.tvTvShowOverview.setTextColor(onVariant)
            listOf(
                binding.tvTvShowRating,
                binding.tvTvShowQuality,
                binding.tvTvShowReleased,
            ).forEach { meta ->
                if (meta.isVisible) {
                    meta.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                    meta.setTextColor(onSurface)
                }
            }
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(binding.tvTvShowTitle, binding.tvTvShowOverview)
            }
        }

        binding.btnTvShowWatchNow.apply {
            isVisible = episodeToWatch != null
            if (ExperimentalMobileDesign.enabled() && isVisible) {
                setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                applyExpPress()
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                if (isIptvProvider()) {
                    handleDirectPlay(findNavController())
                } else {
                    val episode = episodeToWatch ?: return@setOnClickListener
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
                        preferredOfflineServerNameForEpisode(episode)?.let {
                            putString("preferredServerName", it)
                        }
                    }
                    findNavController().navigate(R.id.player, args)
                }
            }
            text = if (isIptvProvider()) context.getString(R.string.movie_watch_now) else context.getString(R.string.tv_show_watch_season_episode, episodeSeason?.number ?: 1, episodeToWatch?.number ?: 1)
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
            val trailer = tvShow.trailer
            if (ExperimentalMobileDesign.enabled() && trailer != null) {
                setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                applyExpPress()
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                if (trailer != null) {
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                    if (fragment != null) {
                        TrailerPlaybackController.play(fragment, trailer)
                    } else {
                        handleTrailerClick(trailer)
                    }
                }
            }
            isVisible = trailer != null
        }

        binding.btnTvShowFavorite.apply {
            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
                applyExpPress()
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

                        withContext(Dispatchers.Main) {
                            tvShow.poster = resolvedTvShow.poster
                            tvShow.banner = resolvedTvShow.banner
                            tvShow.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                            ExpMotion.popIn(binding.btnTvShowFavorite)
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, tvShow.isFavorite.drawable())
            )
        }
    }

    private fun displaySeasonsMobile(binding: ContentTvShowSeasonsMobileBinding) {
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_tv_show_seasons_label),
                binding.root.findViewById(R.id.v_tv_show_seasons_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_tv_show_seasons_rule))
            ExpMotion.staggerFirstFill(binding.rvTvShowSeasons)
        }
        binding.rvTvShowSeasons.apply {
            adapter = AppAdapter().apply { submitList(tvShow.seasons.onEach { it.itemType = AppAdapter.Type.SEASON_MOBILE_ITEM }) }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
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
        binding.rvTvShowDirectors.text = tvShow.directors.joinToString(", ") { it.name }
    }
    private fun displayDirectorsTv(binding: ContentTvShowDirectorsTvBinding) {
        if (ExperimentalMobileDesign.enabled()) {
            binding.tvTvShowDirectorsLabel.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvTvShowDirectorsLabel,
                    androidx.appcompat.R.attr.colorPrimary,
                ),
            )
            binding.hgvTvShowDirectors.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.hgvTvShowDirectors,
                    com.google.android.material.R.attr.colorOnSurface,
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
            }
        }
        binding.hgvTvShowDirectors.text = tvShow.directors.joinToString(", ") { it.name }
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
        binding.rvTvShowRecommendations.apply {
            adapter = AppAdapter().apply {
                submitList(tvShow.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
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
}
