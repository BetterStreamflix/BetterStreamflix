package com.dskja.betterstreamflix.utils

import android.util.Log
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.DaddyLiveTvProvider
import com.dskja.betterstreamflix.providers.IptvOrgProvider
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.NtvProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.providers.ProviderHealth
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.providers.SportsBiteProvider
import com.dskja.betterstreamflix.providers.StreamSports99Provider
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
 * into a dedicated Live Hub with Sport, News, and Channels shelves — without
 * requiring the user to dig through the provider picker first.
 */
object LiveHubCatalog {

    private const val TAG = "LiveHubCatalog"
    private const val PER_SECTION = 48
    private const val PER_PROVIDER_PAGES = 2
    /** Per-source budget so IPTV-Org's 30s callTimeout cannot dominate the hub. */
    private const val PER_SOURCE_TIMEOUT_MS = 18_000L
    private const val OVERALL_TIMEOUT_MS = 28_000L

    const val SECTION_SPORT = "live_hub_sport"
    const val SECTION_NEWS = "live_hub_news"
    const val SECTION_CHANNELS = "live_hub_channels"

    /** Live IPTV has no reliable age ratings — block the whole hub under kids/parental. */
    fun isAgeGated(): Boolean = ParentalControlUtils.isFilteringActive()

    enum class Section { SPORT, NEWS, CHANNELS }

    private val SPORTS_KEYS = listOf(
        "sport", "espn", "sky sport", "bein", "nba", "nfl", "nhl", "mlb", "mls",
        "fox sport", "dazn", "tennis", "golf", "moto", "f1", "ufc", "boxing",
        "cricket", "rugby", "serie a", "laliga", "bundesliga", "premier",
        "football", "soccer", "basket", "olymp", "ppv",
    )

    private val NEWS_KEYS = listOf(
        "news", "cnn", "bbc", "msnbc", "fox news", "sky news", "al jazeera",
        "euronews", "bloomberg", "cnbc", "nbc news", "abc news", "cbs news",
        "france 24", "dw ", "nhk", "reuters", "weather channel", "nachrichten",
        "telegiornale", "tg1", "tg5", "rai news", "n-tv", "welt ",
    )

    data class HubResult(
        val categories: List<Category>,
        val warnings: List<String> = emptyList(),
    )

    fun classify(title: String, group: String? = null, overview: String? = null): Section {
        val g = group.orEmpty().lowercase(Locale.US)
        if (g.contains("sport")) return Section.SPORT
        if (g.contains("news") || g.contains("weather")) return Section.NEWS

        val blob = buildString {
            append(title.lowercase(Locale.US))
            append(' ')
            append(overview.orEmpty().lowercase(Locale.US))
            append(' ')
            append(g)
        }
        if (NEWS_KEYS.any { blob.contains(it) }) return Section.NEWS
        if (SPORTS_KEYS.any { blob.contains(it) }) return Section.SPORT
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
        perSection: Int = PER_SECTION,
    ): List<Category> {
        val parts = partition(shows)
        return listOf(
            category(sportLabel, SECTION_SPORT, parts[Section.SPORT].orEmpty(), perSection),
            category(newsLabel, SECTION_NEWS, parts[Section.NEWS].orEmpty(), perSection),
            category(channelsLabel, SECTION_CHANNELS, parts[Section.CHANNELS].orEmpty(), perSection),
        ).filter { it.list.isNotEmpty() }
    }

    suspend fun load(
        sportLabel: String,
        newsLabel: String,
        channelsLabel: String,
        includeQuarantined: Boolean = false,
    ): HubResult = withContext(Dispatchers.IO) {
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
                                warnings.add("${provider.name}: ${e.message ?: "unavailable"}")
                                ProviderSmoke.noteHomeFailure(provider.name)
                            }.getOrNull()
                            when {
                                batch == null -> Unit
                                batch.isEmpty() -> {
                                    warnings.add("${provider.name}: empty catalog")
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
        } catch (e: Exception) {
            Log.w(TAG, "Live Hub overall timeout/fail: ${e.message}")
            warnings.add("Live Hub: ${e.message ?: "timed out"}")
        }
        val shows = collected.toList()
        HubResult(
            categories = buildCategories(shows, sportLabel, newsLabel, channelsLabel),
            warnings = warnings.toList(),
        )
    }

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

    private suspend fun collectFrom(provider: Provider): List<TvShow> {
        val out = LinkedHashMap<String, TvShow>()
        fun stamp(show: TvShow): TvShow {
            if (show.providerName.isNullOrBlank()) {
                show.providerName = provider.name
            }
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

    private fun showKey(show: TvShow): String =
        "${show.providerName.orEmpty()}|${show.id}"

    /** Cross-provider title dedupe so ESPN from Daddy + SS99 shares one tile. */
    private fun dedupeKey(show: TvShow): String {
        val title = show.title.trim().lowercase(Locale.US)
            .replace(Regex("""\s+"""), " ")
        return if (title.isNotBlank()) title else showKey(show)
    }

    private fun groupFromOverview(overview: String?): String? {
        if (overview.isNullOrBlank()) return null
        // LiveCatalogMeta.overview puts "Title\nCountry · Group · …"
        val second = overview.lineSequence().drop(1).firstOrNull() ?: return null
        return second.substringAfter('·', missingDelimiterValue = second).trim()
            .ifBlank { second.trim() }
    }
}
