package com.dskja.betterstreamflix.home

import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.utils.UserPreferences
import org.json.JSONArray

/**
 * Shelf Pinning — float up to [MAX_PINS] individual Home shelves under Featured.
 *
 * Coordinates with [HomeRemix]: Remix reorders Continue / Live / Collections
 * blocks first; pinning then lifts specific shelves (Continue, Live, Downloads,
 * Collections…) above the remixed remainder. Featured is never pinned.
 */
object HomeShelfLayout {

    const val MAX_PINS = 3

    /** Well-known pinnable ids (stable English / Remix identity keys). */
    val WELL_KNOWN_PINNABLE = listOf(
        Category.CONTINUE_WATCHING,
        Category.NEXT_EPISODES,
        Category.RECENTLY_WATCHED,
        Category.RECENTLY_ADDED,
        Category.NEW_THIS_WEEK,
        Category.FAMILY_FRIDAY,
        Category.HOME_LIVE_ID,
        Category.DOWNLOADS_ID,
        Category.SMART_CHILL_EVENING,
        Category.SMART_WITH_KIDS,
        Category.SMART_DE_AUDIO,
        Category.FAVORITE_MOVIES,
        Category.FAVORITE_TV_SHOWS,
    )

    sealed class ToggleResult {
        data class Pinned(val pins: List<String>) : ToggleResult()
        data class Unpinned(val pins: List<String>) : ToggleResult()
        data class MaxReached(val pins: List<String>) : ToggleResult()
        data object NotPinnable : ToggleResult()
    }

    fun shelfId(category: Category): String {
        val key = category.identityKey?.trim().orEmpty()
        if (key.isNotEmpty()) return key
        return category.name.trim()
    }

    fun isFeatured(category: Category): Boolean =
        Category.isFeaturedName(shelfId(category)) || Category.isFeaturedName(category.name)

    fun isPinnable(category: Category): Boolean {
        if (isFeatured(category)) return false
        return shelfId(category).isNotEmpty()
    }

    fun isPinnableId(shelfId: String): Boolean {
        val id = shelfId.trim()
        return id.isNotEmpty() && !Category.isFeaturedName(id)
    }

    fun pins(): List<String> = decodeList(UserPreferences.homeShelfPinsJson)

    fun setPins(ids: List<String>) {
        val cleaned = ids.map { it.trim() }
            .filter { isPinnableId(it) }
            .distinct()
            .take(MAX_PINS)
        UserPreferences.homeShelfPinsJson = encodeList(cleaned)
    }

    fun isPinned(shelfId: String): Boolean =
        pins().any { it.equals(shelfId.trim(), ignoreCase = true) }

    fun togglePin(shelfId: String): ToggleResult {
        val id = shelfId.trim()
        if (!isPinnableId(id)) return ToggleResult.NotPinnable
        val current = pins().toMutableList()
        val index = current.indexOfFirst { it.equals(id, ignoreCase = true) }
        if (index >= 0) {
            current.removeAt(index)
            setPins(current)
            return ToggleResult.Unpinned(pins())
        }
        if (current.size >= MAX_PINS) {
            return ToggleResult.MaxReached(current)
        }
        current.add(id)
        setPins(current)
        return ToggleResult.Pinned(pins())
    }

    /**
     * Featured → pinned (≤3, pin order) → remaining in the incoming order
     * (already Remix-ordered). Unknown pin ids are skipped.
     */
    fun apply(
        categories: List<Category>,
        pins: List<String> = pins(),
    ): List<Category> {
        if (categories.isEmpty()) return categories
        val featured = mutableListOf<Category>()
        val rest = LinkedHashMap<String, Category>()
        for (category in categories) {
            if (isFeatured(category)) {
                featured += category
            } else {
                rest[shelfId(category)] = category
            }
        }
        val pinned = mutableListOf<Category>()
        for (pinId in pins.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_PINS)) {
            val matchKey = rest.keys.firstOrNull { it.equals(pinId, ignoreCase = true) }
                ?: continue
            rest.remove(matchKey)?.let { pinned += it }
        }
        return featured + pinned + rest.values
    }

    fun stampIdentity(category: Category, id: String = category.name): Category {
        if (category.identityKey.isNullOrBlank()) {
            category.identityKey = id
        }
        return category
    }

    private fun encodeList(ids: List<String>): String {
        if (ids.isEmpty()) return ""
        val array = JSONArray()
        ids.forEach { array.put(it) }
        return array.toString()
    }

    private fun decodeList(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(text)
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotEmpty()) add(value)
                }
            }
        }.getOrElse {
            text.split(',', '|', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }
}
