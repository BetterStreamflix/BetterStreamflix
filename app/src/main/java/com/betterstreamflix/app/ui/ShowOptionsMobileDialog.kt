package com.betterstreamflix.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.betterstreamflix.app.R
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.databinding.DialogShowOptionsMobileBinding
import com.betterstreamflix.app.download.ui.DownloadOptionsController
import com.betterstreamflix.app.fragments.home.HomeMobileFragment
import com.betterstreamflix.app.fragments.home.HomeMobileFragmentDirections
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.sync.FavoriteMutations
import com.betterstreamflix.app.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class ShowOptionsMobileDialog(
    context: Context,
    show: AppAdapter.Item,
) : BottomSheetDialog(context) {

    private val binding = DialogShowOptionsMobileBinding.bind(
        LayoutInflater.from(context).inflate(
            R.layout.dialog_show_options_mobile,
            null,
        )
    )

    private val database: AppDatabase?
        get() = AppDatabase.getInstanceOrNull(context)

    private fun checkProviderAndRun(show: AppAdapter.Item, action: () -> Unit) {
        val providerName = when (show) {
            is Movie -> show.providerName
            is TvShow -> show.providerName
            is Episode -> show.tvShow?.providerName
            else -> null
        }

        if (!providerName.isNullOrBlank() && providerName != UserPreferences.currentProvider?.name) {
            Provider.findByName(providerName)?.let {
                UserPreferences.setCurrentProviderForPlayback(it)
            }
        }
        action()
    }

    init {
        setContentView(binding.root)

        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)

        binding.root.setBackgroundResource(R.drawable.bg_profile_dialog)
        ExpMotion.enterScreen(binding.root)
        with(ExpPressEffects) {
            listOf(
                R.id.btn_option_episode_open_tv_show,
                R.id.btn_option_show_favorite,
                R.id.btn_option_show_watched,
                R.id.btn_option_show_download,
                R.id.btn_option_show_taste,
                R.id.btn_option_episode_note,
                R.id.btn_option_episode_mark_all_previous_watched,
                R.id.btn_option_program_clear,
                R.id.btn_option_recently_watched_clear,
                R.id.btn_option_cancel,
            ).forEach { id ->
                binding.root.findViewById<View>(id)?.let { btn ->
                    btn.setBackgroundResource(
                        if (id == R.id.btn_option_cancel) {
                            R.drawable.bg_outlined_chip
                        } else {
                            R.drawable.bg_outlined_chip
                        },
                    )
                    btn.applyExpPress()
                }
            }
        }
        ExpMotion.revealHeader(
            binding.ivOptionsShowPoster,
            binding.tvOptionsShowTitle,
            binding.tvShowSubtitle,
            binding.root.findViewById(R.id.v_show_options_rule),
        )
        ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_show_options_rule))

        when (show) {
            is Episode -> displayEpisode(show)
            is Movie -> displayMovie(show)
            is TvShow -> displayTvShow(show)
        }

        listOf(
            R.id.btn_option_episode_open_tv_show,
            R.id.btn_option_show_favorite,
            R.id.btn_option_show_watched,
            R.id.btn_option_show_download,
            R.id.btn_option_show_taste,
            R.id.btn_option_episode_note,
            R.id.btn_option_episode_mark_all_previous_watched,
            R.id.btn_option_program_clear,
            R.id.btn_option_recently_watched_clear,
            R.id.btn_option_cancel,
        ).forEachIndexed { index, id ->
            binding.root.findViewById<View>(id)?.takeIf { it.visibility == View.VISIBLE }?.let { row ->
                row.postDelayed({ ExpMotion.popIn(row) }, 36L * index)
            }
        }

        binding.btnOptionCancel.setOnClickListener {
            ExpMotion.hapticTap(it)
            dismiss()
        }
    }


    private fun displayEpisode(episode: Episode) {
        val provider = UserPreferences.currentProvider

        Glide.with(context)
            .load(episode.poster ?: episode.tvShow?.poster)
            .fitCenter()
            .into(binding.ivOptionsShowPoster)

        binding.tvOptionsShowTitle.text = episode.tvShow?.title ?: ""

        val epTitle = com.betterstreamflix.app.spoilers.SpoilerShield.displayTitle(
            episode,
            episodeNumberFallback = { context.getString(R.string.episode_number, it) },
        )
        binding.tvShowSubtitle.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.episode_item_info,
                season.number,
                episode.number,
                epTitle,
            )
        } ?: context.getString(
            R.string.episode_item_info_episode_only,
            episode.number,
            epTitle,
        )


        binding.btnOptionEpisodeOpenTvShow.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                when (val fragment = context.toActivity()?.getCurrentFragment()) {
                    is HomeMobileFragment -> episode.tvShow?.let { tvShow ->
                        NavHostFragment.findNavController(fragment).navigate(
                            HomeMobileFragmentDirections.actionHomeToTvShow(
                                id = tvShow.id,
                                poster = tvShow.poster,
                                banner = tvShow.banner,
                            )
                        )
                    }
                }
                dismiss()
            }

            visibility = when (context.toActivity()?.getCurrentFragment()) {
                is HomeMobileFragment -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.btnOptionShowFavorite.visibility = View.GONE
        binding.btnOptionChannelPin.visibility = View.GONE

        binding.btnOptionShowWatched.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun(episode) {
                    val currentProvider = UserPreferences.currentProvider ?: return@checkProviderAndRun
                    val updatedEpisode = episode.copy().apply {
                        merge(episode)
                        isWatched = !isWatched
                        if (isWatched) {
                            watchedDate = Calendar.getInstance()
                            watchHistory = null
                        } else {
                            watchedDate = null
                        }
                    }
                    database?.episodeDao()?.save(updatedEpisode)
                    UserDataCache.syncEpisodeToCache(context, currentProvider, updatedEpisode)

                    // NUOVA LOGICA: Aggiorna lo stato isWatching della serie TV madre
                    episode.tvShow?.let { tvShow ->
                        val episodeDao = database?.episodeDao() ?: return@checkProviderAndRun
                        val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                        // Se l'episodio è stato marcato come VISTO E non ci sono altri
                        // episodi con cronologia, impostiamo isWatching a false.
                        if (updatedEpisode.isWatched && !isStillWatching) {
                            database?.tvShowDao()?.save(tvShow.copy().apply {
                                merge(tvShow)
                                isWatching = false
                            })
                            UserDataCache.removeEpisodeFromContinueWatching(context, currentProvider, episode.id)
                        }
                    }
                    if (updatedEpisode.isWatched) {
                        UserDataCache.removeEpisodeFromContinueWatching(context, currentProvider, episode.id)
                    }
                }

                dismiss()
            }

            text = when {
                episode.isWatched -> context.getString(R.string.option_show_unwatched)
                else -> context.getString(R.string.option_show_watched)
            }
            visibility = View.VISIBLE
        }

        binding.btnOptionShowDownload.apply {
            val iptv = UserPreferences.currentProvider is com.betterstreamflix.app.providers.IptvProvider ||
                (episode.tvShow?.providerName?.let { Provider.findByName(it) }
                    is com.betterstreamflix.app.providers.IptvProvider)
            if (iptv) {
                visibility = View.GONE
            } else {
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    val activity = context.toActivity() as? Activity
                    if (activity != null) {
                        DownloadOptionsController.enqueueEpisode(context, activity, episode)
                    }
                    dismiss()
                }
                visibility = View.VISIBLE
            }
        }

        binding.btnOptionShowTaste.visibility = View.GONE
        binding.btnOptionCustomLogo.visibility = View.GONE

        binding.btnOptionEpisodeNote.apply {
            text = EpisodeNotesUi.optionLabel(context, episode.id)
            visibility = View.VISIBLE
            setOnClickListener {
                ExpMotion.hapticTap(it)
                EpisodeNotesUi.showEditor(context, episode)
                dismiss()
            }
        }

        binding.btnOptionEpisodeMarkAllPreviousWatched.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun(episode) {
                    val episodeDao = database?.episodeDao() ?: return@checkProviderAndRun
                    val episodeNumber = episode.number
                    val tvShowId = episode.tvShow?.id ?: return@checkProviderAndRun
                    val allEpisodes = episodeDao.getEpisodesByTvShowIdAndSeason(tvShowId, episode.season?.id).filter { it.number <= episodeNumber }
                    val targetState = !episode.isWatched // If current is watched, we unwatch; else, we mark watched
                    val now = Calendar.getInstance()
                    val currentProvider = UserPreferences.currentProvider ?: return@checkProviderAndRun

                    for (ep in allEpisodes) {
                        if (ep.isWatched != targetState) {
                            val updatedEp = ep.copy().apply {
                                merge(ep)
                                isWatched = targetState
                                watchedDate = if (targetState) now else null
                                watchHistory = if (targetState) null else watchHistory
                            }
                            episodeDao.save(updatedEp)
                            UserDataCache.syncEpisodeToCache(context, currentProvider, updatedEp)
                        }
                    }

                    // Logica aggiuntiva per isWatching:
                    // Se l'obiettivo era marcare come VISTO, e non ci sono cronologie, si imposta isWatching a false.
                    if (targetState) {
                        episode.tvShow?.let { tvShow ->
                            if (!episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)) {
                                database?.tvShowDao()?.save(tvShow.copy().apply {
                                    merge(tvShow)
                                    isWatching = false
                                })
                                UserDataCache.removeEpisodeFromContinueWatching(context, currentProvider, episode.id)
                            }
                        }
                        UserDataCache.removeEpisodeFromContinueWatching(context, currentProvider, episode.id)
                    }
                    // Se l'obiettivo era marcare come NON VISTO, impostiamo isWatching a true per farlo riapparire.
                    if (!targetState) {
                        episode.tvShow?.let { tvShow ->
                            database?.tvShowDao()?.save(tvShow.copy().apply {
                                merge(tvShow)
                                isWatching = true
                            })
                        }
                    }
                }

                dismiss()
            }

            text = when {
                episode.isWatched -> context.getString(R.string.option_show_mark_all_previous_unwatched)
                else -> context.getString(R.string.option_show_mark_all_previous_watched)
            }
            visibility = View.VISIBLE
        }


        binding.btnOptionProgramClear.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun(episode) {
                    val provider = UserPreferences.currentProvider ?: return@checkProviderAndRun
                    val updatedEpisode = episode.copy().apply {
                        merge(episode)
                        watchHistory = null
                    }
                    database?.episodeDao()?.save(updatedEpisode)
                    UserDataCache.syncEpisodeToCache(context, provider, updatedEpisode)
                    
                    episode.tvShow?.let { tvShow ->
                        // Rimuoviamo isWatching solo se NON ci sono altri episodi in corso
                        val episodeDao = database?.episodeDao() ?: return@checkProviderAndRun
                        if (!episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)) {
                            database?.tvShowDao()?.save(tvShow.copy().apply {
                                merge(tvShow)
                                isWatching = false
                            })
                            UserDataCache.removeEpisodeFromContinueWatching(context, provider, episode.id)
                        }
                    }
                    UserDataCache.removeEpisodeFromContinueWatching(context, provider, episode.id)
                }

                dismiss()
            }

            visibility = when {
                episode.watchHistory != null -> View.VISIBLE
                episode.tvShow?.isWatching ?: false -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.btnOptionRecentlyWatchedClear.visibility = View.GONE
    }

    private fun displayMovie(movie: Movie) {
        binding.ivOptionsShowPoster.loadMoviePoster(movie) {
            fitCenter()
        }

        binding.tvOptionsShowTitle.text = movie.title

        binding.tvShowSubtitle.text = movie.released?.format("yyyy")
        if (!binding.tvShowSubtitle.text.isNullOrBlank()) {
            binding.tvShowSubtitle.setBackgroundResource(R.drawable.bg_meta_pill)
            val density = context.resources.displayMetrics.density
            binding.tvShowSubtitle.setPadding(
                (10 * density).toInt(),
                (4 * density).toInt(),
                (10 * density).toInt(),
                (4 * density).toInt(),
            )
        }


        binding.btnOptionEpisodeOpenTvShow.visibility = View.GONE
        binding.btnOptionChannelPin.visibility = View.GONE

        val freshMovie = database?.movieDao()?.getById(movie.id) ?: movie
        val recentlyWatchedAt = freshMovie.lastPlayedAtMillis ?: movie.lastPlayedAtMillis

        binding.btnOptionShowFavorite.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun(freshMovie) {
                    val provider = UserPreferences.currentProvider ?: return@checkProviderAndRun
                    context.toActivity()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val db = database ?: return@launch
                        val current = db.movieDao().getById(movie.id)?.isFavorite ?: false
                        FavoriteMutations.setMovieFavorite(
                            context,
                            provider,
                            movie,
                            favorite = !current,
                            database = db,
                        )
                    }
                }

                dismiss()
            }

            text = when {
                freshMovie.isFavorite -> context.getString(R.string.option_show_unfavorite)
                else -> context.getString(R.string.option_show_favorite)
            }
            setBackgroundResource(
                if (freshMovie.isFavorite) R.drawable.bg_button_mobile
                else R.drawable.bg_outlined_chip,
            )
            if (freshMovie.isFavorite) {
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnPrimary,
                    ),
                )
            }
            visibility = View.VISIBLE
        }

        binding.btnOptionShowWatched.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun(freshMovie) {
                    val provider = UserPreferences.currentProvider ?: return@checkProviderAndRun
                    val updatedMovie = freshMovie.copy().apply {
                        merge(freshMovie)
                        isWatched = !isWatched
                        if (isWatched) {
                            watchedDate = Calendar.getInstance()
                            watchHistory = null
                        } else {
                            watchedDate = null
                        }
                    }
                    database?.movieDao()?.save(updatedMovie)
                    UserDataCache.syncMovieToCache(context, provider, updatedMovie)
                    
                    if (updatedMovie.isWatched) {
                        UserDataCache.removeMovieFromContinueWatching(context, provider, freshMovie.id)
                    }
                }

                dismiss()
            }

            text = when {
                freshMovie.isWatched -> context.getString(R.string.option_show_unwatched)
                else -> context.getString(R.string.option_show_watched)
            }
            setBackgroundResource(
                if (freshMovie.isWatched) R.drawable.bg_button_mobile
                else R.drawable.bg_outlined_chip,
            )
            if (freshMovie.isWatched) {
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnPrimary,
                    ),
                )
            }
            visibility = View.VISIBLE
        }

        binding.btnOptionShowDownload.apply {
            val iptv = UserPreferences.currentProvider is com.betterstreamflix.app.providers.IptvProvider ||
                (freshMovie.providerName?.let { Provider.findByName(it) }
                    is com.betterstreamflix.app.providers.IptvProvider)
            if (iptv) {
                visibility = View.GONE
            } else {
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    val activity = context.toActivity() as? Activity
                    if (activity != null) {
                        DownloadOptionsController.enqueueMovie(context, activity, freshMovie)
                    }
                    dismiss()
                }
                visibility = View.VISIBLE
            }
        }

        binding.btnOptionEpisodeNote.visibility = View.GONE
        binding.btnOptionEpisodeMarkAllPreviousWatched.visibility = View.GONE

        bindTasteOption(freshMovie)

        binding.btnOptionCustomLogo.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                ExpMotion.hapticTap(it)
                com.betterstreamflix.app.logo.CustomLogoDialog.show(
                    context, freshMovie.title, isTv = false, id = freshMovie.id,
                ) { dismiss() }
            }
        }

        binding.btnOptionProgramClear.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun(freshMovie) {
                    val provider = UserPreferences.currentProvider ?: return@checkProviderAndRun
                    val updatedMovie = freshMovie.copy().apply {
                        merge(freshMovie)
                        watchHistory = null
                    }
                    database?.movieDao()?.save(updatedMovie)
                    UserDataCache.syncMovieToCache(context, provider, updatedMovie)
                    UserDataCache.removeMovieFromContinueWatching(context, provider, freshMovie.id)
                }

                dismiss()
            }

            visibility = when {
                freshMovie.watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.btnOptionRecentlyWatchedClear.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun(freshMovie) {
                    database?.movieDao()?.clearRecentlyWatched(freshMovie.id)
                    freshMovie.lastPlayedAtMillis = null
                    movie.lastPlayedAtMillis = null
                    UserDataNotifier.notifyChanged()
                }
                dismiss()
            }
            visibility = if (recentlyWatchedAt != null) View.VISIBLE else View.GONE
        }
    }

    private fun displayTvShow(tvShow: TvShow) {
        binding.ivOptionsShowPoster.loadTvShowPoster(tvShow) {
            fitCenter()
        }

        binding.tvOptionsShowTitle.text = tvShow.title

        binding.tvShowSubtitle.text = tvShow.released?.format("yyyy")
        if (!binding.tvShowSubtitle.text.isNullOrBlank()) {
            binding.tvShowSubtitle.setBackgroundResource(R.drawable.bg_meta_pill)
            val density = context.resources.displayMetrics.density
            binding.tvShowSubtitle.setPadding(
                (10 * density).toInt(),
                (4 * density).toInt(),
                (10 * density).toInt(),
                (4 * density).toInt(),
            )
        }


        binding.btnOptionEpisodeOpenTvShow.visibility = View.GONE

        val freshTvShow = database?.tvShowDao()?.getById(tvShow.id) ?: tvShow
        val recentlyWatchedAt = freshTvShow.lastPlayedAtMillis ?: tvShow.lastPlayedAtMillis

        binding.btnOptionShowFavorite.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun(freshTvShow) {
                    val provider = UserPreferences.currentProvider ?: return@checkProviderAndRun
                    context.toActivity()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val db = database ?: return@launch
                        val current = db.tvShowDao().getById(tvShow.id)?.isFavorite ?: false
                        FavoriteMutations.setTvShowFavorite(
                            context,
                            provider,
                            tvShow,
                            favorite = !current,
                            database = db,
                        )
                    }
                }

                dismiss()
            }

            text = if (freshTvShow.isFavorite) {
                context.getString(R.string.option_show_unfavorite)
            } else {
                context.getString(R.string.option_show_favorite)
            }
            setBackgroundResource(
                if (freshTvShow.isFavorite) R.drawable.bg_button_mobile
                else R.drawable.bg_outlined_chip,
            )
            if (freshTvShow.isFavorite) {
                setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnPrimary,
                    ),
                )
            }
            visibility = View.VISIBLE
        }

        bindChannelPinOption(freshTvShow)

        binding.btnOptionShowWatched.visibility = View.GONE
        binding.btnOptionShowDownload.visibility = View.GONE
        binding.btnOptionEpisodeNote.visibility = View.GONE
        binding.btnOptionEpisodeMarkAllPreviousWatched.visibility = View.GONE
        binding.btnOptionProgramClear.visibility = View.GONE

        bindTasteOption(freshTvShow)

        binding.btnOptionCustomLogo.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                ExpMotion.hapticTap(it)
                com.betterstreamflix.app.logo.CustomLogoDialog.show(
                    context, freshTvShow.title, isTv = true, id = freshTvShow.id,
                ) { dismiss() }
            }
        }

        binding.btnOptionRecentlyWatchedClear.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun(freshTvShow) {
                    database?.tvShowDao()?.clearRecentlyWatched(freshTvShow.id)
                    freshTvShow.lastPlayedAtMillis = null
                    freshTvShow.lastPlayedEpisodeId = null
                    tvShow.lastPlayedAtMillis = null
                    tvShow.lastPlayedEpisodeId = null
                    UserDataNotifier.notifyChanged()
                }
                dismiss()
            }
            visibility = if (recentlyWatchedAt != null) View.VISIBLE else View.GONE
        }
    }

    /**
     * Long-press parity with TV: opens the shared taste overflow
     * (More like this / Not interested / Hide) for this title.
     */
    private fun bindTasteOption(item: AppAdapter.Item) {
        binding.btnOptionShowTaste.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                ExpMotion.hapticTap(it)
                val fragment = context.toActivity()?.getCurrentFragment()
                if (fragment != null) {
                    com.betterstreamflix.app.taste.TasteSignalsUi.showFor(
                        fragment = fragment,
                        item = item,
                        onChanged = { dismiss() },
                        onMoreLike = { dismiss() },
                    )
                } else {
                    dismiss()
                }
            }
        }
    }

    private fun bindChannelPinOption(tvShow: TvShow) {
        val btn = binding.btnOptionChannelPin
        val isLive = com.betterstreamflix.app.utils.LiveHubCatalog.isLiveFavorite(tvShow)
        val gated = com.betterstreamflix.app.profiles.ProfileManager.isKidsActive() ||
            com.betterstreamflix.app.utils.LiveHubCatalog.isAgeGated()
        if (!isLive || tvShow.providerName.isNullOrBlank() || gated) {
            btn.visibility = View.GONE
            return
        }
        val pinned = com.betterstreamflix.app.live.ChannelPinStore.isPinned(context, tvShow)
        btn.text = context.getString(
            if (pinned) R.string.meine_sender_leiste_unpin
            else R.string.meine_sender_leiste_pin,
        )
        btn.visibility = View.VISIBLE
        btn.setOnClickListener {
            ExpMotion.hapticTap(it)
            val activity = context.toActivity() as? androidx.fragment.app.FragmentActivity
            if (activity != null) {
                val nowPinned = com.betterstreamflix.app.live.ChannelPinStripController
                    .pinFromShowOptions(activity, tvShow)
                android.widget.Toast.makeText(
                    context,
                    if (nowPinned) {
                        context.getString(R.string.meine_sender_leiste_pinned)
                    } else {
                        context.getString(R.string.meine_sender_leiste_unpinned, tvShow.title)
                    },
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
            dismiss()
        }
    }
}
