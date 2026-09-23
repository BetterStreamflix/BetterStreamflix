package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.Provider
import java.net.URI

/**
 * Shared post-process for every provider's [Provider.getHome] result.
 *
 * Ensures FEATURED exists, stamps [Movie.providerName] / [TvShow.providerName],
 * drops empty shelves, absolute-izes relative artwork URLs (poster/banner/logo),
 * and dedupes items. Applied once in
 * [com.dskja.betterstreamflix.fragments.home.HomeViewModel] so all ~80 scrapers
 * benefit without per-provider rewrites.
 *
 * Featured contract:
 * - [Category.FEATURED] is the stable shelf id (`"Featured"`); blank / "Featured*"
 *   names normalize here.
 * - FEATURED rows are always identity-cloned ([cloneShowItems]) so Mobile
 *   ViewPager2 itemType stamps cannot crash shelf posters (BETTERSTREAMFLIX-13).
 * - Cap: [MAX_FEATURED_ITEMS] (aligned with
 *   [com.dskja.betterstreamflix.ui.FeaturedHeroController.MAX_FEATURED_BITMAP_BUDGET]).
 * - Demo addon tip ids never promote into FEATURED.
 * - Kids / parental: when filtering is active, donor synthesis only uses already
 *   filtered shelves (callers must filter before [ensureFeaturedShelf]).
 */
object HomeCatalogPipeline {

    const val MAX_ITEMS_PER_CATEGORY = 40
    const val MAX_FEATURED_ITEMS = 12

    data class Result(
        val categories: List<Category>,
        val warnings: List<String> = emptyList(),
    ) {
        val warningText: String?
            get() = warnings.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun process(
        provider: Provider,
        categories: List<Category>,
        addonRows: List<Category> = emptyList(),
    ): Result {
        val warnings = mutableListOf<String>()
        val merged = categories + addonRows
        if (merged.isEmpty()) {
            return Result(emptyList(), listOf("Empty catalog from ${provider.name}"))
        }

        val stamped = merged.mapNotNull { category ->
            normalizeCategory(provider, category)
        }

        if (stamped.isEmpty()) {
            return Result(emptyList(), listOf("No usable rows from ${provider.name}"))
        }

        val withFeatured = ensureFeatured(stamped, warnings)
        val dedupedShelves = mergeDuplicateShelfNames(withFeatured)

        return Result(dedupedShelves, warnings)
    }

    private fun normalizeCategory(provider: Provider, category: Category): Category? {
        val rawName = category.name.trim()
        val name = when {
            rawName.isEmpty() -> Category.FEATURED
            Category.isFeaturedName(rawName) -> Category.FEATURED
            // Exact-ish Featured labels only — avoid collapsing "Featured Hits 2024".
            rawName.contains("Featured", ignoreCase = true) &&
                rawName.length <= 16 &&
                rawName.split(Regex("\\s+")).size <= 2 -> Category.FEATURED
            else -> rawName
        }
        val cap = if (name == Category.FEATURED) MAX_FEATURED_ITEMS else MAX_ITEMS_PER_CATEGORY
        val seen = LinkedHashSet<String>()
        val items = category.list.mapNotNull { item ->
            stampAndNormalize(provider, item)
        }.filter { item ->
            val key = itemKey(item) ?: return@filter true
            seen.add(key)
        }.take(cap)

        if (items.isEmpty()) return null
        return Category(name = name, list = items)
    }

    private fun stampAndNormalize(provider: Provider, item: AppAdapter.Item): AppAdapter.Item? {
        return when (item) {
            is Movie -> {
                if (item.id.isBlank() && item.title.isBlank()) return null
                item.apply {
                    if (providerName.isNullOrBlank()) providerName = provider.name
                    poster = absoluteUrl(provider.baseUrl, poster)
                    banner = absoluteUrl(provider.baseUrl, banner)
                    logo = absoluteUrl(provider.baseUrl, logo)
                }
            }

            is TvShow -> {
                if (item.id.isBlank() && item.title.isBlank()) return null
                item.apply {
                    if (providerName.isNullOrBlank()) providerName = provider.name
                    poster = absoluteUrl(provider.baseUrl, poster)
                    banner = absoluteUrl(provider.baseUrl, banner)
                    logo = absoluteUrl(provider.baseUrl, logo)
                }
            }

            is Episode -> {
                item.apply {
                    tvShow?.let { show ->
                        if (show.providerName.isNullOrBlank()) show.providerName = provider.name
                        show.poster = absoluteUrl(provider.baseUrl, show.poster)
                        show.banner = absoluteUrl(provider.baseUrl, show.banner)
                    }
                    poster = absoluteUrl(provider.baseUrl, poster)
                }
            }

            else -> item
        }
    }

    private fun ensureFeatured(
        categories: List<Category>,
        warnings: MutableList<String>,
    ): List<Category> {
        val featured = categories.firstOrNull { Category.isFeaturedName(it.name) }
        if (featured != null && featured.list.isNotEmpty()) {
            // Always clone FEATURED rows even when the provider already sent the
            // shelf — shared Movie/TvShow refs with other shelves mutate itemType
            // (poster ↔ swiper) and crash ViewPager2 (BETTERSTREAMFLIX-13).
            val cloned = cloneShowItems(featured.list)
            return categories.map { cat ->
                if (Category.isFeaturedName(cat.name)) {
                    cat.copy(
                        name = Category.FEATURED,
                        list = cloned,
                        selectedIndex = cat.selectedIndex
                            .coerceIn(0, (cloned.size - 1).coerceAtLeast(0)),
                    )
                } else {
                    cat
                }
            }
        }

        val donor = categories.firstOrNull { category ->
            !Category.isFeaturedName(category.name) &&
                !category.name.equals("BetterStreamflix Addons", ignoreCase = true) &&
                category.list.any { item ->
                    (item is Movie || item is TvShow) && !isPluginTipItem(item)
                }
        } ?: return categories

        warnings.add("Featured shelf restored from “${donor.name.ifBlank { "catalog" }}”")
        val featuredItems = cloneShowItems(
            donor.list.filter { item ->
                (item is Movie || item is TvShow) && !isPluginTipItem(item)
            }.take(MAX_FEATURED_ITEMS),
        )
        val rest = categories.filterNot { it === donor }
            .filter { !Category.isFeaturedName(it.name) }
        // Do not keep a blank-named donor (legacy Featured sentinel) as a shelf.
        val keepDonor = donor.name.isNotBlank() && !Category.isFeaturedName(donor.name)
        return buildList {
            add(Category(name = Category.FEATURED, list = featuredItems))
            if (keepDonor) add(donor)
            addAll(rest)
        }
    }

    /** Never let built-in tip / onboarding tiles get promoted into FEATURED. */
    private fun isPluginTipItem(item: AppAdapter.Item): Boolean {
        if (item !is Movie) return false
        val id = item.id.lowercase()
        return id == "demo-addon-tip" ||
            id.endsWith("-tip") ||
            id.startsWith("tip-") ||
            id.contains("onboarding") ||
            item.title.contains("plugin system", ignoreCase = true)
    }

    fun cloneShowItems(items: List<AppAdapter.Item>): List<AppAdapter.Item> =
        items.map { item ->
            when (item) {
                is Movie -> item.copy()
                is TvShow -> item.copy()
                else -> item
            }
        }

    /**
     * Home bind helper: restore FEATURED if filters dropped it, then clone
     * every featured Movie/TvShow so shelf rows can mutate [AppAdapter.Item.itemType]
     * without crashing the ViewPager2 hero (BETTERSTREAMFLIX-13).
     */
    fun isolateFeatured(categories: List<Category>): List<Category> {
        val visible = categories.filter { it.list.isNotEmpty() }
        val ensured = ensureFeatured(visible, mutableListOf())
                return ensured.map { cat ->
            if (!Category.isFeaturedName(cat.name)) {
                cat
            } else {
                val featuredList = cloneShowItems(
                    cat.list.filter { it is Movie || it is TvShow }
                        .take(MAX_FEATURED_ITEMS),
                )
                // Preserve rotation index — callers often set selectedIndex before isolate.
                cat.copy(
                    name = Category.FEATURED,
                    list = featuredList,
                    selectedIndex = cat.selectedIndex
                        .coerceIn(0, (featuredList.size - 1).coerceAtLeast(0)),
                )
            }
        }.filter { it.list.isNotEmpty() }
    }

    /** Public: restore FEATURED after parental / live-DB filters drop it. */
    fun ensureFeaturedShelf(categories: List<Category>): List<Category> =
        ensureFeatured(categories, mutableListOf())

    private fun mergeDuplicateShelfNames(categories: List<Category>): List<Category> {
        val order = LinkedHashMap<String, MutableList<AppAdapter.Item>>()
        categories.forEach { category ->
            val key = if (Category.isFeaturedName(category.name)) {
                Category.FEATURED
            } else {
                category.name
            }
            val bucket = order.getOrPut(key) { mutableListOf() }
            val seen = bucket.mapNotNullTo(mutableSetOf()) { itemKey(it) }
            category.list.forEach { item ->
                val itemKey = itemKey(item)
                if (itemKey == null || seen.add(itemKey)) {
                    bucket.add(item)
                }
            }
        }
        return order.map { (name, items) ->
            val canonical = if (Category.isFeaturedName(name)) Category.FEATURED else name
            val cap = if (canonical == Category.FEATURED) {
                MAX_FEATURED_ITEMS
            } else {
                MAX_ITEMS_PER_CATEGORY
            }
            Category(name = canonical, list = items.take(cap))
        }
    }

    private fun itemKey(item: AppAdapter.Item): String? = when (item) {
        is Movie -> "m:${item.id}"
        is TvShow -> "t:${item.id}"
        is Episode -> "e:${item.id}"
        else -> null
    }

    /**
     * Turn protocol-relative / site-relative artwork into an absolute URL.
     * Leaves data:, blob:, and already-absolute http(s) URLs alone.
     */
    fun absoluteUrl(baseUrl: String, raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("data:", ignoreCase = true) ||
            value.startsWith("blob:", ignoreCase = true)
        ) {
            return value
        }
        if (value.startsWith("//")) {
            val scheme = runCatching { URI(baseUrl).scheme }.getOrNull()?.ifBlank { null } ?: "https"
            return "$scheme:$value"
        }
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return value
        }
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return value
        return if (value.startsWith("/")) "$base$value" else "$base/$value"
    }
}
