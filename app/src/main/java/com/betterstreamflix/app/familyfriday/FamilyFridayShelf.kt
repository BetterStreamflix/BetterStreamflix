package com.betterstreamflix.app.familyfriday

import android.util.Log
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.platform.ContinueWatchingMerger
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.smartcollections.SmartCollectionRules
import com.betterstreamflix.app.utils.ParentalControlUtils
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Kids-profile Friday ritual: exactly three age-appropriate catalog suggestions on Home.
 *
 * Independent of [com.betterstreamflix.app.utils.UserPreferences.showSmartCollections].
 */
object FamilyFridayShelf {

    private const val TAG = "FamilyFriday"
    const val SUGGESTION_COUNT = 3

    fun isFamilyFriday(
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean =
        LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone).dayOfWeek == DayOfWeek.FRIDAY

    /**
     * @return a [Category.FAMILY_FRIDAY] shelf with exactly [SUGGESTION_COUNT] titles, or null
     * when the kids/Friday gate fails or fewer than three kids-tilted (+padded) candidates exist.
     */
    suspend fun build(
        catalogCategories: List<Category>,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        isKidsActive: () -> Boolean = { ProfileManager.isKidsActive() },
        profileId: () -> String = { ProfileManager.activeProfileId },
        filterAgeSafe: suspend (List<AppAdapter.Item>) -> List<AppAdapter.Item> = {
            ParentalControlUtils.filterItems(it)
        },
        favoriteCategories: List<Category> = emptyList(),
    ): Category? {
        if (!isKidsActive() || !isFamilyFriday(nowMs, zone)) return null

        val pool = flattenCatalog(catalogCategories + favoriteCategories)
        if (pool.size < SUGGESTION_COUNT) {
            logOmit("catalog pool thin (${pool.size})")
            return null
        }

        val ageSafe = filterAgeSafe(pool)
            .distinctBy { ContinueWatchingMerger.identityKey(it) ?: poolKeyFallback(it) }
        if (ageSafe.size < SUGGESTION_COUNT) {
            logOmit("age-safe pool thin (${ageSafe.size})")
            return null
        }

        val kidsTilt = ageSafe.filter { item ->
            val signals = SmartCollectionRules.signalsFor(item) ?: return@filter false
            SmartCollectionRules.matchesKids(signals)
        }
        // Prefer kids-tilt; pad with ageSafe only when tilt is thin but non-empty.
        // Never adult-fill from a zero kids-tilt pool.
        val candidates = when {
            kidsTilt.size >= SUGGESTION_COUNT -> kidsTilt
            kidsTilt.isEmpty() -> {
                logOmit("no kids-tilt titles")
                return null
            }
            else -> {
                val tiltKeys = kidsTilt.mapTo(HashSet()) {
                    ContinueWatchingMerger.identityKey(it) ?: poolKeyFallback(it)
                }
                val pad = ageSafe.filter {
                    val key = ContinueWatchingMerger.identityKey(it) ?: poolKeyFallback(it)
                    key !in tiltKeys
                }
                val padded = kidsTilt + pad
                if (padded.size < SUGGESTION_COUNT) {
                    logOmit("padded kids-tilt still thin (${padded.size})")
                    return null
                }
                padded
            }
        }

        val date = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val seed = (profileId() + "|" + date).hashCode().toLong()
        val picked = deterministicPick(candidates, SUGGESTION_COUNT, seed) ?: return null

        return Category(name = Category.FAMILY_FRIDAY, list = picked)
    }

    private fun flattenCatalog(categories: List<Category>): List<AppAdapter.Item> {
        val seen = linkedSetOf<String>()
        val out = ArrayList<AppAdapter.Item>()
        for (category in categories) {
            for (item in category.list) {
                if (item !is Movie && item !is TvShow) continue
                val key = ContinueWatchingMerger.identityKey(item) ?: poolKeyFallback(item)
                if (!seen.add(key)) continue
                out += item
            }
        }
        return out
    }

    /**
     * Stable shuffle for the Friday/profile seed, then take [count]. Returns null if underfilled.
     */
    internal fun deterministicPick(
        items: List<AppAdapter.Item>,
        count: Int,
        seed: Long,
    ): List<AppAdapter.Item>? {
        if (items.size < count) return null
        val shuffled = items.toMutableList()
        val rng = Random(seed)
        for (i in shuffled.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = shuffled[i]
            shuffled[i] = shuffled[j]
            shuffled[j] = tmp
        }
        return shuffled.take(count)
    }

    private fun poolKeyFallback(item: AppAdapter.Item): String = when (item) {
        is Movie -> "movie:${item.id}"
        is TvShow -> "show:${item.id}"
        else -> item.hashCode().toString()
    }

    private fun logOmit(reason: String) {
        runCatching { Log.i(TAG, "Family Friday omitted — $reason") }
    }
}
