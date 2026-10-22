package com.betterstreamflix.app.providers

import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow

/**
 * Shelf Weather — dim / warn category shelves when their catalog source health is bad.
 *
 * Coupled to [ProviderHealth] quarantine / parked defaults and [ProviderSmoke]
 * circuit / flaky counters. Mixed personal shelves use majority / dominant-provider
 * rules (not worst-of-one).
 *
 * Distinct from [ProviderTrustLayer] outage banners (PT-025): Weather intentionally
 * storms quarantined/parked shelves, while Trust outage names exclude those
 * registry states so Home is not permanently SEVERE.
 */
object ShelfWeather {

    /** Poster row alpha when the source is flaky / circuit-open. */
    const val HAZE_ALPHA: Float = 0.72f

    /** Poster row alpha when the source is quarantined / parked. */
    const val STORM_ALPHA: Float = 0.48f

    const val CLEAR_ALPHA: Float = 1f

    /** Title stays readable; slight pull-back on storm. */
    const val TITLE_HAZE_ALPHA: Float = 0.92f
    const val TITLE_STORM_ALPHA: Float = 0.78f

    enum class Level {
        CLEAR,
        HAZE,
        STORM,
    }

    enum class Reason {
        QUARANTINED,
        PARKED,
        CIRCUIT_OPEN,
        FLAKY,
    }

    data class Forecast(
        val level: Level,
        val reason: Reason? = null,
        val providerName: String? = null,
    ) {
        val shouldWarn: Boolean get() = level != Level.CLEAR
        val contentAlpha: Float
            get() = when (level) {
                Level.CLEAR -> CLEAR_ALPHA
                Level.HAZE -> HAZE_ALPHA
                Level.STORM -> STORM_ALPHA
            }
        val titleAlpha: Float
            get() = when (level) {
                Level.CLEAR -> CLEAR_ALPHA
                Level.HAZE -> TITLE_HAZE_ALPHA
                Level.STORM -> TITLE_STORM_ALPHA
            }
    }

    val CLEAR: Forecast = Forecast(Level.CLEAR)

    /**
     * Featured hero and empty shelves stay clear — weather is for catalog rows
     * backed by stamped provider items.
     */
    fun appliesTo(category: Category): Boolean {
        if (Category.isFeaturedName(category.name)) return false
        return category.list.isNotEmpty()
    }

    fun forecast(category: Category): Forecast {
        if (!appliesTo(category)) return CLEAR
        val names = providerNames(category)
        if (names.isEmpty()) return CLEAR
        return forecastForProviders(names)
    }

    /**
     * Stamped provider names (one entry per item). Falls back to [Category.identityKey]
     * only when it matches a known [Provider] name — never `home_*` / human titles.
     */
    fun providerNames(category: Category): List<String> {
        val fromItems = ArrayList<String>()
        for (item in category.list) {
            val name = providerNameOf(item) ?: continue
            if (name.isNotBlank()) fromItems.add(name)
        }
        if (fromItems.isEmpty()) {
            val identity = category.identityKey?.trim().orEmpty()
            if (identity.isNotEmpty() &&
                !Category.isFeaturedName(identity) &&
                isKnownProviderName(identity)
            ) {
                fromItems.add(identity)
            }
        }
        return fromItems
    }

    /**
     * Mixed shelves: warn only when a strict majority of stamped items are unhealthy,
     * or when the unique dominant provider is unhealthy. A single bad stamp among many
     * healthy ones stays clear.
     */
    fun forecastForProviders(providerNames: Collection<String>): Forecast {
        if (providerNames.isEmpty()) return CLEAR
        val list = providerNames.map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return CLEAR
        if (list.size == 1) return forecastForProvider(list.first())

        val counts = list.groupingBy { it }.eachCount()
        val dominantEntry = counts.entries.maxByOrNull { it.value }
        val dominantName = dominantEntry?.key
        val dominantCount = dominantEntry?.value ?: 0
        val uniquelyDominant = dominantName != null &&
            counts.values.count { it == dominantCount } == 1

        if (uniquelyDominant) {
            val dominantForecast = forecastForProvider(dominantName!!)
            if (dominantForecast.shouldWarn) return dominantForecast
        }

        val unhealthyItemCount = list.count { forecastForProvider(it).shouldWarn }
        val majorityUnhealthy = unhealthyItemCount * 2 > list.size
        if (!majorityUnhealthy) return CLEAR

        var worst = CLEAR
        for (name in list.distinct()) {
            val next = forecastForProvider(name)
            if (!next.shouldWarn) continue
            if (severityRank(next.level) < severityRank(worst.level)) {
                worst = next
            }
            if (worst.level == Level.STORM) return worst
        }
        return worst
    }

    fun forecastForProvider(providerName: String): Forecast {
        val name = providerName.trim()
        if (name.isEmpty()) return CLEAR
        return when {
            ProviderHealth.parkedDefaultNames.contains(name) ->
                Forecast(Level.STORM, Reason.PARKED, name)
            ProviderHealth.isQuarantinedName(name) ->
                Forecast(Level.STORM, Reason.QUARANTINED, name)
            ProviderSmoke.peekHomeCircuitOpen(name) ->
                Forecast(Level.HAZE, Reason.CIRCUIT_OPEN, name)
            ProviderSmoke.failureCount(name) >= 2 ->
                Forecast(Level.HAZE, Reason.FLAKY, name)
            else -> CLEAR
        }
    }

    /** Compact digest for Home bind signatures (quarantine + non-zero smoke). */
    fun healthSmokeDigest(): String {
        val q = ProviderHealth.quarantinedNames.size
        val parked = ProviderHealth.parkedDefaultNames.size
        val smokeBits = runCatching {
            Provider.providers.keys.mapNotNull { provider ->
                val name = provider.name
                val fails = ProviderSmoke.failureCount(name)
                val open = ProviderSmoke.peekHomeCircuitOpen(name)
                if (fails <= 0 && !open) null else "$name:$fails:${if (open) 1 else 0}"
            }.sorted().joinToString(";")
        }.getOrDefault("")
        return "q=$q|p=$parked|s=$smokeBits"
    }

    private fun isKnownProviderName(name: String): Boolean {
        // Reject shelf/human ids early; accept registry + health name sets.
        if (name.startsWith("home_", ignoreCase = true)) return false
        if (Category.isFeaturedName(name)) return false
        if (ProviderHealth.quarantinedNames.contains(name)) return true
        if (ProviderHealth.parkedDefaultNames.contains(name)) return true
        if (ProviderHealth.topSmokeNames.contains(name)) return true
        return runCatching { Provider.findByName(name) != null }.getOrDefault(false)
    }

    private fun severityRank(level: Level): Int = when (level) {
        Level.STORM -> 0
        Level.HAZE -> 1
        Level.CLEAR -> 2
    }

    private fun providerNameOf(item: AppAdapter.Item): String? = when (item) {
        is Movie -> item.providerName
        is TvShow -> item.providerName
        else -> null
    }
}
