package com.dskja.betterstreamflix.providers

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * HDFilme home markup after the hdfilme.ceo redesign (hd-slide / hd-c rows).
 * Catalog pages still use the older DLE grid.
 */
internal object HDFilmeHtml {

    data class Card(
        val href: String,
        val title: String,
        val image: String,
        val series: Boolean,
    )

    data class Row(
        val name: String,
        val cards: List<Card>,
    )

    fun parseSlides(document: Document): List<Card> {
        return document.select("div.hd-slide").mapNotNull { slide -> parseSlide(slide) }
            .distinctBy { it.href }
    }

    fun parseRows(document: Document): List<Row> {
        return document.select("section.hd-row, div.hd-row").mapNotNull { row ->
            val name = row.selectFirst(".hd-row-title")?.text()?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val seriesRow = name.contains("serien", ignoreCase = true)
            val cards = row.select("a.hd-c[href]").mapNotNull { card ->
                parseCard(card, seriesRow)
            }.distinctBy { it.href }
            if (cards.isEmpty()) return@mapNotNull null
            Row(name = name, cards = cards)
        }
    }

    private fun parseSlide(slide: Element): Card? {
        val href = slide.selectFirst("a.hd-slide-cta[href], a[href]")?.attr("href")?.trim().orEmpty()
        val title = slide.selectFirst("img.hd-slide-logo")?.attr("alt")?.trim().orEmpty()
            .ifBlank { slide.selectFirst(".hd-slide-bg img")?.attr("alt")?.trim().orEmpty() }
            .ifBlank { slide.selectFirst("a.hd-slide-cta")?.attr("title")?.trim().orEmpty() }
            .substringBefore(" Stream")
            .trim()
        if (href.isBlank() || title.isBlank()) return null
        val kind = slide.selectFirst(".hd-slide-kind")?.text().orEmpty()
        val image = slide.selectFirst(".hd-slide-bg img")?.attr("src")?.trim().orEmpty()
        return Card(
            href = href,
            title = title,
            image = image,
            series = kind.contains("serie", ignoreCase = true) ||
                href.contains("/serien/", ignoreCase = true),
        )
    }

    private fun parseCard(card: Element, seriesRow: Boolean): Card? {
        val href = card.attr("href").trim()
        val title = card.selectFirst(".hd-c-title, .hd-cw-title")?.text()?.trim().orEmpty()
            .ifBlank { card.attr("title").trim() }
            .ifBlank { card.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
        if (href.isBlank() || title.isBlank()) return null
        val image = card.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }.orEmpty().trim()
        val series = seriesRow || href.contains("/serien/", ignoreCase = true)
        return Card(href = href, title = title, image = image, series = series)
    }
}
