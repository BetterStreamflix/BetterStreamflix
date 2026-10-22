package com.betterstreamflix.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.bumptech.glide.Glide
import com.betterstreamflix.app.R
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.databinding.DialogShowOptionsTvBinding
import com.betterstreamflix.app.download.ui.DownloadOptionsController
import com.betterstreamflix.app.fragments.home.HomeTvFragment
import com.betterstreamflix.app.fragments.home.HomeTvFragmentDirections
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.utils.UserDataCache
import com.betterstreamflix.app.utils.UserPreferences
import com.betterstreamflix.app.utils.format
import com.betterstreamflix.app.utils.getCurrentFragment
import com.betterstreamflix.app.utils.loadMoviePoster
import com.betterstreamflix.app.utils.loadTvShowPoster
import com.betterstreamflix.app.utils.toActivity
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.sync.FavoriteMutations
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShowOptionsTvDialog(
    context: Context,
    show: AppAdapter.Item,
) : Dialog(context) {

    private val binding = DialogShowOptionsTvBinding.inflate(LayoutInflater.from(context))

    private val database: AppDatabase?
        get() = AppDatabase.getInstanceOrNull(context)

    private fun checkProviderAndRun(show: AppAdapter.Item, action: () -> Unit) {
        val providerName = when(show){
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

    private fun styleActiveOption(btn: View, active: Boolean) {
    }

    private fun styleSubtitlePill() {
    }

    init {
        setContentView(binding.root)

        binding.btnOptionCancel.setOnClickListener {
            dismiss()
        }

        when (show) {
            is Episode -> displayEpisode(show)
            is Movie -> displayMovie(show)
            is TvShow -> displayTvShow(show)
        }

        applyActionContentDescriptions()
        focusFirstVisibleAction()

        window?.attributes = window?.attributes?.also { param ->
            param.gravity = Gravity.END
        }
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.35).toInt(),
            context.resources.displayMetrics.heightPixels
        )
    }

    private fun actionButtons(): List<android.widget.TextView> = listOf(
        binding.btnOptionEpisodeOpenTvShow,
        binding.btnOptionShowFavorite,
        binding.btnOptionChannelPin,
        binding.btnOptionShowWatched,
        binding.btnOptionShowDownload,
        binding.btnOptionShowTaste,
        binding.btnOptionEpisodeNote,
        binding.btnOptionEpisodeMarkAllPreviousWatched,
        binding.btnOptionProgramClear,
        binding.btnOptionRecentlyWatchedClear,
        binding.btnOptionCancel,
    )

    private fun applyActionContentDescriptions() {
        actionButtons().forEach { btn ->
            if (btn.visibility == View.VISIBLE) {
                val label = btn.text?.toString().orEmpty()
                if (label.isNotBlank()) {
                    btn.contentDescription = label
                }
            }
        }
    }

    private fun focusFirstVisibleAction() {
        val first = actionButtons().firstOrNull { it.visibility == View.VISIBLE } ?: return
        first.post { first.requestFocus() }
    }

    private fun displayEpisode(episode: Episode) {
        val provider = UserPreferences.currentProvider

        Glide.with(context)
            .load(episode.poster ?: episode.tvShow?.poster)
            .fallback(R.drawable.glide_fallback_cover)
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
        styleSubtitlePill()

        binding.btnOptionEpisodeOpenTvShow.apply {
            setOnClickListener {
                when (val fragment = context.toActivity()?.getCurrentFragment()) {
                    is HomeTvFragment -> episode.tvShow?.let { tvShow ->
                        NavHostFragment.findNavController(fragment).navigate(
                            HomeTvFragmentDirections.actionHomeToTvShow(
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
                is HomeTvFragment -> View.VISIBLE
                else -> View.GONE
            }

            requestFocus()
        }

        binding.btnOptionShowFavorite.visibility = View.GONE
        binding.btnOptionChannelPin.visibility = View.GONE

        binding.btnOptionShowWatched.apply {
            setOnClickListener {
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
            styleActiveOption(this, episode.isWatched)
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
                    val activity = context.toActivity() as? Activity
                    if (activity != null) {
                        DownloadOptionsController.enqueueEpisode(context, activity, episode)
                    }
                    dismiss()
                }
                visibility = View.VISIBLE
            }
        }

        binding.btnOptionEpisodeNote.apply {
            text = EpisodeNotesUi.optionLabel(context, episode.id)
            visibility = View.VISIBLE
            setOnClickListener {
                EpisodeNotesUi.showEditor(context, episode)
                dismiss()
            }
        }

        binding.btnOptionEpisodeMarkAllPreviousWatched.apply {
            setOnClickListener {
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
        binding.btnOptionShowTaste.visibility = View.GONE
    }

    private fun displayMovie(movie: Movie) {
        binding.ivOptionsShowPoster.loadMoviePoster(movie) {
            fallback(R.drawable.glide_fallback_cover)
            fitCenter()
        }

        binding.tvOptionsShowTitle.text = movie.title

        binding.tvShowSubtitle.text = movie.released?.format("yyyy")
        styleSubtitlePill()

        binding.btnOptionEpisodeOpenTvShow.visibility = View.GONE
        binding.btnOptionChannelPin.visibility = View.GONE

        val freshMovie = database?.movieDao()?.getById(movie.id) ?: movie
        val recentlyWatchedAt = freshMovie.lastPlayedAtMillis ?: movie.lastPlayedAtMillis

        binding.btnOptionShowFavorite.apply {
            setOnClickListener {
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
            styleActiveOption(this, freshMovie.isFavorite)
            visibility = View.VISIBLE

            requestFocus()
        }

        // Taste Graph overflow from Show Options (Home/detail parity with Search).
        binding.ivOptionsShowPoster.setOnLongClickListener {
            showTasteOverflow(freshMovie)
            true
        }

        bindTasteOption(freshMovie)

        binding.btnOptionShowWatched.apply {
            setOnClickListener {
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
            styleActiveOption(this, freshMovie.isWatched)
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

        binding.btnOptionProgramClear.apply {
            setOnClickListener {
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
            fallback(R.drawable.glide_fallback_cover)
            fitCenter()
        }

        binding.tvOptionsShowTitle.text = tvShow.title

        binding.tvShowSubtitle.text = tvShow.released?.format("yyyy")
        styleSubtitlePill()

        binding.btnOptionEpisodeOpenTvShow.visibility = View.GONE

        val freshTvShow = database?.tvShowDao()?.getById(tvShow.id) ?: tvShow
        val recentlyWatchedAt = freshTvShow.lastPlayedAtMillis ?: tvShow.lastPlayedAtMillis

        binding.btnOptionShowFavorite.apply {
            setOnClickListener {
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

            text = when {
                freshTvShow.isFavorite -> context.getString(R.string.option_show_unfavorite)
                else -> context.getString(R.string.option_show_favorite)
            }
            styleActiveOption(this, freshTvShow.isFavorite)
            visibility = View.VISIBLE

            requestFocus()
        }

        binding.ivOptionsShowPoster.setOnLongClickListener {
            showTasteOverflow(freshTvShow)
            true
        }

        bindTasteOption(freshTvShow)

        bindChannelPinOption(freshTvShow)

        binding.btnOptionShowWatched.visibility = View.GONE
        binding.btnOptionShowDownload.visibility = View.GONE
        binding.btnOptionEpisodeNote.visibility = View.GONE
        binding.btnOptionEpisodeMarkAllPreviousWatched.visibility = View.GONE

        binding.btnOptionProgramClear.visibility = View.GONE

        binding.btnOptionRecentlyWatchedClear.apply {
            setOnClickListener {
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

    private fun bindTasteOption(item: AppAdapter.Item) {
        binding.btnOptionShowTaste.apply {
            visibility = View.VISIBLE
            setOnClickListener { showTasteOverflow(item) }
        }
    }

    private fun showTasteOverflow(item: AppAdapter.Item): Boolean {
        val activity = context.toActivity() ?: return false
        val fragment = activity.supportFragmentManager.fragments
            .flatMap { it.childFragmentManager.fragments + it }
            .firstOrNull { it.isVisible && it.view != null }
            ?: return false
        com.betterstreamflix.app.taste.TasteSignalsUi.showFor(
            fragment = fragment,
            item = item,
            onChanged = { dismiss() },
            onMoreLike = { dismiss() },
        )
        return true
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
