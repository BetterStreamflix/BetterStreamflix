package com.betterstreamflix.app.home

import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.utils.LiveHubCatalog
import java.util.Locale

/**
 * Remix Home — reorder the Continue / Live / Collections shelf blocks via drag.
 * Featured stays first; favorites + provider rows stay after the remixed blocks.
 */
object HomeRemix {

    enum class Block(val key: String) {
        CONTINUE("continue"),
        LIVE("live"),
        COLLECTIONS("collections"),
        ;

        companion object {
            fun fromKey(raw: String?): Block? {
                val key = raw?.trim()?.lowercase(Locale.US).orEmpty()
                return entries.firstOrNull { it.key == key }
            }
        }
    }

    val DEFAULT_ORDER: List<Block> = listOf(
        Block.CONTINUE,
        Block.LIVE,
        Block.COLLECTIONS,
    )

    fun parseOrder(raw: String?): List<Block> {
        if (raw.isNullOrBlank()) return DEFAULT_ORDER
        val parsed = raw.split(',')
            .mapNotNull { Block.fromKey(it) }
            .distinct()
        return (parsed + DEFAULT_ORDER).distinct()
    }

    fun serializeOrder(order: List<Block>): String =
        normalize(order).joinToString(",") { it.key }

    fun normalize(order: List<Block>): List<Block> =
        (order + DEFAULT_ORDER).distinct()

    fun move(order: List<Block>, from: Int, to: Int): List<Block> {
        val list = normalize(order).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return list
        val item = list.removeAt(from)
        list.add(to, item)
        return list
    }

    fun blockOf(category: Category): Block? = blockOfName(
        name = category.name,
        identityKey = category.identityKey,
    )

    fun blockOfName(name: String?, identityKey: String? = null): Block? {
        val id = identityKey?.trim().orEmpty()
        if (id == Category.HOME_LIVE_ID ||
            id == LiveHubCatalog.SECTION_FAVORITES ||
            id.startsWith("home_live")
        ) {
            return Block.LIVE
        }
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || Category.isFeaturedName(n)) return null
        if (id.equals(Category.FAMILY_FRIDAY, ignoreCase = true) ||
            id.equals(Category.NEW_THIS_WEEK, ignoreCase = true) ||
            n.equals(Category.FAMILY_FRIDAY, ignoreCase = true) ||
            n.equals(Category.NEW_THIS_WEEK, ignoreCase = true)
        ) {
            return Block.CONTINUE
        }
        return when {
            n.equals(Category.CONTINUE_WATCHING, ignoreCase = true) ||
                n.equals(Category.NEXT_EPISODES, ignoreCase = true) ||
                n.equals(Category.RECENTLY_WATCHED, ignoreCase = true) ||
                n.equals(Category.RECENTLY_ADDED, ignoreCase = true) ||
                id.equals(Category.CONTINUE_WATCHING, ignoreCase = true) ||
                id.equals(Category.NEXT_EPISODES, ignoreCase = true) ||
                id.equals(Category.RECENTLY_WATCHED, ignoreCase = true) ||
                id.equals(Category.RECENTLY_ADDED, ignoreCase = true) -> Block.CONTINUE
            n.equals(Category.HOME_LIVE, ignoreCase = true) -> Block.LIVE
            n.equals(Category.SMART_CHILL_EVENING, ignoreCase = true) ||
                n.equals(Category.SMART_WITH_KIDS, ignoreCase = true) ||
                n.equals(Category.SMART_DE_AUDIO, ignoreCase = true) -> Block.COLLECTIONS
            else -> null
        }
    }

    /**
     * Reorder remixable blocks; keep Featured first and non-block rows (favorites,
     * provider shelves) after the remixed region.
     */
    fun apply(
        categories: List<Category>,
        order: List<Block> = DEFAULT_ORDER,
    ): List<Category> {
        val featured = mutableListOf<Category>()
        val buckets = Block.entries.associateWith { mutableListOf<Category>() }
        val rest = mutableListOf<Category>()
        for (category in categories) {
            when {
                Category.isFeaturedName(category.name) -> featured += category
                else -> {
                    val block = blockOf(category)
                    if (block == null) rest += category else buckets.getValue(block) += category
                }
            }
        }
        val remixed = normalize(order).flatMap { buckets.getValue(it) }
        return featured + remixed + rest
    }
}
