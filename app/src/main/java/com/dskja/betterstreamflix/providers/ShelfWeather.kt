package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow

/**
 * Shelf Weather — dim / warn category shelves when their catalog source health is bad.
 *
 * Coupled to [ProviderHealth] quarantine / parked defaults and [ProviderSmoke]
 * circuit / flaky counters. Personal mixed shelves use the worst stamped provider.
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

    fun providerNames(category: Category): List<String> {
        val fromItems = LinkedHashSet<String>()
        for (item in category.list) {
            val name = providerNameOf(item) ?: continue
            if (name.isNotBlank()) fromItems.add(name)
        }
        val identity = category.identityKey?.trim().orEmpty()
        if (fromItems.isEmpty() && identity.isNotEmpty() && !Category.isFeaturedName(identity)) {
            fromItems.add(identity)
        }
        return fromItems.toList()
    }

    fun forecastForProviders(providerNames: Collection<String>): Forecast {
        var worst = CLEAR
        for (name in providerNames) {
            val next = forecastForProvider(name)
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
