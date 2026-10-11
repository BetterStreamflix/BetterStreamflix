package com.dskja.betterstreamflix.watchlistweek

import android.content.Context
import android.util.Log
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.utils.TMDb3
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale

/**
 * Builds the Home “New this week” shelf from My List (favorite) series air dates.
 */
object WatchlistWeekBuilder {
    private const val TAG = "WatchlistWeek"
    private const val PARALLELISM = 3
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L // 6h

    private data class CacheEntry(
        val builtAtMs: Long,
        val fingerprint: String,
        val category: Category?,
    )

    @Volatile
    private var cache: CacheEntry? = null

    fun invalidateCache() {
        cache = null
    }

    suspend fun build(
        context: Context,
        favoriteTvShows: List<TvShow>,
        today: LocalDate = LocalDate.now(),
        locale: Locale = Locale.getDefault(),
        nowMs: Long = System.currentTimeMillis(),
    ): Category? = withContext(Dispatchers.IO) {
        if (!UserPreferences.showWatchlistWeek) return@withContext null
        val shows = favoriteTvShows
            .filter { !LiveHubCatalog.isLiveFavorite(it) }
            .sortedByDescending { it.favoritedAtMillis ?: 0L }
            .take(WatchlistWeekRules.MAX_SHOWS)
        if (shows.isEmpty()) return@withContext null

        val fingerprint = shows.joinToString("|") {
            "${it.providerName}:${it.id}:${it.tmdbId}:${it.favoritedAtMillis}"
        } + "|$today"
        cache?.let { hit ->
            if (hit.fingerprint == fingerprint && nowMs - hit.builtAtMs < CACHE_TTL_MS) {
                return@withContext hit.category
            }
        }

        val week = WatchlistWeekRules.weekBounds(today, locale)
        val tips = collectTips(shows, week)
        val flat = WatchlistWeekRules.flatten(WatchlistWeekRules.bucket(tips, week, locale))
            .take(WatchlistWeekRules.MAX_EPISODES)
        val category = if (flat.size < WatchlistWeekRules.MIN_SHELF_SIZE) {
            null
        } else {
            val episodes = flat.map { tip -> tip.toEpisode(locale) }
            Category(name = Category.NEW_THIS_WEEK, list = episodes)
        }
        cache = CacheEntry(builtAtMs = nowMs, fingerprint = fingerprint, category = category)
        category
    }

    private suspend fun collectTips(
        shows: List<TvShow>,
        week: ClosedRange<LocalDate>,
    ): List<WatchlistWeekRules.AirTip> = coroutineScope {
        val gate = Semaphore(
            com.dskja.betterstreamflix.ui.StickEnergyMode.providerParallelism(
                default = PARALLELISM,
            ),
        )
        shows.map { show ->
            async {
                gate.withPermit {
                    runCatching { tipsForShow(show, week) }
                        .onFailure { Log.w(TAG, "week tips failed for ${show.title}", it) }
                        .getOrElse { emptyList() }
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun tipsForShow(
        show: TvShow,
        week: ClosedRange<LocalDate>,
    ): List<WatchlistWeekRules.AirTip> {
        val tmdbTips = tipsFromTmdb(show, week)
        if (tmdbTips.isNotEmpty()) return hydrateProviderIds(show, tmdbTips)
        return tipsFromProvider(show, week)
    }

    private suspend fun tipsFromTmdb(
        show: TvShow,
        week: ClosedRange<LocalDate>,
    ): List<WatchlistWeekRules.AirTip> {
        val tmdbId = resolveTmdbId(show) ?: return emptyList()
        val lang = UserPreferences.currentProvider?.language
        val detail = runCatching {
            TMDb3.TvSeries.details(seriesId = tmdbId, language = lang)
        }.getOrNull() ?: return emptyList()

        val seasonNumbers = linkedSetOf<Int>()
        detail.nextEpisodeToAir?.seasonNumber?.takeIf { it > 0 }?.let { seasonNumbers += it }
        detail.lastEpisodeToAir?.seasonNumber?.takeIf { it > 0 }?.let { seasonNumbers += it }
        seasonNumbers.toList().forEach { n ->
            if (n > 1) seasonNumbers += n - 1
            seasonNumbers += n + 1
        }
        if (seasonNumbers.isEmpty()) {
            detail.numberOfSeasons.takeIf { it > 0 }?.let { seasonNumbers += it }
        }

        val showKey = LiveHubCatalog.identityKey(show)
        val out = ArrayList<WatchlistWeekRules.AirTip>()
        for (season in seasonNumbers) {
            if (season <= 0) continue
            val episodes = TmdbUtils.getEpisodesBySeason(tmdbId.toString(), season, lang)
            episodes.forEach { ep ->
                val air = calendarToLocalDate(ep.released) ?: return@forEach
                if (!WatchlistWeekRules.inWeek(air, week)) return@forEach
                out += WatchlistWeekRules.AirTip(
                    showKey = showKey,
                    showTitle = show.title,
                    showPoster = show.poster,
                    providerName = show.providerName,
                    providerShowId = show.id,
                    season = season,
                    episode = ep.number,
                    airDate = air,
                    episodeId = ep.id.ifBlank { "tmdb:$tmdbId:S${season}E${ep.number}" },
                    episodeTitle = ep.title,
                    stillPath = ep.poster,
                    overview = ep.overview,
                )
            }
        }
        return out
    }

    private suspend fun tipsFromProvider(
        show: TvShow,
        week: ClosedRange<LocalDate>,
    ): List<WatchlistWeekRules.AirTip> {
        val provider = Provider.findByName(show.providerName.orEmpty()) ?: return emptyList()
        if (provider is IptvProvider) return emptyList()
        val remote = runCatching { provider.getTvShow(show.id) }.getOrNull() ?: return emptyList()
        val seasons = remote.seasons.orEmpty().sortedBy { it.number }
        if (seasons.isEmpty()) return emptyList()
        val candidates = seasons.takeLast(2)
        val showKey = LiveHubCatalog.identityKey(show)
        val out = ArrayList<WatchlistWeekRules.AirTip>()
        for (season in candidates) {
            val episodes = runCatching { provider.getEpisodesBySeason(season.id) }
                .getOrElse { emptyList() }
            episodes.forEach { ep ->
                val air = calendarToLocalDate(ep.released) ?: return@forEach
                if (!WatchlistWeekRules.inWeek(air, week)) return@forEach
                out += WatchlistWeekRules.AirTip(
                    showKey = showKey,
                    showTitle = show.title,
                    showPoster = show.poster ?: remote.poster,
                    providerName = show.providerName,
                    providerShowId = show.id,
                    season = season.number,
                    episode = ep.number,
                    airDate = air,
                    episodeId = ep.id.ifBlank { "${show.id}:S${season.number}E${ep.number}" },
                    episodeTitle = ep.title,
                    stillPath = ep.poster,
                    overview = ep.overview,
                )
            }
        }
        return out
    }

    /** Prefer provider episode ids so Continue-Watching tiles can open playback. */
    private suspend fun hydrateProviderIds(
        show: TvShow,
        tips: List<WatchlistWeekRules.AirTip>,
    ): List<WatchlistWeekRules.AirTip> {
        if (tips.isEmpty()) return tips
        val provider = Provider.findByName(show.providerName.orEmpty()) ?: return tips
        if (provider is IptvProvider) return tips
        val remote = runCatching { provider.getTvShow(show.id) }.getOrNull() ?: return tips
        val seasonsByNumber = remote.seasons.orEmpty().associateBy { it.number }
        val idBySeasonEpisode = HashMap<Pair<Int, Int>, String>()
        tips.map { it.season }.distinct().forEach { seasonNumber ->
            val season = seasonsByNumber[seasonNumber] ?: return@forEach
            val episodes = runCatching { provider.getEpisodesBySeason(season.id) }
                .getOrElse { emptyList() }
            episodes.forEach { ep ->
                if (ep.id.isNotBlank()) {
                    idBySeasonEpisode[seasonNumber to ep.number] = ep.id
                }
            }
        }
        if (idBySeasonEpisode.isEmpty()) return tips
        return tips.map { tip ->
            val providerId = idBySeasonEpisode[tip.season to tip.episode] ?: return@map tip
            tip.copy(episodeId = providerId)
        }
    }

    private suspend fun resolveTmdbId(show: TvShow): Int? {
        // Prefer stamped favorites tmdbId — never guess when ambiguous.
        show.tmdbId?.toIntOrNull()?.let { return it }
        val imdb = show.imdbId?.takeIf { it.isNotBlank() }
        if (imdb != null) {
            TmdbUtils.getTvShowByImdbId(imdb)?.tmdbId?.toIntOrNull()?.let { return it }
        }
        val title = show.title.trim()
        if (title.isBlank()) return null
        val lang = UserPreferences.currentProvider?.language
        val year = show.released?.get(Calendar.YEAR)
        return runCatching {
            val results = TMDb3.Search.tv(
                query = title,
                language = lang,
                firstAirDateYear = year,
            ).results
            when {
                results.isEmpty() -> null
                results.size == 1 -> results.first().id
                year != null -> {
                    val exactYear = results.filter { result ->
                        result.firstAirDate?.startsWith(year.toString()) == true
                    }
                    exactYear.singleOrNull()?.id
                }
                else -> null // ambiguous title search — take none
            }
        }.getOrNull()
    }

    private fun calendarToLocalDate(cal: Calendar?): LocalDate? {
        cal ?: return null
        return LocalDate.of(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun WatchlistWeekRules.AirTip.toEpisode(locale: Locale): Episode {
        val dayLabel = WatchlistWeekRules.shortDayLabel(airDate, locale)
        val epTitle = episodeTitle?.takeIf { it.isNotBlank() }
        val titled = when {
            epTitle != null -> "$dayLabel · $epTitle"
            else -> dayLabel
        }
        val host = TvShow(
            id = providerShowId,
            title = showTitle,
            poster = showPoster,
            providerName = providerName,
        )
        return Episode(
            id = episodeId,
            number = episode,
            title = titled,
            released = airDate.toString(),
            poster = stillPath ?: showPoster,
            overview = overview,
            tvShow = host,
            season = Season(
                id = "$providerShowId-$season",
                number = season,
                title = null,
                tvShow = host,
            ),
        ).also { ep ->
            ep.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM
        }
    }
}
