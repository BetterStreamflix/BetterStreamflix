package com.dskja.betterstreamflix.adapters.viewholders

import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.AccessibilityPreferences
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
import com.dskja.betterstreamflix.ui.EpisodeNotesUi
import com.dskja.betterstreamflix.ui.ShowOptionsMobileDialog
import com.dskja.betterstreamflix.ui.ShowOptionsTvDialog
import com.dskja.betterstreamflix.spoilers.SpoilerShield
import com.dskja.betterstreamflix.spoilers.SpoilerShieldUi
import com.dskja.betterstreamflix.utils.ArtworkUrls
import com.dskja.betterstreamflix.utils.EpisodeDisplayLabels
import com.dskja.betterstreamflix.utils.EpisodeManager
import com.dskja.betterstreamflix.utils.EpisodeNotesStore
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
        if ((
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
    private var downloadButtonJob: Job? = null

    fun bind(episode: Episode) {
        this.episode = episode

        when (_binding) {
            is ItemEpisodeMobileBinding -> displayMobileItem(_binding)
            is ItemEpisodeDetailMobileBinding -> displayDetailMobileItem(_binding)
            is ItemEpisodeTvBinding -> displayTvItem(_binding)
            is ItemEpisodeContinueWatchingMobileBinding -> displayContinueWatchingMobileItem(_binding)
            is ItemEpisodeContinueWatchingTvBinding -> displayContinueWatchingTvItem(_binding)
        }
        applySpoilerShieldAfterBind()
    }

    private fun displayMobileItem(binding: ItemEpisodeMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                findNavController().navigate(
                    SeasonMobileFragmentDirections.actionSeasonToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = playerEpisodeSubtitle(),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = SpoilerShield.displayTitle(
                                episode,
                                episodeNumberFallback = {
                                    context.getString(R.string.episode_number, it)
                                },
                            ),
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
                val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                if (fragment != null &&
                    com.dskja.betterstreamflix.sync.CastRoomSession.isActive()
                ) {
                    val options = arrayOf(
                        context.getString(R.string.cast_room_add_action),
                        context.getString(R.string.favorites_show_options),
                    )
                    androidx.appcompat.app.AlertDialog.Builder(context)
                        .setTitle(shieldedEpisodeTitle())
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> com.dskja.betterstreamflix.sync.CastRoomUi.offerAddFromDetail(
                                    fragment,
                                    com.dskja.betterstreamflix.sync.CastRoomUi.videoTypeFromEpisode(episode),
                                )
                                1 -> ShowOptionsMobileDialog(context, episode).show()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    ShowOptionsMobileDialog(context, episode).show()
                }
                true
            }
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            val dens = resources.displayMetrics.density
            val h = (96f * dens).toInt().coerceAtLeast(160)
            val w = (h * 16 / 9f).toInt().coerceAtLeast(240)
            Glide.with(context)
                .load(ArtworkUrls.preferW500(episode.poster) ?: episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .override(w, h)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
            {
                ExpMotion.kenBurns(this)
            }
        }
        binding.ivEpisodeWatchedRibbon.let { ribbon ->
            val wasVisible = ribbon.visibility == View.VISIBLE
            ribbon.visibility = if (episode.isWatched) View.VISIBLE else View.GONE
            if (episode.isWatched) {
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
        {
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
            if (show) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }
        binding.tvEpisodeOverview.apply {
            text = episode.overview ?: ""
            if (!episode.overview.isNullOrBlank()) {
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
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true
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

        // Soft elevated row; up-next gets a brighter glass plate (no blue border).
        val isUpNext = episode.id == episode.tvShow?.episodeToWatch?.id
        binding.root.setBackgroundResource(
            if (isUpNext) R.drawable.bg_detail_episode_row_up_next
            else R.drawable.bg_detail_episode_row,
        )

        binding.flEpisodeThumb.apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 10f * resources.displayMetrics.density)
                }
            }
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            val posterUrl = ArtworkUrls.preferW500(episode.poster) ?: episode.poster
            if (getTag(R.id.detail_episode_poster_url_tag) != posterUrl || drawable == null) {
                setTag(R.id.detail_episode_poster_url_tag, posterUrl)
                val dens = resources.displayMetrics.density
                val h = (96f * dens).toInt().coerceAtLeast(160)
                val w = (h * 16 / 9f).toInt().coerceAtLeast(240)
                Glide.with(context)
                    .load(posterUrl)
                    .error(R.drawable.glide_fallback_cover)
                    .override(w, h)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(this)
            }
        }

        binding.ivEpisodeWatchedRibbon.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = if (episode.isWatched) View.VISIBLE else View.GONE
        }
        bindDownloadRibbon(binding.ivEpisodeDownloadRibbon)
        bindEpisodeProgress(binding.pbEpisodeProgress)

        val title = episode.title ?: context.getString(R.string.episode_number, episode.number)
        binding.tvEpisodeTitle.text = "E${episode.number} · $title"
        binding.tvEpisodeTitle.setTextColor(
            ContextCompat.getColor(context, R.color.cinema_text),
        )

        binding.tvEpisodeMeta.text = buildDetailMetaLine()
        binding.tvEpisodeMeta.visibility =
            if (binding.tvEpisodeMeta.text.isNullOrBlank()) View.GONE else View.VISIBLE
        val accent = com.dskja.betterstreamflix.ui.DetailWatchLabels.episodeMetaAccent(
            context,
            episode.watchHistory,
            episode.isWatched,
        )
        binding.tvEpisodeMeta.setTextColor(
            ContextCompat.getColor(
                context,
                if (accent != null) R.color.cinema_text else R.color.cinema_text_muted,
            ),
        )

        val ageBadge = contentRatingBadge(episode.tvShow?.contentRating)
        binding.tvEpisodeAgeRating.apply {
            text = ageBadge
            visibility = if (ageBadge.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        bindEpisodeNotePreview(binding.tvEpisodeNote)

        binding.tvEpisodeOverview.apply {
            text = episode.overview.orEmpty()
            visibility = if (episode.overview.isNullOrBlank()) View.GONE else View.VISIBLE
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            var expanded = false
            setOnClickListener {
                ExpMotion.hapticTap(it)
                expanded = !expanded
                maxLines = if (expanded) Integer.MAX_VALUE else 2
            }
        }

        binding.btnEpisodeNote.apply {
            visibility = View.VISIBLE
            contentDescription = EpisodeNotesUi.optionLabel(context, episode.id)
            alpha = if (EpisodeNotesStore.has(context, episode.id)) 1f else 0.72f
            setOnClickListener {
                ExpMotion.hapticTap(it)
                EpisodeNotesUi.showEditor(context, episode) {
                    bindEpisodeNotePreview(binding.tvEpisodeNote)
                    contentDescription = EpisodeNotesUi.optionLabel(context, episode.id)
                    alpha = if (EpisodeNotesStore.has(context, episode.id)) 1f else 0.72f
                }
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
                    checkProviderAndRun {
                        DownloadOptionsController.enqueueEpisode(fragment, episode)
                    }
                }
                bindEpisodeDownloadButtonState(this)
            }
        }
    }

    private fun bindEpisodeNotePreview(noteView: android.widget.TextView) {
        val note = EpisodeNotesStore.get(context, episode.id)
        if (note.isNullOrBlank()) {
            noteView.visibility = View.GONE
            noteView.text = ""
            noteView.setOnClickListener(null)
            return
        }
        noteView.visibility = View.VISIBLE
        noteView.text = context.getString(
            R.string.player_episode_note_label,
            EpisodeNotesStore.preview(note),
        )
        noteView.setOnClickListener {
            ExpMotion.hapticTap(it)
            EpisodeNotesUi.showEditor(context, episode) {
                bindEpisodeNotePreview(noteView)
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
        val upNext = episode.id == episode.tvShow?.episodeToWatch?.id
        val accent = com.dskja.betterstreamflix.ui.DetailWatchLabels.episodeMetaAccent(
            context,
            episode.watchHistory,
            episode.isWatched,
        ) ?: if (upNext) context.getString(R.string.detail_up_next) else null
        val date = if (accent == null) episode.released?.format("MMM d, yyyy") else null
        return listOfNotNull(runtime, accent, date).joinToString(" · ")
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
        val subtitle = playerEpisodeSubtitle()
        val videoType = Video.Type.Episode(
            id = episode.id,
            number = episode.number,
            title = SpoilerShield.displayTitle(
                episode,
                episodeNumberFallback = { context.getString(R.string.episode_number, it) },
            ),
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
                        subtitle = playerEpisodeSubtitle(),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = SpoilerShield.displayTitle(
                                episode,
                                episodeNumberFallback = {
                                    context.getString(R.string.episode_number, it)
                                },
                            ),
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
            // Horizontal DPAD: stay on sibling episode cards (download CTA is non-focusable).
            wireEpisodeSiblingFocus(this)
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            val dens = resources.displayMetrics.density
            val h = (96f * dens).toInt().coerceAtLeast(160)
            val w = (h * 16 / 9f).toInt().coerceAtLeast(240)
            var request = Glide.with(context)
                .load(ArtworkUrls.preferW500(episode.poster) ?: episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .fallback(R.drawable.glide_fallback_cover)
                .override(w, h)
                .centerCrop()
            if (!DeviceCapabilities.shouldReduceHomeEffects(context)) {
                request = request.transition(DrawableTransitionOptions.withCrossFade())
            }
            request.into(this)
        }
        binding.ivEpisodeWatchedRibbon.let { ribbon ->
            ribbon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            ribbon.visibility = if (episode.isWatched) View.VISIBLE else View.GONE
            if (!episode.isWatched) {
                ribbon.background = null
            }
        }
        binding.ivEpisodeDownloadRibbon.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO
        bindDownloadRibbon(binding.ivEpisodeDownloadRibbon)
        applyEpisodeA11y()

        bindEpisodeProgress(binding.pbEpisodeProgress)

        val badgeView = binding.root.findViewById<android.widget.TextView>(R.id.tv_episode_number_badge)
        badgeView?.apply {
            text = EpisodeDisplayLabels.badge(episode.number)
            visibility = View.VISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Badge already shows E01 — hide duplicate info line (TV-SEAS-05).
        binding.tvEpisodeInfo.visibility = View.GONE

        binding.tvEpisodeTitle.apply {
            text = EpisodeDisplayLabels.readableTitle(episode.title, episode.number) {
                context.getString(R.string.episode_number, it)
            }
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.VISIBLE
        }

        binding.tvEpisodeReleased.apply {
            val meta = buildDetailMetaLine().ifBlank {
                episode.released?.format("EEEE - MMMM dd, yyyy").orEmpty()
            }
            text = meta
            val show = meta.isNotBlank()
            visibility = if (show) View.VISIBLE else View.GONE
        }
        bindEpisodeNotePreview(binding.tvEpisodeNote)
        binding.tvEpisodeOverview.apply {
            val overview = episode.overview?.trim().orEmpty()
            text = overview
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = if (overview.isBlank()) View.GONE else View.VISIBLE
        }

        binding.btnEpisodeDownload.apply {
            // Non-focusable: long-press → ShowOptions (includes Download). Click kept for touch.
            isFocusable = false
            isFocusableInTouchMode = false
            binding.root.nextFocusDownId = View.NO_ID
            val iptv = UserPreferences.currentProvider is com.dskja.betterstreamflix.providers.IptvProvider ||
                (episode.tvShow?.providerName?.let { Provider.findByName(it) }
                    is com.dskja.betterstreamflix.providers.IptvProvider)
            if (iptv) {
                visibility = View.GONE
                setOnClickListener(null)
            } else {
                visibility = View.VISIBLE
                contentDescription = context.getString(R.string.player_download_with_long_press)
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                        ?: return@setOnClickListener
                    checkProviderAndRun {
                        DownloadOptionsController.enqueueEpisode(fragment, episode)
                    }
                }
                // Nested focusable download traps Leanback HGV — keep clickable for OK, not focusable.
                setOnFocusChangeListener(null)
                bindEpisodeDownloadButtonState(this as View)
            }
        }
    }

    /** Point LEFT/RIGHT at adjacent episode ViewHolders so DPAD never traps in-item. */
    private fun wireEpisodeSiblingFocus(root: View) {
        root.post {
            val rv = root.parent as? RecyclerView ?: return@post
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@post
            fun ensureId(view: View): Int {
                if (view.id == View.NO_ID) view.id = View.generateViewId()
                return view.id
            }
            val left = rv.findViewHolderForAdapterPosition(pos - 1)?.itemView
            val right = rv.findViewHolderForAdapterPosition(pos + 1)?.itemView
            root.nextFocusLeftId = left?.let { ensureId(it) } ?: View.NO_ID
            root.nextFocusRightId = right?.let { ensureId(it) } ?: View.NO_ID
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
            val subtitle = playerEpisodeSubtitle()
            val navTitle = SpoilerShield.displayTitle(
                episode,
                episodeNumberFallback = { context.getString(R.string.episode_number, it) },
            )
            com.dskja.betterstreamflix.player.PlayerCleanExit.tagHomeFocusView(itemView, episode.id)
            com.dskja.betterstreamflix.player.PlayerCleanExit.rememberHomeFocus(
                episode.id,
                rowHint = "continue_watching",
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
                            title = navTitle,
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
        // Next Episodes reuse the CW item type but are not mid-watch — one-tap play
        // (parity with mobile). Only confirm when resuming in-progress CW.
        val midWatch = episode.watchHistory?.let { history ->
            history.lastPlaybackPositionMillis > 0L && history.durationMillis > 0L
        } == true
        if (!leanback || !midWatch) {
            play()
            return
        }
        // OK / Details / Cancel buttons — setItems is fragile for Fire OS DPAD focus.
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.continue_watching_confirm_title)
            .setPositiveButton(R.string.home_swiper_watch_now) { _, _ -> play() }
            .setNeutralButton(R.string.continue_watching_go_to_details) { _, _ -> details() }
            .setNegativeButton(R.string.option_cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    com.dskja.betterstreamflix.utils.ExpDialogChrome.polishButtons(dialog)
                }
                dialog.show()
            }
    }

    private fun displayContinueWatchingMobileItem(binding: ItemEpisodeContinueWatchingMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                // Go straight to player (parity with continue-watching movies) — no detail detour.
                checkProviderAndRun {
                    val subtitle = playerEpisodeSubtitle()
                    val navTitle = SpoilerShield.displayTitle(
                        episode,
                        episodeNumberFallback = { context.getString(R.string.episode_number, it) },
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
                                    title = navTitle,
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
            {
                ExpMotion.kenBurns(this)
            }
        }

        bindDownloadRibbon(binding.ivEpisodeDownloadRibbon)
        bindEpisodeProgress(binding.pbEpisodeProgress)
        bindEpisodeRemainingPill(binding.root)

        binding.tvEpisodeTvShowTitle.text = episode.tvShow?.title ?: ""
        episode.tvShow?.let { show ->
            com.dskja.betterstreamflix.logo.TitleLogoSurface.bindAndMaybeResolve(
                anchor = binding.root,
                imageView = binding.ivEpisodeTvShowLogo,
                titleView = null,
                tvShow = show,
                hideUntilReady = true,
            )
        } ?: com.dskja.betterstreamflix.logo.TitleLogoSurface.bindCachedOnly(
            imageView = binding.ivEpisodeTvShowLogo,
            titleView = null,
            logoUrl = null,
            title = "",
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
        {
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
            {
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
                            val show = episode.tvShow
                            fragment.pinBackground(
                                ArtworkUrls.featuredBannerOrPoster(show?.banner, show?.poster),
                            )
                        } else {
                            fragment.releasePinnedBackground()
                        }
                    }
                }
            }
        }

        binding.ivEpisodeTvShowPoster.apply {
            clipToOutline = true
            com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.applyShelfPosterHeight(this)
            loadContinueWatchingArtwork(withFallback = true)
            {
                ExpMotion.kenBurns(this)
            }
        }

        bindEpisodeProgress(binding.pbEpisodeProgress)
        bindEpisodeRemainingPill(binding.root)

        binding.tvEpisodeTvShowTitle.text = episode.tvShow?.title ?: ""
        episode.tvShow?.let { show ->
            com.dskja.betterstreamflix.logo.TitleLogoSurface.bindAndMaybeResolve(
                anchor = binding.root,
                imageView = binding.ivEpisodeTvShowLogo,
                titleView = null,
                tvShow = show,
                hideUntilReady = true,
            )
        } ?: com.dskja.betterstreamflix.logo.TitleLogoSurface.bindCachedOnly(
            imageView = binding.ivEpisodeTvShowLogo,
            titleView = null,
            logoUrl = null,
            title = "",
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

        {
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

    private fun applyEpisodeA11y(downloaded: Boolean? = null) {
        val contentKey = episodeDownloadContentKey()
        val isDownloaded = downloaded
            ?: (contentKey != null && OfflineBadgeStore.isCompleted(context, contentKey))
        itemView.contentDescription = AccessibilityPreferences.episodeLabel(
            context,
            episode.number,
            episode.title,
            watched = episode.isWatched,
            downloaded = isDownloaded,
        )
    }

    private fun bindDownloadRibbon(downloadRibbon: View) {
        downloadRibbon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val boundEpisodeId = episode.id
        val contentKey = episodeDownloadContentKey()
        val show = contentKey != null && OfflineBadgeStore.isCompleted(context, contentKey)
        val wasVisible = downloadRibbon.visibility == View.VISIBLE
        downloadRibbon.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            downloadRibbon.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
            val pad = (4 * context.resources.displayMetrics.density).toInt()
            downloadRibbon.setPadding(pad, pad, pad, pad)
            if (!wasVisible) ExpMotion.popIn(downloadRibbon)
        } else if (!show) {
            downloadRibbon.background = null
        }
        applyEpisodeA11y(show)

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
                if (visible) {
                    downloadRibbon.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
                    val pad = (4 * context.resources.displayMetrics.density).toInt()
                    downloadRibbon.setPadding(pad, pad, pad, pad)
                    if (!was) ExpMotion.popIn(downloadRibbon)
                } else if (!visible) {
                    downloadRibbon.background = null
                }
                applyEpisodeA11y(visible)
            }
        }
    }

    /** Reflect completed / in-progress download state on the episode download button (DL-P2-K). */
    private fun bindEpisodeDownloadButtonState(button: View) {
        val boundEpisodeId = episode.id
        fun applyState(completed: Boolean, active: Boolean) {
            when {
                completed -> {
                    if (button is ImageView) {
                        button.setImageResource(R.drawable.ic_offline_badge)
                        button.imageAlpha = 255
                    } else if (button is android.widget.TextView) {
                        button.setText(R.string.downloads_already_completed)
                        button.alpha = 1f
                    }
                    button.contentDescription = context.getString(R.string.downloads_already_completed)
                }
                active -> {
                    if (button is ImageView) {
                        button.setImageResource(R.drawable.ic_menu_downloads)
                        button.imageAlpha = 180
                    } else if (button is android.widget.TextView) {
                        button.setText(R.string.downloads_already_active)
                        button.alpha = 0.7f
                    }
                    button.contentDescription = context.getString(R.string.downloads_already_active)
                }
                else -> {
                    if (button is ImageView) {
                        button.setImageResource(R.drawable.ic_menu_downloads)
                        button.imageAlpha = 255
                    } else if (button is android.widget.TextView) {
                        button.setText(R.string.detail_download)
                        button.alpha = 1f
                    }
                    button.contentDescription = context.getString(R.string.detail_download)
                }
            }
        }
        val key = episodeDownloadContentKey()
        applyState(
            completed = key != null && OfflineBadgeStore.isCompleted(context, key),
            active = key != null && OfflineBadgeStore.isActive(context, key),
        )
        downloadButtonJob?.cancel()
        val lifecycleOwner = itemView.findViewTreeLifecycleOwner()
            ?: context.toActivity()
            ?: return
        downloadButtonJob = lifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                OfflineBadgeStore.completedKeys(context),
                OfflineBadgeStore.activeKeys(context),
            ) { completed, active -> completed to active }
                .collect { (completed, active) ->
                    if (episode.id != boundEpisodeId) return@collect
                    val contentKey = episodeDownloadContentKey()
                    applyState(
                        completed = contentKey != null && completed.contains(contentKey),
                        active = contentKey != null && active.contains(contentKey),
                    )
                }
        }
    }

    private fun ImageView.loadContinueWatchingArtwork(withFallback: Boolean = false) {
        val tvShow = episode.tvShow
        val reduce = DeviceCapabilities.shouldReduceHomeEffects(context)
        val dens = resources.displayMetrics.density
        val (w, h) = com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.shelfPosterOverridePx(dens)
        if (tvShow == null) {
            var request = Glide.with(context)
                .load(
                    ArtworkUrls.preferPoster(episode.poster)
                        ?: ArtworkUrls.preferW500(episode.poster)
                        ?: episode.poster,
                )
                .error(R.drawable.glide_fallback_cover)
                .apply {
                    if (withFallback) fallback(R.drawable.glide_fallback_cover)
                }
                .override(w, h)
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
            override(w, h)
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
        when {
            watchHistory != null && watchHistory.durationMillis > 0 -> {
                remaining.text = com.dskja.betterstreamflix.ui.DetailWatchLabels
                    .remainingMinutesLabel(context, watchHistory)
                    ?: context.getString(R.string.continue_watching_percent, 0)
                {
                    remaining.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                    val density = context.resources.displayMetrics.density
                    remaining.setPadding(
                        (8 * density).toInt(),
                        (2 * density).toInt(),
                        (8 * density).toInt(),
                        (2 * density).toInt(),
                    )
                }
                val wasVisible = remaining.visibility == View.VISIBLE
                remaining.visibility = View.VISIBLE
                if (!wasVisible) ExpMotion.popIn(remaining)
            }
            // Next-episode shelf (and unfinished CW without a resume point): badge, not a 0% bar.
            !episode.isWatched -> {
                remaining.text = context.getString(R.string.detail_up_next)
                {
                    remaining.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                }
                val wasVisible = remaining.visibility == View.VISIBLE
                remaining.visibility = View.VISIBLE
                if (!wasVisible) ExpMotion.popIn(remaining)
            }
            else -> remaining.visibility = View.GONE
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
        if (show && (!wasVisible || bar.progress != target)) {
            android.animation.ObjectAnimator.ofInt(bar, "progress", 0, target)
                .setDuration(420L)
                .start()
        } else {
            bar.progress = target
        }
    }


    private fun shieldedEpisodeTitle(): String = SpoilerShield.displayTitle(
        episode,
        episodeNumberFallback = { context.getString(R.string.episode_number, it) },
    )

    /**
     * After layout-specific binds: blur poster/title, hide spoiler copy, and gate
     * the root click until the user unlocks (Kids PIN / adult confirm).
     */
    private fun applySpoilerShieldAfterBind() {
        val shielded = SpoilerShield.isShielded(episode)
        val posterViews = listOfNotNull(
            itemView.findViewById<ImageView>(R.id.iv_episode_poster),
            itemView.findViewById<ImageView>(R.id.iv_episode_tv_show_poster),
        )
        val titleView = itemView.findViewById<TextView>(R.id.tv_episode_title)
        val overviewView = itemView.findViewById<TextView>(R.id.tv_episode_overview)
        val infoView = itemView.findViewById<TextView>(R.id.tv_episode_info)
        posterViews.forEach { SpoilerShield.applyPosterShield(it, shielded) }
        if (!shielded) {
            SpoilerShield.applyTextShield(titleView, false)
            SpoilerShield.applyTextShield(overviewView, false)
            SpoilerShield.applyTextShield(infoView, false)
            return
        }
        val safeTitle = shieldedEpisodeTitle()
        val rawTitle = episode.title?.trim().orEmpty()
        if (titleView != null) {
            val current = titleView.text?.toString().orEmpty()
            titleView.text = when {
                current.contains(" · ") ->
                    current.substringBefore(" · ") + " · " + safeTitle
                rawTitle.isNotEmpty() && current.contains(rawTitle) ->
                    current.replace(rawTitle, safeTitle)
                else -> safeTitle
            }
            SpoilerShield.applyTextShield(titleView, true)
        }
        if (infoView != null && rawTitle.isNotEmpty()) {
            val current = infoView.text?.toString().orEmpty()
            if (rawTitle in current) {
                infoView.text = current.replace(rawTitle, safeTitle)
            }
            SpoilerShield.applyTextShield(infoView, true)
        }
        if (overviewView != null) {
            SpoilerShield.applyOverviewShield(
                overviewView,
                shielded = true,
                placeholder = context.getString(R.string.spoiler_shield_overview_placeholder),
                real = episode.overview,
            )
        }
        itemView.contentDescription = context.getString(
            R.string.spoiler_shield_episode_cd,
            context.getString(R.string.episode_number, episode.number),
        )
        val shieldedClick = View.OnClickListener { tapped ->
            ExpMotion.hapticTap(tapped)
            val fragment = context.toActivity()?.getCurrentFragment() as? Fragment ?: return@OnClickListener
            SpoilerShieldUi.requestUnlock(fragment, episode) {
                bind(episode)
            }
        }
        itemView.setOnClickListener(shieldedClick)
        itemView.setOnLongClickListener {
            shieldedClick.onClick(it)
            true
        }
    }

    private fun playerEpisodeSubtitle(): String {
        val titlePart = SpoilerShield.displayTitle(
            episode,
            episodeNumberFallback = { context.getString(R.string.episode_number, it) },
        )
        return episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.player_subtitle_tv_show,
                season.number,
                episode.number,
                titlePart,
            )
        } ?: context.getString(
            R.string.player_subtitle_tv_show_episode_only,
            episode.number,
            titlePart,
        )
    }


}
