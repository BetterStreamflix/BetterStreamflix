package com.betterstreamflix.app.utils

import android.content.Context
import android.util.Log
import com.betterstreamflix.app.BetterStreamflixApp
import com.betterstreamflix.app.R
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.providers.DaddyLiveTvProvider
import com.betterstreamflix.app.providers.IptvOrgProvider
import com.betterstreamflix.app.providers.IptvProvider
import com.betterstreamflix.app.providers.NtvProvider
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.providers.ProviderHealth
import com.betterstreamflix.app.providers.ProviderSmoke
import com.betterstreamflix.app.providers.SportsBiteProvider
import com.betterstreamflix.app.providers.StreamSports99Provider
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Aggregates DaddyLive / StreamSports99 / IPTV-Org (and optional NTV / SportsBite)
 * into a dedicated Live Hub with Favorites, Sport, News, and Channels shelves —
 * without requiring the user to dig through the provider picker first.
 */
object LiveHubCatalog {

    private const val TAG = "LiveHubCatalog"
    private const val PER_SECTION = 48
    private const val PER_PROVIDER_PAGES = 2
    /** Per-source budget so IPTV-Org's 30s callTimeout cannot dominate the hub. */
    private const val PER_SOURCE_TIMEOUT_MS = 18_000L
    private const val OVERALL_TIMEOUT_MS = 28_000L

    const val SECTION_FAVORITES = "live_hub_favorites"
    const val SECTION_SPORT = "live_hub_sport"
    const val SECTION_NEWS = "live_hub_news"
    const val SECTION_CHANNELS = "live_hub_channels"

    /** Live IPTV has no reliable age ratings — block the whole hub under kids/parental. */
    fun isAgeGated(): Boolean = ParentalControlUtils.isFilteringActive()

    enum class Section { FAVORITES, SPORT, NEWS, CHANNELS }

    private val SPORTS_KEYS = listOf(
        "sport", "espn", "sky sport", "bein", "nba", "nfl", "nhl", "mlb", "mls",
        "fox sport", "dazn", "tennis", "golf", "moto", "f1", "ufc", "boxing",
        "cricket", "rugby", "serie a", "laliga", "bundesliga", "premier league",
        "football", "soccer", "basket", "olymp", "ppv",
    )

    private val NEWS_KEYS = listOf(
        "news", "cnn", "msnbc", "fox news", "sky news", "al jazeera",
        "euronews", "bloomberg", "cnbc", "nbc news", "abc news", "cbs news",
        "france 24", "dw ", "nhk", "reuters", "weather channel", "nachrichten",
        "telegiornale", "tg1", "tg5", "rai news", "n-tv", "welt ",
        // "bbc" alone is handled in classify — must not steal BBC Sport.
        "bbc news", "bbc world",
    )

    data class HubResult(
        val categories: List<Category>,
        val warnings: List<String> = emptyList(),
    )

    fun classify(title: String, group: String? = null, overview: String? = null): Section {
        val g = group.orEmpty().lowercase(Locale.US)
        if (g.contains("sport")) return Section.SPORT
        if (g.contains("news") || g.contains("weather")) return Section.NEWS

        val titleL = title.lowercase(Locale.US)
        val blob = buildString {
            append(titleL)
            append(' ')
            append(overview.orEmpty().lowercase(Locale.US))
            append(' ')
            append(g)
        }
        // Sport before News so "BBC Sport" / sport titles never land in News.
        val sportHit = SPORTS_KEYS.any { blob.contains(it) }
        if (sportHit) return Section.SPORT
        val newsHit = NEWS_KEYS.any { blob.contains(it) } ||
            // Bare "bbc" is news only when the title is not sport-context.
            (blob.contains("bbc") && !titleL.contains("sport") && !blob.contains("sport"))
        if (newsHit) return Section.NEWS
        return Section.CHANNELS
    }

    fun partition(shows: List<TvShow>): Map<Section, List<TvShow>> {
        val sport = LinkedHashMap<String, TvShow>()
        val news = LinkedHashMap<String, TvShow>()
        val channels = LinkedHashMap<String, TvShow>()
        for (show in shows) {
            val key = dedupeKey(show)
            when (classify(show.title, groupFromOverview(show.overview), show.overview)) {
                Section.SPORT -> sport.putIfAbsent(key, show)
                Section.NEWS -> news.putIfAbsent(key, show)
                Section.CHANNELS -> channels.putIfAbsent(key, show)
                Section.FAVORITES -> Unit
            }
        }
        return mapOf(
            Section.SPORT to sport.values.toList(),
            Section.NEWS to news.values.toList(),
            Section.CHANNELS to channels.values.toList(),
        )
    }

    fun buildCategories(
        shows: List<TvShow>,
        sportLabel: String,
        newsLabel: String,
        channelsLabel: String,
        favorites: List<TvShow> = emptyList(),
        favoritesLabel: String = "Favorites",
        perSection: Int = PER_SECTION,
    ): List<Category> {
        val parts = partition(shows)
        val out = ArrayList<Category>(4)
        val fav = category(favoritesLabel, SECTION_FAVORITES, favorites, perSection)
        if (fav.list.isNotEmpty()) out += fav
        out += category(sportLabel, SECTION_SPORT, parts[Section.SPORT].orEmpty(), perSection)
        out += category(newsLabel, SECTION_NEWS, parts[Section.NEWS].orEmpty(), perSection)
        out += category(channelsLabel, SECTION_CHANNELS, parts[Section.CHANNELS].orEmpty(), perSection)
        return out.filter { it.list.isNotEmpty() }
    }

    suspend fun load(
        sportLabel: String,
        newsLabel: String,
        channelsLabel: String,
        favoritesLabel: String = "Favorites",
        includeQuarantined: Boolean = false,
        context: Context? = null,
    ): HubResult = withContext(Dispatchers.IO) {
        // Kids profiles: Live Hub is gated in the bottom nav; keep catalog empty if reached.
        if (ProfileManager.isKidsActive()) {
            return@withContext HubResult(categories = emptyList())
        }
        val collected = ConcurrentLinkedQueue<TvShow>()
        val warnings = ConcurrentLinkedQueue<String>()
        val sources = hubSources(includeQuarantined)
        try {
            withTimeout(OVERALL_TIMEOUT_MS) {
                coroutineScope {
                    sources.map { provider ->
                        async {
                            val batch = runCatching {
                                withTimeout(PER_SOURCE_TIMEOUT_MS) {
                                    collectFrom(provider)
                                }
                            }.onFailure { e ->
                                Log.w(TAG, "Live Hub source ${provider.name} failed: ${e.message}")
                                warnings.add(localizedSourceUnavailable(provider.name))
                                ProviderSmoke.noteHomeFailure(provider.name)
                            }.getOrNull()
                            when {
                                batch == null -> Unit
                                batch.isEmpty() -> {
                                    warnings.add(localizedSourceUnavailable(provider.name))
                                    ProviderSmoke.noteHomeFailure(provider.name)
                                }
                                else -> {
                                    ProviderSmoke.noteHomeSuccess(provider.name)
                                    collected.addAll(batch)
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Live Hub overall timeout/fail: ${e.message}")
            warnings.add(
                runCatching {
                    BetterStreamflixApp.instance.getString(R.string.live_hub_empty)
                }.getOrElse { "Live Hub: ${e.message ?: "timed out"}" },
            )
        }
        val shows = ParentalControlUtils.filterItems(collected.toList()).onEach { enrichEpgLite(it) }
        val favorites = resolveFavoriteChannels(
            context = context ?: runCatching { BetterStreamflixApp.instance }.getOrNull(),
            hubShows = shows,
        )
        HubResult(
            categories = buildCategories(
                shows = shows,
                sportLabel = sportLabel,
                newsLabel = newsLabel,
                channelsLabel = channelsLabel,
                favorites = favorites,
                favoritesLabel = favoritesLabel,
            ),
            warnings = warnings.toList(),
        )
    }

    /**
     * Favorite live channels for the hub shelf: IPTV favorites from the library,
     * preferring fresher hub catalog rows (EPG + poster) when ids match.
     */
    suspend fun resolveFavoriteChannels(
        context: Context?,
        hubShows: List<TvShow>,
    ): List<TvShow> {
        if (context == null) return emptyList()
        val hubByKey = LinkedHashMap<String, TvShow>()
        hubShows.forEach { hubByKey[showKey(it)] = it }
        val hubByTitle = hubShows.associateBy { dedupeKey(it) }
        val favorites = runCatching {
            CrossProviderLibrary.loadFavoriteTvShows(context)
        }.getOrElse { emptyList() }
        val out = LinkedHashMap<String, TvShow>()
        for (fav in favorites) {
            if (!isLiveFavorite(fav)) continue
            val key = showKey(fav)
            val fromHub = hubByKey[key] ?: hubByTitle[dedupeKey(fav)]
            val merged = if (fromHub != null) {
                fromHub.apply {
                    isFavorite = true
                    favoritedAtMillis = fav.favoritedAtMillis ?: favoritedAtMillis
                    if (programNow.isNullOrBlank()) {
                        programNow = fav.programNow ?: programNowOf(fav)
                    }
                    if (programNext.isNullOrBlank()) {
                        programNext = fav.programNext
                    }
                }
            } else {
                fav.apply {
                    isFavorite = true
                    enrichEpgLite(this)
                }
            }
            out.putIfAbsent(showKey(merged), merged)
        }
        // Also surface hub rows already marked favorite in-memory / Room stamp.
        hubShows.filter { it.isFavorite }.forEach { show ->
            out.putIfAbsent(showKey(show), show)
        }
        return out.values
            .sortedByDescending { it.favoritedAtMillis ?: 0L }
            .take(PER_SECTION)
    }

    fun isLiveFavorite(show: TvShow): Boolean {
        val provider = Provider.findByName(show.providerName.orEmpty())
        if (provider is IptvProvider) return true
        val name = show.providerName.orEmpty()
        return hubSources(includeQuarantined = true).any { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Real now-playing when providers set [TvShow.programNow]. Does **not** fall
     * back to category/group labels (those live in [groupOf] / overview).
     */
    fun programNowOf(show: TvShow): String? =
        show.programNow?.trim()?.takeIf { it.isNotBlank() }

    fun programNextOf(show: TvShow): String? =
        show.programNext?.trim()?.takeIf { it.isNotBlank() }

    fun groupOf(show: TvShow): String? = groupFromOverview(show.overview)

    fun enrichEpgLite(show: TvShow): TvShow {
        // Keep provider-supplied programNow; do not invent group-as-EPG.
        return show
    }

    private fun localizedSourceUnavailable(providerName: String): String =
        runCatching {
            BetterStreamflixApp.instance.getString(
                R.string.live_hub_source_unavailable,
                providerName,
            )
        }.getOrElse { "$providerName unavailable" }

    /** Providers feeding the hub — order is soft priority for duplicate titles. */
    fun hubSources(includeQuarantined: Boolean = false): List<Provider> {
        val base = listOf(
            StreamSports99Provider,
            DaddyLiveTvProvider,
            IptvOrgProvider,
            NtvProvider,
        )
        val withSportsBite = if (includeQuarantined || !ProviderHealth.isQuarantined(SportsBiteProvider)) {
            base + SportsBiteProvider
        } else {
            base
        }
        return withSportsBite.filterIsInstance<IptvProvider>()
    }

    /** True when [provider] is an IPTV / Live Hub source (Live tab + Meine Sender scope). */
    fun isLiveProvider(provider: Provider?): Boolean {
        if (provider == null) return false
        if (provider is IptvProvider) return true
        return hubSources(includeQuarantined = true).any {
            it.name.equals(provider.name, ignoreCase = true)
        }
    }

    private suspend fun collectFrom(provider: Provider): List<TvShow> {
        val out = LinkedHashMap<String, TvShow>()
        fun stamp(show: TvShow): TvShow {
            if (show.providerName.isNullOrBlank()) {
                show.providerName = provider.name
            }
            enrichEpgLite(show)
            return show
        }

        // Do not soft-catch hard throws from getHome — propagate so load() records failure.
        val home = provider.getHome()
        home.forEach { category ->
            val groupHint = category.name
            // Skip Soporte meta shelves entirely (IPTV-Org/Spain).
            if (groupHint.contains("soporte", ignoreCase = true) ||
                groupHint.contains("ayuda", ignoreCase = true)
            ) {
                return@forEach
            }
            category.list.filterIsInstance<TvShow>().forEach { show ->
                if (show.id == "creador-info" || show.id == "apoyo-nando") return@forEach
                if (show.overview.isNullOrBlank() && groupHint.isNotBlank()) {
                    show.overview = LiveCatalogMeta.overview(
                        title = show.title,
                        group = groupHint,
                    )
                }
                // EPG-001: never stamp shelf/group names into programNow — that
                // field is reserved for real now-playing / catch-up overlays.
                out.putIfAbsent(showKey(show), stamp(show))
            }
        }

        for (page in 1..PER_PROVIDER_PAGES) {
            val batch = provider.getTvShows(page)
            if (batch.isEmpty()) break
            batch.forEach { show ->
                if (show.id == "creador-info" || show.id == "apoyo-nando") return@forEach
                // Prefer group stamped on overview when provider supplies it (paginated IPTV).
                out.putIfAbsent(showKey(show), stamp(show))
            }
        }
        return out.values.toList()
    }

    private fun category(
        label: String,
        identity: String,
        shows: List<TvShow>,
        perSection: Int,
    ): Category {
        val visible = shows.take(perSection)
        val name = if (shows.size > visible.size) {
            "$label (${visible.size}+)"
        } else {
            "$label (${visible.size})"
        }
        return Category(name, visible).also { it.identityKey = identity }
    }

    fun identityKey(show: TvShow): String =
        "${show.providerName.orEmpty()}|${show.id}"

    private fun showKey(show: TvShow): String = identityKey(show)

    /** Cross-provider title dedupe so ESPN from Daddy + SS99 shares one tile. */
    private fun dedupeKey(show: TvShow): String {
        val title = show.title.trim().lowercase(Locale.US)
            .replace(Regex("""\s+"""), " ")
        return if (title.isNotBlank()) title else showKey(show)
    }

    fun groupFromOverview(overview: String?): String? {
        if (overview.isNullOrBlank()) return null
        // LiveCatalogMeta.overview puts "Title\nCountry · Group · …"
        val second = overview.lineSequence().drop(1).firstOrNull() ?: return null
        val afterDot = second.substringAfter('·', missingDelimiterValue = "")
            .trim()
            .substringBefore('·')
            .trim()
        return afterDot.ifBlank { second.trim() }.takeIf { it.isNotBlank() }
    }
}
