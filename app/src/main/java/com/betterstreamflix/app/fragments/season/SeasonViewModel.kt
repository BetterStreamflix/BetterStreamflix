package com.betterstreamflix.app.fragments.season

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Season
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.providers.ProviderSmoke
import com.betterstreamflix.app.utils.EpisodeManager
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class SeasonViewModel(
    private val seasonId: String,
    private val tvShowId: String,
    private val database: AppDatabase?,
    initialSeasonNumber: Int = 0,
) : ViewModel() {
    var seasonNumber: Int = resolveSeasonNumber(seasonId, initialSeasonNumber)
    var tvShowTitle = ""
    private val _state = MutableStateFlow<State>(State.LoadingEpisodes)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = run {
        val db = database
        if (db == null) {
            flowOf(State.FailedLoadingEpisodes(Exception("No provider selected")))
        } else {
            combine(
                _state,
                _state.transformLatest { state ->
                    when (state) {
                        is State.SuccessLoadingEpisodes -> {
                            db.episodeDao()
                                .getBySeasonIdAsFlow(seasonId)
                                .collect { emit(it) }
                        }
                        else -> emit(emptyList())
                    }
                },
                db.tvShowDao().getByIdAsFlow(tvShowId),
                db.seasonDao().getByIdAsFlow(seasonId),
            ) { state, episodesDb, tvShow, season ->
                season?.number?.let { seasonNumber = it }
                tvShow?.title?.let { tvShowTitle = it }

                when (state) {
                    is State.SuccessLoadingEpisodes -> {
                        State.SuccessLoadingEpisodes(
                            episodes = state.episodes.map { episode ->
                                episodesDb.find { it.id == episode.id }
                                    ?.takeIf { !episode.isSame(it) }
                                    ?.let { episode.copy().merge(it) }
                                    ?: episode
                            }.sortedBy { it.number }.onEach {
                                it.tvShow = tvShow ?: it.tvShow
                                it.season = season ?: it.season
                            }
                        )
                    }
                    else -> state
                }
            }
        }
    }

    sealed class State {
        data object LoadingEpisodes : State()
        data class SuccessLoadingEpisodes(val episodes: List<Episode>) : State()
        data class FailedLoadingEpisodes(val error: Exception) : State()
    }

    init {
        getSeasonEpisodes(seasonId)
    }

    fun getSeasonEpisodes(seasonId: String) = viewModelScope.launch(Dispatchers.IO) {
        val db = database
        if (db == null) {
            _state.emit(State.FailedLoadingEpisodes(Exception("No provider selected")))
            return@launch
        }

        _state.emit(State.LoadingEpisodes)

        try {
            val provider = UserPreferences.currentProvider
                ?: throw Exception(com.betterstreamflix.app.BetterStreamflixApp.instance.getString(com.betterstreamflix.app.R.string.error_no_provider_selected))
            val episodes = ProviderSmoke.withProviderTimeout(
                timeoutMs = ProviderSmoke.DETAIL_TIMEOUT_MS,
                label = "getEpisodesBySeason(${provider.name})",
            ) {
                provider.getEpisodesBySeason(seasonId)
            }.sortedBy { it.number }
            val ids = episodes.map { it.id }
            val episodeMap = episodes.associateBy { it.id }

            ids.chunked(400).forEach { chunk ->
                db.episodeDao()
                    .getByIds(chunk)
                    .forEach { episodeDb ->
                        episodeMap[episodeDb.id]?.merge(episodeDb)
                    }
            }

            val existingSeason = db.seasonDao().getById(seasonId)
            seasonNumber = existingSeason?.number
                ?: resolveSeasonNumber(seasonId, seasonNumber)

            val tvShow = db.tvShowDao().getById(tvShowId)
                ?: TvShow(id = tvShowId)
            val season = Season(
                id = seasonId,
                number = seasonNumber,
                title = existingSeason?.title,
                poster = existingSeason?.poster,
            ).apply {
                this.tvShow = tvShow
            }
            episodes.forEach { episode ->
                episode.tvShow = tvShow
                episode.season = season
            }

            // Fill blank episode titles/overviews from TMDb when the provider omitted them.
            if (
                episodes.any { it.overview.isNullOrBlank() || it.title.isNullOrBlank() } &&
                !tvShow.tmdbId.isNullOrBlank()
            ) {
                runCatching {
                    val tmdbEps = com.betterstreamflix.app.utils.TmdbUtils.getEpisodesBySeason(
                        tvShowId = tvShow.tmdbId!!,
                        seasonNumber = seasonNumber,
                    )
                    val byNumber = tmdbEps.associateBy { it.number }
                    episodes.forEach { ep ->
                        val tmdb = byNumber[ep.number] ?: return@forEach
                        if (ep.overview.isNullOrBlank()) {
                            tmdb.overview?.takeIf { it.isNotBlank() }?.let { ep.overview = it }
                        }
                        if (ep.title.isNullOrBlank()) {
                            tmdb.title?.takeIf { it.isNotBlank() }?.let { ep.title = it }
                        }
                    }
                }
            }

            db.seasonDao().insert(season)
            db.episodeDao().insertAll(episodes)

            EpisodeManager.addEpisodes(
                EpisodeManager.convertToVideoTypeEpisodes(episodes, db, seasonNumber)
            )
            _state.emit(State.SuccessLoadingEpisodes(episodes))
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("SeasonViewModel", "getSeasonEpisodes: ", e)
            _state.emit(State.FailedLoadingEpisodes(e as? Exception ?: Exception(e)))
        }
    }

    companion object {
        fun resolveSeasonNumber(seasonId: String, fallback: Int): Int {
            val parsed = seasonId.substringAfterLast('-', missingDelimiterValue = "")
                .toIntOrNull()
            return parsed ?: fallback
        }
    }
}
