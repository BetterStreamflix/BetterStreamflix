package com.dskja.betterstreamflix.smartcollections

import android.content.Context
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.platform.ContinueWatchingMerger
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Builds dynamic Home shelves from Watchlist + Continue Watching.
 */
object SmartCollectionsBuilder {

    /**
     * @return ordered Category shelves ready for Home (English stable names).
     */
    suspend fun build(
        context: Context,
        favorites: List<AppAdapter.Item>,
        continueWatching: List<AppAdapter.Item>,
    ): List<Category> {
        if (!UserPreferences.showSmartCollections) return emptyList()
        val appContext = context.applicationContext
        val pool = SmartCollectionEnricher.preparePool(
            context = appContext,
            favorites = favorites,
            continueWatching = continueWatching,
        )
        if (pool.size < SmartCollectionRules.MIN_SHELF_SIZE) return emptyList()

        val audioLookup = SmartCollectionEnricher.preferredAudioLookup(appContext)
        val scored = pool.mapNotNull { item ->
            val signals = SmartCollectionRules.signalsFor(item, audioLookup) ?: return@mapNotNull null
            ScoredTitle(
                item = item,
                chill = SmartCollectionRules.matchesChill(signals),
                kids = SmartCollectionRules.matchesKids(signals),
                deAudio = SmartCollectionRules.matchesDeAudio(signals),
                rank = engagementRank(item),
            )
        }

        val chill = scored.filter { it.chill }.sortedByDescending { it.rank }.map { it.item }
        val kids = scored.filter { it.kids }.sortedByDescending { it.rank }.map { it.item }
        val deAudio = scored.filter { it.deAudio }.sortedByDescending { it.rank }.map { it.item }

        val shelves = mutableListOf<Category>()
        fun add(name: String, items: List<AppAdapter.Item>) {
            val capped = items
                .distinctBy { ContinueWatchingMerger.identityKey(it) ?: poolKeyFallback(it) }
                .take(SmartCollectionRules.SHELF_CAP)
            if (capped.size >= SmartCollectionRules.MIN_SHELF_SIZE) {
                shelves += Category(name = name, list = capped)
            }
        }

        // Kids profile: surface family-safe rail first.
        if (ProfileManager.isKidsActive()) {
            add(Category.SMART_WITH_KIDS, kids)
            add(Category.SMART_CHILL_EVENING, chill)
            add(Category.SMART_DE_AUDIO, deAudio)
        } else {
            add(Category.SMART_CHILL_EVENING, chill)
            add(Category.SMART_WITH_KIDS, kids)
            add(Category.SMART_DE_AUDIO, deAudio)
        }
        return shelves
    }

    private data class ScoredTitle(
        val item: AppAdapter.Item,
        val chill: Boolean,
        val kids: Boolean,
        val deAudio: Boolean,
        val rank: Long,
    )

    private fun engagementRank(item: AppAdapter.Item): Long = when (item) {
        is Movie -> maxOf(
            item.lastPlayedAtMillis ?: 0L,
            item.favoritedAtMillis ?: 0L,
            item.watchHistory?.lastEngagementTimeUtcMillis ?: 0L,
        )
        is TvShow -> maxOf(
            item.lastPlayedAtMillis ?: 0L,
            item.favoritedAtMillis ?: 0L,
        )
        else -> 0L
    }

    private fun poolKeyFallback(item: AppAdapter.Item): String = when (item) {
        is Movie -> "movie:${item.id}"
        is TvShow -> "show:${item.id}"
        else -> item.hashCode().toString()
    }
}
