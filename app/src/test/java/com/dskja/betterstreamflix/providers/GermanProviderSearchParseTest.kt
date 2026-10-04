package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * German HTML search parsers must never emit blank id/title rows that crash Leanback.
 */
class GermanProviderSearchParseTest {

    @Test
    fun kinoGer_parseShorts_skipsBlankTitles() {
        val html = """
            <html><body>
              <div class="short">
                <div class="titlecontrol"><div class="title">
                  <a href="/film-ohne-titel.html"></a>
                </div></div>
              </div>
              <div class="short">
                <div class="titlecontrol"><div class="title">
                  <a href="/dark-2024.html">Dark (2024)</a>
                </div></div>
                <div class="content_text"><img src="/poster.jpg"/></div>
              </div>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://kinoger.fun/")
        val items = KinoGerHtml.parseShorts(doc) { path ->
            if (path.startsWith("http")) path else "https://kinoger.fun$path"
        }
        assertEquals(1, items.size)
        val show = items.first()
        assertTrue(show is Movie || show is TvShow)
        when (show) {
            is Movie -> {
                assertTrue(show.id.isNotBlank())
                assertTrue(show.title.isNotBlank())
            }
            is TvShow -> {
                assertTrue(show.id.isNotBlank())
                assertTrue(show.title.isNotBlank())
            }
            else -> error("unexpected")
        }
    }

    @Test
    fun serienStream_coverCards_requireTitleAndId() {
        val html = """
            <div class="search-results search-results-list">
              <div class="card cover-card">
                <a href="/serie/dark" class="d-block show-cover"></a>
                <h6 class="show-title mb-0 small">Dark</h6>
              </div>
              <div class="card cover-card">
                <a href="/serie/" class="d-block show-cover"></a>
                <h6 class="show-title mb-0 small"></h6>
              </div>
            </div>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val cards = doc.select("div.search-results-list div.card.cover-card")
        val parsed = cards.mapNotNull { card ->
            val link = card.selectFirst("a[href^=\"/serie/\"], a[href^=/serie/]")?.attr("href")
                ?: return@mapNotNull null
            val id = link.trim('/').substringAfter("serie/").substringBefore('/').trim()
            val title = card.selectFirst("h6.show-title")?.text()?.trim().orEmpty()
            if (id.isBlank() || title.isBlank()) null else id to title
        }
        assertEquals(listOf("dark" to "Dark"), parsed)
    }

    @Test
    fun kinoGer_buildSearchUrl_encodesQuery() {
        val url = KinoGerHtml.buildSearchUrl("https://kinoger.fun/", "Dark Matter", 1)
        assertTrue(url.contains("story=Dark+Matter") || url.contains("story=Dark%20Matter"))
        assertTrue(url.contains("do=search"))
    }
}
