package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItalianVixCatalogTest {

    @Test
    fun parseDetailItemClassifiesFilmAndTv() {
        val film = ItalianVixCatalog.parseDetailItem(
            "https://eurostreaming.design/",
            "/detail/film-19995-avatar",
            "Avatar",
            "https://eurostreaming.design/img/w200/x.jpg",
        )
        val movieAlias = ItalianVixCatalog.parseDetailItem(
            "https://guarda-serie.ovh/",
            "/detail/movie-19995-avatar",
            "Avatar",
            "",
        )
        val tv = ItalianVixCatalog.parseDetailItem(
            "https://eurostreaming.design/",
            "/detail/tv-1396-breaking-bad",
            "Breaking Bad",
            "",
        )

        assertTrue(film is Movie)
        assertTrue(movieAlias is Movie)
        assertTrue(tv is TvShow)
        assertEquals("https://eurostreaming.design/detail/film-19995-avatar", (film as Movie).id)
        assertEquals("https://eurostreaming.design/detail/tv-1396-breaking-bad", (tv as TvShow).id)
    }

    @Test
    fun extractTmdbIdFromDetailUrlAndScript() {
        val fromUrl = ItalianVixCatalog.extractTmdbId(
            Jsoup.parse("<html></html>"),
            "https://alta-definizione.beer/detail/film-969681-spider-man",
        )
        assertEquals(969681, fromUrl)

        val doc = Jsoup.parse(
            """
            <html><script>
              var playerBase = "https:\/\/vixsrc.to";
              var tmdbID =  1396 ;
              var mediaType = "tv";
            </script></html>
            """.trimIndent(),
        )
        assertEquals(1396, ItalianVixCatalog.extractTmdbId(doc, "https://x/detail/tv-0-x"))
        assertEquals("tv", ItalianVixCatalog.extractMediaType(doc.html(), "https://x/detail/tv-0-x"))
        assertEquals("https://vixsrc.to", ItalianVixCatalog.extractPlayerBase(doc.html()))
    }

    @Test
    fun seasonsAndServersFromEuroStyleDetail() {
        val html = """
            <html>
            <div class="es-dropdown seasons">
              <span data-season="1">Stagione 1</span>
              <span data-season="2">Stagione 2</span>
            </div>
            <div class="episode-group" data-group-season="1">
              <span data-episode="1-1">Episodio 1</span>
              <span data-episode="1-2">Episodio 2</span>
            </div>
            <script>
              var playerBase = "https:\/\/vixsrc.to";
              var tmdbID =  1396 ;
              var mediaType = "tv";
            </script>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val showId = "https://eurostreaming.design/detail/tv-1396-breaking-bad"
        val seasons = ItalianVixCatalog.seasonsFromDetail(doc, showId)
        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals("$showId|1", seasons.first().id)

        val servers = ItalianVixCatalog.serversFor(
            doc,
            "$showId|1|2",
            Video.Type.Episode(
                id = "$showId|1|2",
                number = 2,
                title = "Ep",
                poster = null,
                overview = null,
                tvShow = Video.Type.Episode.TvShow(
                    id = showId,
                    title = "Breaking Bad",
                    poster = null,
                    banner = null,
                    releaseDate = null,
                    imdbId = null,
                ),
                season = Video.Type.Episode.Season(number = 1, title = null),
            ),
        )
        assertEquals(1, servers.size)
        assertEquals("https://vixsrc.to/tv/1396/1/2?lang=it", servers.first().src)
    }

    @Test
    fun parseMovieCardsFromSearchHtml() {
        val html = """
            <div class="card-grid">
              <a href="/detail/film-19995-avatar" class="movie-card">
                <div class="movie-card-poster">
                  <img src="/img/w200/a.jpg" alt="Avatar">
                </div>
              </a>
              <a href="/detail/tv-82452-avatar-la-leggenda-di-aang" class="movie-card">
                <img src="/img/w200/b.jpg" alt="Avatar - La leggenda di Aang">
              </a>
            </div>
        """.trimIndent()
        val items = ItalianVixCatalog.parseMovieCards("https://alta-definizione.beer/", Jsoup.parse(html))
        assertEquals(2, items.size)
        assertTrue(items[0] is Movie)
        assertTrue(items[1] is TvShow)
    }
}
