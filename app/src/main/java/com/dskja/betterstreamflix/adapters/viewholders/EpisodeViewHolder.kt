package com.dskja.betterstreamflix.adapters.viewholders

import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.TvFocusZoom
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.databinding.ItemEpisodeContinueWatchingMobileBinding
import com.dskja.betterstreamflix.databinding.ItemEpisodeContinueWatchingTvBinding
import com.dskja.betterstreamflix.databinding.ItemEpisodeDetailMobileBinding
import com.dskja.betterstreamflix.databinding.ItemEpisodeMobileBinding
import com.dskja.betterstreamflix.databinding.ItemEpisodeTvBinding
import com.dskja.betterstreamflix.download.DownloadContentKey
import com.dskja.betterstreamflix.download.OfflineBadgeStore
import com.dskja.betterstreamflix.download.ui.DownloadOptionsController
import com.dskja.betterstreamflix.fragments.home.HomeMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.home.HomeTvFragment
import com.dskja.betterstreamflix.fragments.home.HomeTvFragmentDirections
import com.dskja.betterstreamflix.fragments.season.SeasonMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.season.SeasonTvFragmentDirections
import com.dskja.betterstreamflix.fragments.tv_show.TvShowMobileFragment
import com.dskja.betterstreamflix.fragments.tv_show.TvShowMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.ui.ShowOptionsMobileDialog
import com.dskja.betterstreamflix.ui.ShowOptionsTvDialog
import com.dskja.betterstreamflix.utils.EpisodeManager
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.getCurrentFragment
import com.dskja.betterstreamflix.utils.loadTvShowCardArtwork
import com.dskja.betterstreamflix.utils.toActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class EpisodeViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    init {
        if (ExperimentalMobileDesign.enabled() && (
                _binding is ItemEpisodeMobileBinding ||
                    _binding is ItemEpisodeDetailMobileBinding ||
                    _binding is ItemEpisodeContinueWatchingMobileBinding ||
                    _binding is ItemEpisodeTvBinding ||
                    _binding is ItemEpisodeContinueWatchingTvBinding
                )
        ) {
            itemView.applyExpPress()
        }
    }

    private lateinit var episode: Episode
    private var downloadRibbonJob: Job? = null

    fun bind(episode: Episode) {
        this.episode = episode

        when (_binding) {
            is ItemEpisodeMobileBinding -> displayMobileItem(_binding)
            is ItemEpisodeDetailMobileBinding -> displayDetailMobileItem(_binding)
            is ItemEpisodeTvBinding -> displayTvItem(_binding)
            is ItemEpisodeContinueWatchingMobileBinding -> displayContinueWatchingMobileItem(_binding)
            is ItemEpisodeContinueWatchingTvBinding -> displayContinueWatchingTvItem(_binding)
        }
    }

    private fun displayMobileItem(binding: ItemEpisodeMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                findNavController().navigate(
                    SeasonMobileFragmentDirections.actionSeasonToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                        preferredServerName = preferredOfflineServerName(),
                    )
                )
            }
            setOnLongClickListener {
                ExpMotion.hapticTap(it)
                ShowOptionsMobileDialog(context, episode)
                    .show()
                true
            }
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
            if (ExperimentalMobileDesign.enabled()) {
                ExpMotion.kenBurns(this)
            }
        }
        binding.ivEpisodeWatchedRibbon.let { ribbon ->
            val wasVisible = ribbon.visibility == View.VISIBLE
            ribbon.visibility = if (episode.isWatched) View.VISIBLE else View.GONE
            if (ExperimentalMobileDesign.enabled() && episode.isWatched) {
                ribbon.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
                val pad = (4 * context.resources.displayMetrics.density).toInt()
                ribbon.setPadding(pad, pad, pad, pad)
                if (!wasVisible) ExpMotion.popIn(ribbon)
            } else if (!episode.isWatched) {
                ribbon.background = null
            }
        }
        bindDownloadRibbon(binding.ivEpisodeDownloadRibbon)

        bindEpisodeProgress(binding.pbEpisodeProgress)
        // Remaining % pill is Continue Watching only — hide if recycled from a CW-styled bind.
        binding.root.findViewById<View>(R.id.tv_episode_remaining)?.visibility = View.GONE

        binding.tvEpisodeInfo.text = context.getString(
            R.string.episode_number,
            episode.number
        )
        if (ExperimentalMobileDesign.enabled()) {
            binding.tvEpisodeInfo.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
        }

        binding.tvEpisodeTitle.text = episode.title ?: context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeReleased.apply {
            text = episode.released?.let { " • ${it.format("yyyy-MM-dd")}" }
            val show = !text.isNullOrEmpty()
            val wasVisible = visibility == View.VISIBLE
            visibility = if (show) View.VISIBLE else View.GONE
            if (ExperimentalMobileDesign.enabled() && show) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }
        binding.tvEpisodeOverview.apply {
            text = episode.overview ?: ""
            if (ExperimentalMobileDesign.enabled() && !episode.overview.isNullOrBlank()) {
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                var expanded = false
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    expanded = !expanded
                    maxLines = if (expanded) Integer.MAX_VALUE else 3
                    if (expanded) ExpMotion.revealHeader(this)
                }
            }
        }
            if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            binding.root.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            ExpMotion.revealHeader(binding.tvEpisodeInfo, binding.tvEpisodeTitle)
        }
    }

    private fun displayDetailMobileItem(binding: ItemEpisodeDetailMobileBinding) {
        binding.root.setOnClickListener {
            ExpMotion.hapticTap(it)
            navigateToPlayer()
        }
        binding.root.setOnLongClickListener {
            ExpMotion.hapticTap(it)
            ShowOptionsMobileDialog(context, episode).show()
            true
        }

        // No permanent "up next" border — blue outline looked like a stuck focus ring.
        binding.root.setBackgroundResource(0)
        binding.root.setPadding(0, binding.root.paddingTop, 0, binding.root.paddingBottom)

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }

        binding.ivEpisodeWatchedRibbon.visibility =
            if (episode.isWatched) View.VISIBLE else View.GONE
        bindDownloadRibbon(binding.ivEpisodeDownloadRibbon)
        bindEpisodeProgress(binding.pbEpisodeProgress)

        val title = episode.title ?: context.getString(R.string.episode_number, episode.number)
        binding.tvEpisodeTitle.text = "${episode.number}. $title"
        binding.tvEpisodeTitle.setTextColor(
            ContextCompat.getColor(context, R.color.cinema_text),
        )

        binding.tvEpisodeMeta.text = buildDetailMetaLine()
        binding.tvEpisodeMeta.visibility =
            if (binding.tvEpisodeMeta.text.isNullOrBlank()) View.GONE else View.VISIBLE

        val ageBadge = contentRatingBadge(episode.tvShow?.contentRating)
        binding.tvEpisodeAgeRating.apply {
            text = ageBadge
            visibility = if (ageBadge.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        binding.tvEpisodeOverview.apply {
            text = episode.overview.orEmpty()
            visibility = if (episode.overview.isNullOrBlank()) View.GONE else View.VISIBLE
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            var expanded = false
            setOnClickListener {
                ExpMotion.hapticTap(it)
                expanded = !expanded
                maxLines = if (expanded) Integer.MAX_VALUE else 3
            }
        }

        binding.btnEpisodeDownload.apply {
            val iptv = UserPreferences.currentProvider is com.dskja.betterstreamflix.providers.IptvProvider ||
                (episode.tvShow?.providerName?.let { Provider.findByName(it) }
                    is com.dskja.betterstreamflix.providers.IptvProvider)
            if (iptv) {
                visibility = View.GONE
                setOnClickListener(null)
            } else {
                visibility = View.VISIBLE
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment ?: return@setOnClickListener
                    DownloadOptionsController.enqueueEpisode(fragment, episode)
                }
            }
        }
    }

    private fun buildDetailMetaLine(): String {
        val runtimeMinutes = episode.watchHistory?.durationMillis
            ?.takeIf { it > 0 }
            ?.let { (it / 60_000L).toInt().coerceAtLeast(1) }
            ?: episode.tvShow?.runtime
        val runtime = runtimeMinutes?.takeIf { it > 0 }?.let { minutes ->
            val hours = minutes / 60
            val rem = minutes % 60
            when {
                hours > 0 && rem == 0 -> "${hours}h"
                hours > 0 -> "${hours}h ${rem}m"
                else -> "${rem}m"
            }
        }
        val date = episode.released?.format("MMM d, yyyy")
        return listOfNotNull(runtime, date).joinToString("  ")
    }

    private fun contentRatingBadge(raw: String?): String? {
        val cert = raw?.trim().orEmpty()
        if (cert.isEmpty()) return null
        val digits = cert.filter { it.isDigit() }
        return when {
            digits.isNotEmpty() -> digits.take(2)
            else -> cert.take(3).uppercase(Locale.getDefault())
        }
    }

    private fun navigateToPlayer() {
        val subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.player_subtitle_tv_show,
                season.number,
                episode.number,
                episode.title ?: context.getString(R.string.episode_number, episode.number),
            )
        } ?: context.getString(
            R.string.player_subtitle_tv_show_episode_only,
            episode.number,
            episode.title ?: context.getString(R.string.episode_number, episode.number),
        )
        val videoType = Video.Type.Episode(
            id = episode.id,
            number = episode.number,
            title = episode.title,
            poster = episode.poster,
            overview = episode.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = episode.tvShow?.id ?: "",
                title = episode.tvShow?.title ?: "",
                poster = episode.tvShow?.poster,
                banner = episode.tvShow?.banner,
                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                imdbId = episode.tvShow?.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = episode.season?.number ?: 0,
                title = episode.season?.title,
            ),
        )
        val preferredServer = preferredOfflineServerName()
        val nav = itemView.findNavController()
        when (context.toActivity()?.getCurrentFragment()) {
            is TvShowMobileFragment -> nav.navigate(
                TvShowMobileFragmentDirections.actionTvShowToPlayer(
                    id = episode.id,
                    title = episode.tvShow?.title ?: "",
                    subtitle = subtitle,
                    videoType = videoType,
                    preferredServerName = preferredServer,
                ),
            )
            else -> nav.navigate(
                SeasonMobileFragmentDirections.actionSeasonToPlayer(
                    id = episode.id,
                    title = episode.tvShow?.title ?: "",
                    subtitle = subtitle,
                    videoType = videoType,
                    preferredServerName = preferredServer,
                ),
            )
        }
    }

    private fun displayTvItem(binding: ItemEpisodeTvBinding) {
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                findNavController().navigate(
                    SeasonTvFragmentDirections.actionSeasonToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                        preferredServerName = preferredOfflineServerName(),
                    )
                )
            }
            setOnLongClickListener {
                ExpMotion.hapticTap(it)
                ShowOptionsTvDialog(context, episode)
                    .show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                TvFocusZoom.apply(itemView, hasFocus)
            }
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            var request = Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .fallback(R.drawable.glide_fallback_cover)
                .centerCrop()
            if (!DeviceCapabilities.shouldReduceHomeEffects(context)) {
                request = request.transition(DrawableTransitionOptions.withCrossFade())
            }
            request.into(this)
        }
        binding.ivEpisodeWatchedRibbon.let { ribbon ->
            val wasVisible = ribbon.visibility == View.VISIBLE
            ribbon.visibility = if (episode.isWatched) View.VISIBLE else View.GONE
if (!episode.isWatched) {
                ribbon.background = null
            }
        }
        bindDownloadRibbon(binding.ivEpisodeDownloadRibbon)

        bindEpisodeProgress(binding.pbEpisodeProgress)

        binding.tvEpisodeInfo.text = context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeTitle.apply {
            text = episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        binding.tvEpisodeReleased.apply {
            val meta = buildDetailMetaLine().ifBlank {
                episode.released?.format("EEEE - MMMM dd, yyyy").orEmpty()
            }
            text = meta
            val show = meta.isNotBlank()
            visibility = if (show) View.VISIBLE else View.GONE
        }
        binding.tvEpisodeOverview.apply {
            text = episode.overview ?: ""
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = if (episode.overview.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun checkProviderAndRun(action: () -> Unit) {
        val providerName = episode.tvShow?.providerName
        if (!providerName.isNullOrBlank() && providerName != UserPreferences.currentProvider?.name) {
            Provider.findByName(providerName)?.let {
                UserPreferences.setCurrentProviderForPlayback(it)
            }
        }
        action()
    }

    /** Leanback: confirm before jumping straight into the player from Continue Watching. */
    private fun openContinueWatchingEpisode() {
        val play = {
            val subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                context.getString(
                    R.string.player_subtitle_tv_show,
                    season.number,
                    episode.number,
                    episode.title ?: context.getString(
                        R.string.episode_number,
                        episode.number,
                    ),
                )
            } ?: context.getString(
                R.string.player_subtitle_tv_show_episode_only,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number,
                ),
            )
            itemView.findNavController().navigate(
                R.id.action_global_player,
                android.os.Bundle().apply {
                    putString("id", episode.id)
                    putString("title", episode.tvShow?.title ?: "")
                    putString("subtitle", subtitle)
                    putSerializable(
                        "videoType",
                        Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                    putString("preferredServerName", preferredOfflineServerName())
                },
            )
        }
        val details = {
            episode.tvShow?.let { tvShow ->
                itemView.findNavController().navigate(
                    HomeTvFragmentDirections.actionHomeToTvShow(
                        id = tvShow.id,
                        poster = tvShow.poster,
                        banner = tvShow.banner,
                    ),
                )
            }
        }
        val leanback = DeviceCapabilities.isLeanbackDevice(context) ||
            DeviceCapabilities.isAmazonFireTv(context)
        if (!leanback) {
            play()
            return
        }
        val items = arrayOf(
            context.getString(R.string.home_swiper_watch_now),
            context.getString(R.string.continue_watching_go_to_details),
            context.getString(R.string.option_cancel),
        )
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.continue_watching_confirm_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> play()
                    1 -> details()
                }
            }
            .show()
    }

    private fun displayContinueWatchingMobileItem(binding: ItemEpisodeContinueWatchingMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                // Go straight to player (parity with continue-watching movies) — no detail detour.
                checkProviderAndRun {
                    val subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                        context.getString(
                            R.string.player_subtitle_tv_show,
                            season.number,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        )
                    } ?: context.getString(
                        R.string.player_subtitle_tv_show_episode_only,
                        episode.number,
                        episode.title ?: context.getString(
                            R.string.episode_number,
                            episode.number
                        )
                    )
                    findNavController().navigate(
                        R.id.action_global_player,
                        android.os.Bundle().apply {
                            putString("id", episode.id)
                            putString("title", episode.tvShow?.title ?: "")
                            putString("subtitle", subtitle)
                            putSerializable(
                                "videoType",
                                Video.Type.Episode(
                                    id = episode.id,
                                    number = episode.number,
                                    title = episode.title,
                                    poster = episode.poster,
                                    overview = episode.overview,
                                    tvShow = Video.Type.Episode.TvShow(
                                        id = episode.tvShow?.id ?: "",
                                        title = episode.tvShow?.title ?: "",
                                        poster = episode.tvShow?.poster,
                                        banner = episode.tvShow?.banner,
                                        releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                        imdbId = episode.tvShow?.imdbId,
                                    ),
                                    season = Video.Type.Episode.Season(
                                        number = episode.season?.number ?: 0,
                                        title = episode.season?.title,
                                    ),
                                ),
                            )
                            preferredOfflineServerName()?.let {
                                putString("preferredServerName", it)
                            }
                        },
                    )
                }
            }
            setOnLongClickListener {
                ExpMotion.hapticTap(it)
                ShowOptionsMobileDialog(context, episode)
                    .show()
                true
            }
        }

        binding.ivEpisodeTvShowPoster.apply {
            clipToOutline = true
            loadContinueWatchingArtwork()
            if (ExperimentalMobileDesign.enabled()) {
                ExpMotion.kenBurns(this)
            }
        }

        bindEpisodeProgress(binding.pbEpisodeProgress)
        bindEpisodeRemainingPill(binding.root)

        binding.tvEpisodeTvShowTitle.text = episode.tvShow?.title ?: ""
        com.dskja.betterstreamflix.logo.TitleLogoSurface.bindCachedOnly(
            imageView = binding.ivEpisodeTvShowLogo,
            titleView = null,
            logoUrl = episode.tvShow?.logo,
            title = episode.tvShow?.title.orEmpty(),
            hideUntilReady = true,
        )

        binding.tvEpisodeInfo.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.episode_item_info,
                season.number,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number
                )
            )
        } ?: context.getString(
            R.string.episode_item_info_episode_only,
            episode.number,
            episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
        )
        if (ExperimentalMobileDesign.enabled()) {
            binding.root.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            binding.tvEpisodeInfo.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(binding.tvEpisodeTvShowTitle, binding.tvEpisodeInfo)
                ExpMotion.popIn(binding.root)
            }
        }
    }

    private fun displayContinueWatchingTvItem(binding: ItemEpisodeContinueWatchingTvBinding) {
        binding.root.apply {
            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
                applyExpPress()
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    openContinueWatchingEpisode()
                }
            }
            setOnLongClickListener {
                ExpMotion.hapticTap(it)
                ShowOptionsTvDialog(context, episode)
                    .show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                TvFocusZoom.apply(itemView, hasFocus)

                when (val fragment = context.toActivity()?.getCurrentFragment()) {
                    is HomeTvFragment -> {
                        if (hasFocus) {
                            fragment.pinBackground(episode.tvShow?.banner)
                        } else {
                            fragment.releasePinnedBackground()
                        }
                    }
                }
            }
        }

        binding.ivEpisodeTvShowPoster.apply {
            clipToOutline = true
            loadContinueWatchingArtwork(withFallback = true)
            if (ExperimentalMobileDesign.enabled()) {
                ExpMotion.kenBurns(this)
            }
        }

        bindEpisodeProgress(binding.pbEpisodeProgress)
        bindEpisodeRemainingPill(binding.root)

        binding.tvEpisodeTvShowTitle.text = episode.tvShow?.title ?: ""
        com.dskja.betterstreamflix.logo.TitleLogoSurface.bindCachedOnly(
            imageView = binding.ivEpisodeTvShowLogo,
            titleView = null,
            logoUrl = episode.tvShow?.logo,
            title = episode.tvShow?.title.orEmpty(),
            hideUntilReady = true,
        )

        binding.tvEpisodeInfo.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.episode_item_info,
                season.number,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number
                )
            )
        } ?: context.getString(
            R.string.episode_item_info_episode_only,
            episode.number,
            episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
        )

        if (ExperimentalMobileDesign.enabled()) {
            val onSurface = com.google.android.material.color.MaterialColors.getColor(
                binding.tvEpisodeTvShowTitle, com.google.android.material.R.attr.colorOnSurface,
            )
            val onVariant = com.google.android.material.color.MaterialColors.getColor(
                binding.tvEpisodeInfo, com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
            binding.tvEpisodeTvShowTitle.setTextColor(onSurface)
            binding.tvEpisodeInfo.setTextColor(onVariant)
            binding.tvEpisodeInfo.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
            val primary = com.google.android.material.color.MaterialColors.getColor(
                binding.pbEpisodeProgress, androidx.appcompat.R.attr.colorPrimary,
            )
            val track = com.google.android.material.color.MaterialColors.getColor(
                binding.pbEpisodeProgress, com.google.android.material.R.attr.colorSurfaceVariant,
            )
            binding.pbEpisodeProgress.progressTintList =
                android.content.res.ColorStateList.valueOf(primary)
            binding.pbEpisodeProgress.progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(track)
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(binding.tvEpisodeTvShowTitle, binding.tvEpisodeInfo)
                ExpMotion.popIn(binding.root)
            }
        }
    }

    private fun episodeDownloadContentKey(): String? {
        val providerName = episode.tvShow?.providerName
            ?.takeIf { it.isNotBlank() }
            ?: UserPreferences.currentProvider?.name
            ?: return null
        val tvShowId = episode.tvShow?.id ?: return null
        val seasonNumber = episode.season?.number ?: 1
        return DownloadContentKey.episode(
            providerName = providerName,
            tvShowId = tvShowId,
            seasonNumber = seasonNumber,
            episodeNumber = episode.number,
            episodeId = episode.id,
        )
    }

    private fun preferredOfflineServerName(): String? {
        val contentKey = episodeDownloadContentKey() ?: return null
        return if (OfflineBadgeStore.isCompleted(context, contentKey)) {
            com.dskja.betterstreamflix.fragments.player.PlayerViewModel.OFFLINE_SERVER_NAME
        } else {
            null
        }
    }

    private fun bindDownloadRibbon(downloadRibbon: View) {
        val boundEpisodeId = episode.id
        val contentKey = episodeDownloadContentKey()
        val show = contentKey != null && OfflineBadgeStore.isCompleted(context, contentKey)
        val wasVisible = downloadRibbon.visibility == View.VISIBLE
        downloadRibbon.visibility = if (show) View.VISIBLE else View.GONE
        if (show && ExperimentalMobileDesign.enabled()) {
            downloadRibbon.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
            val pad = (4 * context.resources.displayMetrics.density).toInt()
            downloadRibbon.setPadding(pad, pad, pad, pad)
            if (!wasVisible) ExpMotion.popIn(downloadRibbon)
        } else if (!show) {
            downloadRibbon.background = null
        }

        downloadRibbonJob?.cancel()
        val lifecycleOwner = itemView.findViewTreeLifecycleOwner()
            ?: context.toActivity()
            ?: return
        downloadRibbonJob = lifecycleOwner.lifecycleScope.launch {
            OfflineBadgeStore.completedKeys(context).collect { keys ->
                if (episode.id != boundEpisodeId) return@collect
                val key = episodeDownloadContentKey()
                val visible = key != null && keys.contains(key)
                val was = downloadRibbon.visibility == View.VISIBLE
                downloadRibbon.visibility = if (visible) View.VISIBLE else View.GONE
                if (visible && ExperimentalMobileDesign.enabled()) {
                    downloadRibbon.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
                    val pad = (4 * context.resources.displayMetrics.density).toInt()
                    downloadRibbon.setPadding(pad, pad, pad, pad)
                    if (!was) ExpMotion.popIn(downloadRibbon)
                } else if (!visible) {
                    downloadRibbon.background = null
                }
            }
        }
    }

    private fun ImageView.loadContinueWatchingArtwork(withFallback: Boolean = false) {
        val tvShow = episode.tvShow
        val reduce = DeviceCapabilities.shouldReduceHomeEffects(context)
        if (tvShow == null) {
            var request = Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .apply {
                    if (withFallback) fallback(R.drawable.glide_fallback_cover)
                }
                .centerCrop()
            if (!reduce) {
                request = request.transition(DrawableTransitionOptions.withCrossFade())
            }
            request.into(this)
            return
        }

        loadTvShowCardArtwork(tvShow) {
            error(R.drawable.glide_fallback_cover)
            apply {
                if (withFallback) fallback(R.drawable.glide_fallback_cover)
            }
            centerCrop()
            if (!reduce) {
                transition(DrawableTransitionOptions.withCrossFade())
            } else {
                this
            }
        }
    }

    private fun bindEpisodeRemainingPill(root: View) {
        val remaining = root.findViewById<android.widget.TextView>(R.id.tv_episode_remaining) ?: return
        val watchHistory = episode.watchHistory
        if (watchHistory != null && watchHistory.durationMillis > 0 && ExperimentalMobileDesign.enabled()) {
            val pct = (watchHistory.lastPlaybackPositionMillis * 100 /
                watchHistory.durationMillis.toDouble()).toInt().coerceIn(0, 99)
            remaining.text = context.getString(R.string.continue_watching_percent, pct)
            remaining.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
            val density = context.resources.displayMetrics.density
            remaining.setPadding(
                (8 * density).toInt(),
                (2 * density).toInt(),
                (8 * density).toInt(),
                (2 * density).toInt(),
            )
            val wasVisible = remaining.visibility == View.VISIBLE
            remaining.visibility = View.VISIBLE
            if (!wasVisible) ExpMotion.popIn(remaining)
        } else if (watchHistory != null && watchHistory.durationMillis > 0) {
            val pct = (watchHistory.lastPlaybackPositionMillis * 100 /
                watchHistory.durationMillis.toDouble()).toInt().coerceIn(0, 99)
            remaining.text = context.getString(R.string.continue_watching_percent, pct)
            remaining.visibility = View.VISIBLE
        } else {
            remaining.visibility = View.GONE
        }
    }

    private fun bindEpisodeProgress(bar: android.widget.ProgressBar) {
        val watchHistory = episode.watchHistory
        val target = when {
            watchHistory != null && watchHistory.durationMillis > 0 ->
                (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
            episode.isWatched -> 100
            else -> 0
        }
        val show = (watchHistory != null && watchHistory.durationMillis > 0) || episode.isWatched
        val wasVisible = bar.visibility == View.VISIBLE
        bar.visibility = if (show) View.VISIBLE else View.GONE
        if (show && ExperimentalMobileDesign.enabled() && (!wasVisible || bar.progress != target)) {
            android.animation.ObjectAnimator.ofInt(bar, "progress", 0, target)
                .setDuration(420L)
                .start()
        } else {
            bar.progress = target
        }
    }

}
