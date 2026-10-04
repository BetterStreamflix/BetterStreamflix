package com.dskja.betterstreamflix.fragments.tv_show

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.utils.ArtworkRepair
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class TvShowViewModel(
    id: String,
    private val database: AppDatabase,
    private val fallbackPoster: String? = null,
    private val fallbackBanner: String? = null,
) : ViewModel() {

    private fun episodeSeasonKey(episode: Episode): String? {
        return episode.id.substringBeforeLast("/", "")
            .takeIf { it.isNotBlank() }
    }

    private fun episodesForSeason(episodes: List<Episode>, season: Season): List<Episode> {
        return episodes
            .filter { episode ->
                val episodeSeason = episode.season
                val seasonKey = episodeSeasonKey(episode)
                seasonKey == season.id ||
                    episodeSeason?.id == season.id ||
                    (
                        episodeSeason?.number != null &&
                            episodeSeason.number != 0 &&
                            episodeSeason.number == season.number
                        )
            }
            .sortedBy { it.number }
            .onEach { episode ->
                episode.season = season
            }
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val episodes = database.episodeDao().getByTvShowIdAsFlow(id).first()
                    state.tvShow.seasons.onEach { season ->
                        season.episodes = episodesForSeason(episodes, season)
                    }

                    if (episodes.isEmpty() && state.tvShow.seasons.isNotEmpty()) {
                        val firstSeason = state.tvShow.seasons.firstOrNull { it.number != 0 }
                            ?: state.tvShow.seasons.first()
                        getSeason(TvShow(id, ""), firstSeason)
                    } else {
                        val season = state.tvShow.seasons.let { seasons ->
                            seasons
                                .lastOrNull { season ->
                                    season.episodes.lastOrNull()?.isWatched == true ||
                                            season.episodes.any { it.isWatched }
                                }?.let { season ->
                                    if (season.episodes.lastOrNull()?.isWatched == true) {
                                        val next = seasons.getOrNull(seasons.indexOf(season) + 1)
                                        next ?: season
                                    } else season
                                }
                                ?: seasons.firstOrNull { season ->
                                    season.episodes.isEmpty() ||
                                            season.episodes.lastOrNull()?.isWatched == false
                                }
                        }

                        val episodeIndex = episodes
                            .filter { it.watchHistory != null }
                            .sortedByDescending { it.watchHistory?.lastEngagementTimeUtcMillis }
                            .indexOfFirst { it.watchHistory != null }.takeIf { it != -1 }
                            ?: season?.episodes?.indexOfLast { it.isWatched }
                                ?.takeIf { it != -1 && it + 1 < episodes.size }
                                ?.let { it + 1 }

                        if (
                            episodeIndex == null &&
                            season != null &&
                            (season.episodes.isEmpty() || state.tvShow.seasons.lastOrNull() == season)
                        ) {
                            getSeason(state.tvShow, season)
                        }
                    }
                }
                else -> {}
            }
            emit(state)
        },
        database.tvShowDao().getByIdAsFlow(id).catch { e ->
            if (e is CancellationException) throw e
            Log.w("TvShowViewModel", "tvShowDb flow failed after provider switch", e)
            emit(null)
        },
        database.episodeDao().getByTvShowIdAsFlow(id).catch { e ->
            if (e is CancellationException) throw e
            Log.w("TvShowViewModel", "episodesDb flow failed after provider switch", e)
            emit(emptyList())
        },
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.tvShow.recommendations
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else if (!database.isOpen) {
                        emit(emptyList())
                    } else {
                        emitAll(
                            database.movieDao().getByIds(movies.map { it.id }).catch { e ->
                                if (e is CancellationException) throw e
                                emit(emptyList())
                            }
                        )
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        },
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val tvShows = state.tvShow.recommendations
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else if (!database.isOpen) {
                        emit(emptyList())
                    } else {
                        emitAll(
                            database.tvShowDao().getByIds(tvShows.map { it.id }).catch { e ->
                                if (e is CancellationException) throw e
                                emit(emptyList())
                            }
                        )
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, tvShowDb, episodesDb, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }
                State.SuccessLoading(
                    tvShow = state.tvShow.copy(
                        seasons = (state.tvShow.seasons
                            .takeIf { seasons -> seasons.flatMap { it.episodes } != episodesDb }
                            ?.map { season ->
                                season.copy(
                                    episodes = episodesForSeason(episodesDb, season)
                                )
                            }
                            ?: state.tvShow.seasons)
                            .sortedWith(::compareSeasonsForDisplay),
                        recommendations = state.tvShow.recommendations.map { show ->
                            when (show) {
                                is Movie -> moviesById[show.id]
                                    ?.takeIf { !show.isSame(it) }
                                    ?.let { show.copy().merge(it) }
                                    ?: show
                                is TvShow -> tvShowsById[show.id]
                                    ?.takeIf { !show.isSame(it) }
                                    ?.let { show.copy().merge(it) }
                                    ?: show
                            }
                        },
                    ).also { tvShow ->
                        tvShowDb?.let { tvShow.merge(it) }
                    }
                )
            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val tvShow: TvShow) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    private val _seasonState = MutableStateFlow<SeasonState>(SeasonState.Loading)
    val seasonState: StateFlow<SeasonState> = _seasonState.asStateFlow()

    /** In-page Episodes tab: remembered season for this show instance (not process-global). */
    var selectedSeasonId: String? = null

    private val loadingSeasonIds = mutableSetOf<String>()
    private val failedSeasonIds = mutableSetOf<String>()
    /** Seasons whose episode fetch already finished, including a legitimate empty list. */
    private val settledSeasonIds = mutableSetOf<String>()

    fun beginSeasonEpisodeLoad(seasonId: String): Boolean = loadingSeasonIds.add(seasonId)

    fun endSeasonEpisodeLoad(seasonId: String) {
        loadingSeasonIds.remove(seasonId)
    }

    fun isSeasonEpisodeLoading(seasonId: String): Boolean = loadingSeasonIds.contains(seasonId)

    fun isSeasonEpisodeFailed(seasonId: String): Boolean = failedSeasonIds.contains(seasonId)

    fun markSeasonEpisodeFailure(seasonId: String) {
        loadingSeasonIds.remove(seasonId)
        failedSeasonIds.add(seasonId)
    }

    fun clearSeasonEpisodeFailure(seasonId: String) {
        loadingSeasonIds.remove(seasonId)
        failedSeasonIds.remove(seasonId)
    }

    fun markSeasonEpisodesSettled(seasonId: String) {
        loadingSeasonIds.remove(seasonId)
        failedSeasonIds.remove(seasonId)
        settledSeasonIds.add(seasonId)
    }

    fun isSeasonEpisodesSettled(seasonId: String): Boolean = settledSeasonIds.contains(seasonId)

    fun clearSeasonEpisodesSettled(seasonId: String) {
        settledSeasonIds.remove(seasonId)
    }

    sealed class SeasonState {
        data object Loading :  SeasonState()
        data class SuccessLoading(
            val tvShow: TvShow,
            val season: Season,
            val episodes: List<Episode>,
        ) : SeasonState()
        data class FailedLoading(
            val season: Season,
            val error: Exception,
        ) : SeasonState()
    }

    init {
        getTvShow(id)
    }

    private fun compareSeasonsForDisplay(season1: Season, season2: Season): Int {
        val key1 = season1.displaySortKey()
        val key2 = season2.displaySortKey()
        return compareValuesBy(key1, key2, SeasonSortKey::group, SeasonSortKey::seasonNumber, SeasonSortKey::partNumber)
    }

    private fun Season.displaySortKey(): SeasonSortKey {
        val title = title.orEmpty()
        val seasonMatch = Regex("""\bSeason\s+(\d+)\b""", RegexOption.IGNORE_CASE).find(title)
        val partMatch = Regex("""\bPart\s+(\d+)\b""", RegexOption.IGNORE_CASE).find(title)
        val isNumberedSeason = title.isBlank() || seasonMatch != null
        val seasonNumber = seasonMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: number
        val partNumber = partMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        return when {
            !isNumberedSeason -> SeasonSortKey(group = 1, seasonNumber = seasonNumber.takeIf { it != 0 } ?: Int.MAX_VALUE, partNumber = partNumber)
            seasonNumber == 0 -> SeasonSortKey(group = 2, seasonNumber = Int.MAX_VALUE, partNumber = partNumber)
            else -> SeasonSortKey(group = 0, seasonNumber = seasonNumber, partNumber = partNumber)
        }
    }

    private data class SeasonSortKey(
        val group: Int,
        val seasonNumber: Int,
        val partNumber: Int,
    )


    fun getTvShow(id: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val provider = UserPreferences.currentProvider
                ?: throw IllegalStateException("No provider selected")
            val tvShow = ProviderSmoke.withProviderTimeout(
                timeoutMs = ProviderSmoke.DETAIL_TIMEOUT_MS,
                label = "getTvShow(${provider.name})",
            ) {
                com.dskja.betterstreamflix.utils.ShowLookup.tvShow(provider, id)
            }
            val pluginEnriched = runCatching {
                com.dskja.betterstreamflix.platform.plugins.PluginManager
                    .enrichTvShow(provider, tvShow)
            }.getOrDefault(tvShow)
            val enriched = runCatching {
                ProviderSmoke.withProviderTimeout(
                    timeoutMs = ProviderSmoke.TMDB_ENRICH_TIMEOUT_MS,
                    label = "enrichTvShowDetail",
                ) {
                    com.dskja.betterstreamflix.utils.TmdbUtils.enrichTvShowDetail(pluginEnriched)
                }
            }.getOrDefault(pluginEnriched)

            if (!ArtworkRepair.isRemoteArtworkUrl(enriched.poster) && ArtworkRepair.isRemoteArtworkUrl(fallbackPoster)) {
                enriched.poster = fallbackPoster
            }
            if (!ArtworkRepair.isRemoteArtworkUrl(enriched.banner) && ArtworkRepair.isRemoteArtworkUrl(fallbackBanner)) {
                enriched.banner = fallbackBanner
            }
            if (!ArtworkRepair.isRemoteArtworkUrl(enriched.banner) && ArtworkRepair.isRemoteArtworkUrl(enriched.poster)) {
                enriched.banner = enriched.poster
            }

            database.tvShowDao().getById(enriched.id)?.let { tvShowDb ->
                enriched.merge(tvShowDb)
            }
            val orderedSeasons = enriched.seasons.sortedWith(::compareSeasonsForDisplay)
            val orderedTvShow = enriched.copy(seasons = orderedSeasons)

            database.tvShowDao().insert(orderedTvShow)

            val tvShowCopy = orderedTvShow.copy()
            orderedTvShow.seasons.forEach { season ->
                season.tvShow = tvShowCopy
            }
            database.seasonDao().insertAll(orderedTvShow.seasons)

            _state.emit(State.SuccessLoading(orderedTvShow))
        } catch (e: Exception) {
            Log.e("TvShowViewModel", "getTvShow: ", e)
            val fallback = metadataOnlyFallback(id)
            if (fallback != null) {
                Log.w("TvShowViewModel", "Showing metadata-only page for $id after a provider failure")
                _state.emit(State.SuccessLoading(fallback))
            } else {
                _state.emit(State.FailedLoading(e))
            }
        }
    }

    /**
     * Provider outages (HTTP 5xx) should still render the page: fall back to the
     * cached row or TMDb metadata, with no episodes attached.
     */
    private suspend fun metadataOnlyFallback(id: String): TvShow? {
        val language = UserPreferences.currentProvider?.language
        val cached = runCatching { database.tvShowDao().getById(id) }.getOrNull()
        val base = cached
            ?: id.toIntOrNull()?.let {
                runCatching {
                    com.dskja.betterstreamflix.utils.TmdbUtils.getTvShowById(it, language)
                }.getOrNull()
            }
            ?: com.dskja.betterstreamflix.utils.ShowLookup.humanizeSlug(id)?.let { title ->
                runCatching {
                    com.dskja.betterstreamflix.utils.TmdbUtils.getTvShow(
                        title,
                        year = com.dskja.betterstreamflix.utils.ShowLookup.yearFromSlug(id),
                        language = language,
                    )
                }.getOrNull()
            }
            ?: return null
        return runCatching {
            com.dskja.betterstreamflix.utils.TmdbUtils.enrichTvShowDetail(base, language)
        }.getOrDefault(base)
    }

    private var ribbonsPrefetchStarted = false

    /**
     * Fills empty season episode lists (bounded) so TV season posters can show
     * fully-watched ribbons without opening each season screen.
     */
    fun prefetchSeasonEpisodesForRibbons(tvShow: TvShow, limit: Int = 6) {
        if (ribbonsPrefetchStarted) return
        ribbonsPrefetchStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            val provider = UserPreferences.currentProvider ?: return@launch
            val targets = tvShow.seasons
                .filter { it.number != 0 && it.episodes.isEmpty() }
                .take(limit)
            if (targets.isEmpty()) return@launch
            var loadedAny = false
            for (season in targets) {
                runCatching {
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
                    season.episodes = episodes
                    tvShow.seasons.firstOrNull { it.id == season.id }?.episodes = episodes
                    loadedAny = true
                }
            }
            if (!loadedAny) return@launch
            val current = (_state.value as? State.SuccessLoading)?.tvShow
            if (current?.id == tvShow.id) {
                _state.emit(State.SuccessLoading(current))
            }
        }
    }

    fun loadSeasonEpisodes(tvShow: TvShow, season: Season) = getSeason(tvShow, season)

    private fun getSeason(tvShow: TvShow, season: Season) = viewModelScope.launch(Dispatchers.IO) {
        _seasonState.emit(SeasonState.Loading)

        try {
            val episodes = UserPreferences.currentProvider?.getEpisodesBySeason(season.id)
                ?: throw IllegalStateException("No provider selected")
            val ids = episodes.map { it.id }
            val episodeMap = episodes.associateBy { it.id }

            ids.chunked(400).forEach { chunk ->
                database.episodeDao()
                    .getByIds(chunk)
                    .forEach { episodeDb ->
                        episodeMap[episodeDb.id]?.merge(episodeDb)
                    }
            }

            episodes.forEach { episode ->
                episode.tvShow = tvShow
                episode.season = season
            }

            database.episodeDao().insertAll(episodes)
            markSeasonEpisodesSettled(season.id)

            _seasonState.emit(SeasonState.SuccessLoading(tvShow, season, episodes))
        } catch (e: Exception) {
            Log.e("TvShowViewModel", "getSeason: ", e)
            markSeasonEpisodeFailure(season.id)
            _seasonState.emit(SeasonState.FailedLoading(season, e))
        }
    }
}
