package com.dskja.betterstreamflix.fragments.movie

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.BetterStreamflixApp
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.utils.EpisodeManager
import com.dskja.betterstreamflix.utils.ShowLookup
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class MovieViewModel(
    id: String,
    @Suppress("UNUSED_PARAMETER") database: AppDatabase? = null,
) : ViewModel() {

    private fun liveDb(): AppDatabase =
        AppDatabase.getInstance(BetterStreamflixApp.instance.applicationContext)

    private val _state = MutableStateFlow<State>(State.Loading)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest {
            val db = runCatching { liveDb() }.getOrNull()
            if (db == null || !db.isOpen) emit(null)
            else emitAll(
                db.movieDao().getByIdAsFlow(id).catch { e ->
                    if (e is CancellationException) throw e
                    Log.w("MovieViewModel", "movieDb flow failed after provider switch", e)
                    emit(null)
                }
            )
        },
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.movie.recommendations
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        val db = runCatching { liveDb() }.getOrNull()
                        if (db == null || !db.isOpen) emit(emptyList())
                        else emitAll(
                            db.movieDao().getByIds(movies.map { it.id }).catch { e ->
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
                    val tvShows = state.movie.recommendations
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        val db = runCatching { liveDb() }.getOrNull()
                        if (db == null || !db.isOpen) emit(emptyList())
                        else emitAll(
                            db.tvShowDao().getByIds(tvShows.map { it.id }).catch { e ->
                                if (e is CancellationException) throw e
                                emit(emptyList())
                            }
                        )
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, movieDb, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }
                State.SuccessLoading(
                    movie = state.movie.copy(
                        recommendations = state.movie.recommendations.map { show ->
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
                    ).also { movie ->
                        movieDb?.let { movie.merge(it) }
                    }
                )
            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val movie: Movie) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        EpisodeManager.clearEpisodes()
        getMovie(id)
    }

    fun getMovie(id: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val provider = UserPreferences.currentProvider
                ?: throw IllegalStateException("No provider selected")
            val movie = ProviderSmoke.withProviderTimeout(
                timeoutMs = ProviderSmoke.DETAIL_TIMEOUT_MS,
                label = "getMovie(${provider.name})",
            ) {
                com.dskja.betterstreamflix.utils.ShowLookup.movie(provider, id)
            }
            val pluginEnriched = runCatching {
                com.dskja.betterstreamflix.platform.plugins.PluginManager
                    .enrichMovie(provider, movie)
            }.getOrDefault(movie)
            val enriched = runCatching {
                ProviderSmoke.withProviderTimeout(
                    timeoutMs = ProviderSmoke.TMDB_ENRICH_TIMEOUT_MS,
                    label = "enrichMovieDetail",
                ) {
                    com.dskja.betterstreamflix.utils.TmdbUtils.enrichMovieDetail(pluginEnriched)
                }
            }.getOrDefault(pluginEnriched)

            val db = runCatching { liveDb() }.getOrNull()
            db?.movieDao()?.getById(id)?.let { movieDb ->
                enriched.merge(movieDb)
            }
            db?.movieDao()?.insert(enriched)

            _state.emit(State.SuccessLoading(enriched))
        } catch (e: Exception) {
            Log.e("MovieViewModel", "getMovie: ", e)
            val fallback = metadataOnlyFallback(id)
            if (fallback != null) {
                Log.w("MovieViewModel", "Showing metadata-only page for $id after a provider failure")
                _state.emit(State.SuccessLoading(fallback))
            } else {
                _state.emit(State.FailedLoading(e))
            }
        }
    }

    /**
     * Provider outages (HTTP 5xx) should still render the page: fall back to the
     * cached row or TMDb metadata, with no servers attached.
     */
    private suspend fun metadataOnlyFallback(id: String): Movie? {
        val language = UserPreferences.currentProvider?.language
        val cached = runCatching { liveDb().movieDao().getById(id) }.getOrNull()
        val base = cached
            ?: id.toIntOrNull()?.let {
                runCatching {
                    com.dskja.betterstreamflix.utils.TmdbUtils.getMovieById(it, language)
                }.getOrNull()
            }
            ?: ShowLookup.humanizeSlug(id)?.let { title ->
                runCatching {
                    com.dskja.betterstreamflix.utils.TmdbUtils.getMovie(
                        title,
                        year = ShowLookup.yearFromSlug(id),
                        language = language,
                    )
                }.getOrNull()
            }
            ?: return null
        return runCatching {
            com.dskja.betterstreamflix.utils.TmdbUtils.enrichMovieDetail(base, language)
        }.getOrDefault(base)
    }
}
