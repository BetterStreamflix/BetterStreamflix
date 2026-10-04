package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.TvShow
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * AniWorld home shelves. Heading text is stable; nth-child positions are not.
 */
internal object AniWorldHtml {

    fun homeShelves(document: Document, baseUrl: String): List<Category> {
        val categories = mutableListOf<Category>()
        val used = mutableSetOf<String>()
        for (heading in document.select("h2")) {
            val name = shelfName(heading.text()) ?: continue
            if (!used.add(name)) continue
            val block = previewBlock(heading) ?: continue
            val shows = parseCovers(block, baseUrl)
            if (shows.isNotEmpty()) {
                categories.add(Category(name = name, list = shows))
            }
        }
        if (categories.isNotEmpty()) return categories

        val fallbackNames = listOf(
            "Beliebt bei AniWorld",
            "Neue Animes",
            "Derzeit beliebte Animes",
        )
        document.select("div.previews").take(fallbackNames.size).forEachIndexed { index, block ->
            val shows = parseCovers(block, baseUrl)
            if (shows.isNotEmpty()) {
                categories.add(Category(name = fallbackNames[index], list = shows))
            }
        }
        return categories
    }

    private fun shelfName(heading: String): String? {
        val text = heading.trim()
        if (text.isBlank()) return null
        return when {
            text.contains("Neue Animes", ignoreCase = true) -> "Neue Animes"
            text.contains("Derzeit beliebt", ignoreCase = true) -> "Derzeit beliebte Animes"
            text.contains("Beliebt bei AniWorld", ignoreCase = true) ||
                text.equals("Beliebt", ignoreCase = true) -> "Beliebt bei AniWorld"
            else -> null
        }
    }

    private fun previewBlock(heading: Element): Element? {
        heading.parent()?.nextElementSibling()?.takeIf { it.hasClass("previews") }?.let { return it }
        heading.nextElementSibling()?.takeIf { it.hasClass("previews") }?.let { return it }
        return heading.parent()?.parent()?.selectFirst("div.previews")
    }

    private fun parseCovers(block: Element, baseUrl: String): List<TvShow> {
        val root = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return block.select("div.coverListItem").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("href").orEmpty()
            val id = href.substringAfter("/anime/stream/").substringBefore('/').trim()
            val title = el.selectFirst("a h3, h3")?.text()?.trim().orEmpty()
                .ifBlank { el.selectFirst("a")?.attr("title")?.substringBefore(" als ")?.trim().orEmpty() }
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            val img = el.selectFirst("img")
            val raw = img?.attr("data-src")?.ifBlank { img.attr("src") }.orEmpty()
            val poster = when {
                raw.isBlank() || raw.startsWith("data:", ignoreCase = true) -> null
                raw.startsWith("http") -> raw
                else -> root + raw.removePrefix("/")
            }
            TvShow(id = id, title = title, poster = poster)
        }.distinctBy { it.id }
    }
}
