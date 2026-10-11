package com.dskja.betterstreamflix.models

import com.dskja.betterstreamflix.adapters.AppAdapter

class Category(
    var name: String,
    val list: List<AppAdapter.Item>,
) : AppAdapter.Item {

    var selectedIndex: Int = 0
    var itemSpacing: Int = 0

    /**
     * Optional DiffUtil-stable id. Search global rows keep the provider name here while
     * [name] shows transient status ("Searching", counts) so progressive updates rebind
     * instead of remove/insert nested Leanback shelves.
     */
    var identityKey: String? = null

    override lateinit var itemType: AppAdapter.Type


    fun copy(
        name: String = this.name,
        list: List<AppAdapter.Item> = this.list,
        selectedIndex: Int = this.selectedIndex,
        itemSpacing: Int = this.itemSpacing,
        identityKey: String? = this.identityKey,
    ) = Category(
        name,
        list,
    ).also {
        it.selectedIndex = selectedIndex
        it.itemSpacing = itemSpacing
        it.identityKey = identityKey
        if (::itemType.isInitialized) {
            it.itemType = itemType
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Category

        if (name != other.name) return false
        if (list != other.list) return false
        if (selectedIndex != other.selectedIndex) return false
        if (itemSpacing != other.itemSpacing) return false
        if (identityKey != other.identityKey) return false
        if (!::itemType.isInitialized || !other::itemType.isInitialized) return false
        return itemType == other.itemType
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + list.hashCode()
        result = 31 * result + selectedIndex
        result = 31 * result + itemSpacing
        result = 31 * result + (identityKey?.hashCode() ?: 0)
        result = 31 * result + (if (::itemType.isInitialized) itemType.hashCode() else 0)
        return result
    }


    companion object {
        /**
         * Stable Featured shelf id. Historically `""` so providers could omit a
         * title; blank / "Featured*" names are still normalized to this in
         * [com.dskja.betterstreamflix.utils.HomeCatalogPipeline].
         */
        const val FEATURED = "Featured"
        const val CONTINUE_WATCHING = "Continue Watching"
        /** Unwatched next episode for shows already in progress (no mid-episode resume). */
        const val NEXT_EPISODES = "Next Episodes"
        const val RECENTLY_WATCHED = "Recently Watched"
        /** Favorites sorted by favoritedAt — Home shelf companion to Favorites “Recently added”. */
        const val RECENTLY_ADDED = "Recently Added"
        const val FAVORITE_MOVIES = "Favorite movies"
        const val FAVORITE_TV_SHOWS = "Favorite TV shows"
        /** Smart Collections — dynamic shelves from Watchlist + Continue Watching. */
        const val SMART_CHILL_EVENING = "Chill Evening"
        const val SMART_WITH_KIDS = "With Kids"
        const val SMART_DE_AUDIO = "German Audio"
        /** Home Live strip (favorites / pinned channels) — Remix Home block. */
        const val HOME_LIVE = "Live"
        const val HOME_LIVE_ID = "home_live"

        /** True for the Featured constant or a blank legacy name. */
        fun isFeaturedName(name: String?): Boolean {
            val n = name?.trim().orEmpty()
            return n.isEmpty() || n.equals(FEATURED, ignoreCase = true)
        }
    }
}
