package com.dskja.betterstreamflix.tvlauncher

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.kids.KidsBedtime
import com.dskja.betterstreamflix.kids.KidsBedtimeUi
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.ui.FeaturedProviderSwitch
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.UserDataCache
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Resolves `betterstreamflix://watch` intents into player / detail navigation. */
object TvWatchDeepLinkRouter {
    private const val TAG = "TvWatchDeepLink"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun handle(activity: FragmentActivity, data: Uri?): Boolean {
        val target = TvWatchDeepLink.parse(data?.toString()) ?: return false
        // Routing works on Mobile + TV; only Channels/Watch Next *publishing* is TV-gated.
        val navHost = activity.supportFragmentManager
            .findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            ?: return false
        val navController = navHost.navController
        scope.launch {
            runCatching {
                open(activity, navController, target)
            }.onFailure {
                Log.w(TAG, "Failed to open watch deep link: ${it.message}")
            }
        }
        return true
    }

    private suspend fun open(
        context: Context,
        navController: NavController,
        target: TvWatchDeepLink.Target,
    ) {
        FeaturedProviderSwitch.switchSticky(target.provider, context)
        val provider = target.provider?.let(Provider::findByName)
            ?: UserPreferences.currentProvider
            ?: return

        when (target.kind) {
            TvWatchDeepLink.Kind.MOVIE -> openMovie(context, navController, provider, target.id)
            TvWatchDeepLink.Kind.EPISODE -> openEpisode(context, navController, provider, target.id)
            TvWatchDeepLink.Kind.TV_SHOW -> {
                withContext(Dispatchers.Main) {
                    runCatching {
                        navController.navigate(
                            R.id.tv_show,
                            Bundle().apply { putString("id", target.id) },
                        )
                    }
                }
            }
        }
    }

    private suspend fun openMovie(
        context: Context,
        navController: NavController,
        provider: Provider,
        id: String,
    ) {
        val movie = withContext(Dispatchers.IO) {
            AppDatabase.getInstanceOrNull(context)?.movieDao()?.getById(id)
                ?: UserDataCache.read(context, provider)
                    ?.continueWatchingMovies
                    ?.firstOrNull { it.id == id }
                    ?.let { cached -> UserDataCache.run { cached.toMovie() } }
                ?: UserDataCache.read(context, provider)
                    ?.recentlyWatchedMovies
                    ?.firstOrNull { it.id == id }
                    ?.let { cached -> UserDataCache.run { cached.toMovie() } }
        }
        if (movie == null) {
            withContext(Dispatchers.Main) {
                runCatching {
                    navController.navigate(
                        R.id.movie,
                        Bundle().apply { putString("id", id) },
                    )
                }
            }
            return
        }
        if (!guardPlaybackAllowed(context, movie = movie, tvShow = null)) return
        navigatePlayer(
            navController,
            id = movie.id,
            title = movie.title,
            subtitle = movie.released?.format("yyyy").orEmpty(),
            videoType = Video.Type.Movie(
                id = movie.id,
                title = movie.title,
                releaseDate = movie.released?.format("yyyy-MM-dd").orEmpty(),
                poster = movie.poster ?: movie.banner.orEmpty(),
                imdbId = movie.imdbId,
            ),
        )
    }

    private suspend fun openEpisode(
        context: Context,
        navController: NavController,
        provider: Provider,
        id: String,
    ) {
        val episode = withContext(Dispatchers.IO) {
            resolveEpisode(context, provider, id)
        }
        if (episode == null) {
            Log.w(TAG, "Episode $id not found for watch deep link")
            return
        }
        val show = episode.tvShow
        if (!guardPlaybackAllowed(context, movie = null, tvShow = show)) return
        val season = episode.season
        val subtitle = season?.takeIf { it.number != 0 }?.let {
            context.getString(
                R.string.player_subtitle_tv_show,
                it.number,
                episode.number,
                episode.title ?: context.getString(R.string.episode_number, episode.number),
            )
        } ?: context.getString(
            R.string.player_subtitle_tv_show_episode_only,
            episode.number,
            episode.title ?: context.getString(R.string.episode_number, episode.number),
        )
        navigatePlayer(
            navController,
            id = episode.id,
            title = show?.title.orEmpty(),
            subtitle = subtitle,
            videoType = Video.Type.Episode(
                id = episode.id,
                number = episode.number,
                title = episode.title,
                poster = episode.poster,
                overview = episode.overview,
                tvShow = Video.Type.Episode.TvShow(
                    id = show?.id.orEmpty(),
                    title = show?.title.orEmpty(),
                    poster = show?.poster,
                    banner = show?.banner,
                    releaseDate = show?.released?.format("yyyy-MM-dd"),
                    imdbId = show?.imdbId,
                ),
                season = Video.Type.Episode.Season(
                    number = season?.number ?: 0,
                    title = season?.title,
                ),
            ),
        )
    }

    private fun resolveEpisode(context: Context, provider: Provider, id: String): Episode? {
        val db = AppDatabase.getInstanceOrNull(context)
        val fromDb = db?.episodeDao()?.getById(id)
        val cached = UserDataCache.read(context, provider)
            ?.continueWatchingEpisodes
            ?.firstOrNull { it.id == id }

        if (fromDb != null) {
            val showId = fromDb.tvShow?.id ?: cached?.tvShowId
            val resolvedShow = showId?.let { sid ->
                db?.tvShowDao()?.getById(sid)
                    ?: cached?.let {
                        TvShow(
                            id = it.tvShowId.orEmpty(),
                            title = it.tvShowTitle.orEmpty(),
                            poster = it.tvShowPoster,
                            banner = it.tvShowBanner,
                        )
                    }
                    ?: fromDb.tvShow
            } ?: fromDb.tvShow

            val season = fromDb.season?.also { season ->
                if (season.title.isNullOrBlank()) {
                    season.title = cached?.seasonTitle
                }
                if (season.number == 0) {
                    cached?.seasonNumber?.let { season.number = it }
                }
            } ?: cached?.seasonId?.let {
                Season(
                    id = it,
                    number = cached.seasonNumber ?: 0,
                    title = cached.seasonTitle,
                )
            }

            return fromDb.copy(
                title = fromDb.title ?: cached?.title,
                poster = fromDb.poster ?: cached?.poster,
                overview = fromDb.overview ?: cached?.overview,
                tvShow = resolvedShow,
                season = season,
            ).apply { merge(fromDb) }
        }

        return cached?.let { UserDataCache.run { it.toEpisode() } }
    }

    private suspend fun navigatePlayer(
        navController: NavController,
        id: String,
        title: String,
        subtitle: String,
        videoType: Video.Type,
    ) {
        withContext(Dispatchers.Main) {
            runCatching {
                navController.navigate(
                    R.id.action_global_player,
                    Bundle().apply {
                        putString("id", id)
                        putString("title", title)
                        putString("subtitle", subtitle)
                        putSerializable("videoType", videoType)
                    },
                )
            }.onFailure {
                Log.w(TAG, "navigate player failed: ${it.message}")
            }
        }
    }

    /**
     * Age + bedtime pre-check before deep-link player open (KF-003).
     * Fail closed when kids filtering is active and metadata is missing.
     */
    private suspend fun guardPlaybackAllowed(
        context: Context,
        movie: Movie?,
        tvShow: TvShow?,
    ): Boolean {
        if (KidsBedtime.shouldBlockEnteringPlayer()) {
            withContext(Dispatchers.Main) {
                val activity = context as? FragmentActivity
                if (activity != null) {
                    KidsBedtimeUi.blockPlayerEntry(activity)
                } else {
                    Toast.makeText(
                        context,
                        R.string.kids_bedtime_new_playback_blocked,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            return false
        }
        if (!ParentalControlUtils.isFilteringActive()) return true
        val allowed = withContext(Dispatchers.IO) {
            when {
                movie != null -> ParentalControlUtils.isAllowed(movie)
                tvShow != null -> ParentalControlUtils.isAllowed(tvShow)
                // Orphan episode under kids / parental filter — fail closed.
                else -> false
            }
        }
        if (!allowed) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    R.string.parental_content_blocked,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            Log.w(TAG, "Watch deep link blocked by age / kids filter")
            return false
        }
        return true
    }
}
