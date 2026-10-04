package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.TvShow
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GermanCatalogParseTest {

    @Test
    fun aniWorldHome_usesHeadingsNotNthChild() {
        val html = """
            <div class="container">
              <div><h2>Beliebt bei AniWorld</h2></div>
              <div class="previews">
                <div class="coverListItem">
                  <a href="/anime/stream/overgeared" title="Overgeared als Stream anschauen">
                    <h3>Overgeared</h3>
                    <img data-src="/public/img/cover/overgeared.png" src="data:image/gif;base64,xx">
                  </a>
                </div>
                <div class="coverListItem">
                  <a href="/anime/stream/"></a>
                </div>
              </div>
              <div><h2>Neue Animes</h2></div>
              <div class="previews">
                <div class="coverListItem">
                  <a href="/anime/stream/tank-chair"><h3>TANK CHAIR</h3></a>
                </div>
              </div>
              <div><h2>Derzeit beliebt</h2></div>
              <div class="previews">
                <div class="coverListItem">
                  <a href="/anime/stream/one-piece"><h3>One Piece</h3></a>
                </div>
              </div>
            </div>
        """.trimIndent()
        val shelves = AniWorldHtml.homeShelves(Jsoup.parse(html), "https://aniworld.to/")
        assertEquals(
            listOf("Beliebt bei AniWorld", "Neue Animes", "Derzeit beliebte Animes"),
            shelves.map { it.name },
        )
        val first = shelves.first().list.first() as TvShow
        assertEquals("overgeared", first.id)
        assertEquals("Overgeared", first.title)
        assertEquals("https://aniworld.to/public/img/cover/overgeared.png", first.poster)
        assertTrue(shelves.all { shelf ->
            shelf.list.all { item ->
                item is TvShow && item.id.isNotBlank() && item.title.isNotBlank()
            }
        })
    }

    @Test
    fun hdFilmeHome_readsCeoRowsAndSkipsBlanks() {
        val html = """
            <html><body>
              <div class="hd-slide">
                <img class="hd-slide-bg" src="https://img.hdfilme.ceo/banner.jpg" alt="Runner">
                <span class="hd-slide-kind">Film · 2026</span>
                <img class="hd-slide-logo" alt="Runner">
                <a class="hd-slide-cta" href="https://hdfilme.ceo/filme1/runner.html">Jetzt streamen</a>
              </div>
              <section class="hd-row">
                <h2 class="hd-row-title">Neu: Serien</h2>
                <a class="hd-c hd-c-v" href="https://hdfilme.ceo/serien/dark.html" title="Dark">
                  <img src="/uploads/dark.jpg" alt="Dark">
                  <div class="hd-c-title">Dark</div>
                </a>
                <a class="hd-c" href="https://hdfilme.ceo/filme1/blank.html"></a>
              </section>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val slides = HDFilmeHtml.parseSlides(doc)
        assertEquals(1, slides.size)
        assertEquals("Runner", slides.first().title)
        assertEquals("https://hdfilme.ceo/filme1/runner.html", slides.first().href)
        val rows = HDFilmeHtml.parseRows(doc)
        assertEquals(1, rows.size)
        assertEquals("Neu: Serien", rows.first().name)
        assertEquals(1, rows.first().cards.size)
        assertTrue(rows.first().cards.first().series)
        assertEquals("Dark", rows.first().cards.first().title)
    }

    @Test
    fun categoryListsStayNonEmptyOnlyWithTitles() {
        val shelves = AniWorldHtml.homeShelves(Jsoup.parse("<html></html>"), "https://aniworld.to/")
        assertTrue(shelves.all { it is Category && it.list.isNotEmpty() })
    }
}
