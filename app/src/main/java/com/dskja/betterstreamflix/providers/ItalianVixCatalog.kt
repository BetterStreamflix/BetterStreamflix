package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.Show
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.TmdbUtils
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Shared helpers for Italian catalogs that serve TMDb-backed VixSrc embeds
 * under `/detail/film-|movie-|tv-{tmdbId}-slug` (Eurostreaming, Altadefinizione
 * beer stack, GuardaSerie-compatible mirrors).
 */
internal object ItalianVixCatalog {

    const val VIXSRC = "https://vixsrc.to"

    fun absUrl(baseUrl: String, path: String): String {
        return when {
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            else -> baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        }
    }

    fun parseDetailItem(
        baseUrl: String,
        href: String,
        title: String,
        poster: String = "",
        rating: Double? = null,
    ): Show? {
        val absolute = absUrl(baseUrl, href)
        if (!absolute.contains("/detail/")) return null
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return null
        return if (
            absolute.contains("/detail/film-") ||
            absolute.contains("/detail/movie-")
        ) {
            Movie(id = absolute, title = cleanTitle, poster = poster, rating = rating)
        } else if (absolute.contains("/detail/tv-")) {
            TvShow(id = absolute, title = cleanTitle, poster = poster, rating = rating)
        } else {
            null
        }
    }

    fun parsePostThumbItems(baseUrl: String, root: Element): List<Show> {
        return root.select(".list.post, div.post-thumb").mapNotNull { el ->
            val a = el.selectFirst("a[href*=/detail/]") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = a.attr("title").ifBlank {
                el.selectFirst("h2 a, .post-content a")?.text()
            }.orEmpty().ifBlank {
                a.selectFirst("img")?.attr("alt").orEmpty()
            }
            val poster = a.selectFirst("img")?.attr("src")?.let { absUrl(baseUrl, it) }.orEmpty()
            parseDetailItem(baseUrl, href, title, poster)
        }.distinctBy { showId(it) }
    }

    fun parseMovieCards(baseUrl: String, root: Element): List<Show> {
        return root.select("a.movie-card[href*=/detail/], .movie-card a[href*=/detail/]").mapNotNull { a ->
            val href = a.attr("href")
            val title = a.selectFirst("img")?.attr("alt")?.ifBlank { null }
                ?: a.attr("title").ifBlank { a.text() }
            val poster = a.selectFirst("img")?.attr("src")?.let { absUrl(baseUrl, it) }.orEmpty()
            parseDetailItem(baseUrl, href, title, poster)
        }.distinctBy { showId(it) }
    }

    fun parseDetailLinks(baseUrl: String, root: Element): List<Show> {
        return root.select("a[href*=/detail/]").mapNotNull { a ->
            val href = a.attr("href")
            val title = a.attr("title").ifBlank {
                a.selectFirst("img")?.attr("alt")
            }.orEmpty().ifBlank { a.text() }
            if (title.isBlank() || title.equals("Riproduci", true) ||
                title.equals("Altre info", true) || title.equals("Guarda ora", true)
            ) {
                return@mapNotNull null
            }
            val poster = a.selectFirst("img")?.attr("src")?.let { absUrl(baseUrl, it) }.orEmpty()
            parseDetailItem(baseUrl, href, title, poster)
        }.distinctBy { showId(it) }
    }

    fun extractTmdbId(doc: Document, id: String): Int? {
        Regex("""tmdbID\s*=\s*(\d+)""").find(doc.html())?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?.let { return it }
        Regex("""/detail/(?:film|movie|tv)-(\d+)""").find(id)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?.let { return it }
        return null
    }

    fun extractPlayerBase(html: String): String {
        return Regex("""playerBase(?:URL)?\s*=\s*"([^"]+)"""")
            .find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?: VIXSRC
    }

    fun extractMediaType(html: String, id: String): String {
        Regex("""mediaType\s*=\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)?.let { return it }
        return when {
            id.contains("/detail/film-") || id.contains("/detail/movie-") -> "movie"
            else -> "tv"
        }
    }

    fun seasonsFromDetail(doc: Document, showId: String): List<Season> {
        val seasons = doc.select(".es-dropdown.seasons [data-season], [data-season]")
            .mapNotNull { it.attr("data-season").toIntOrNull() }
            .distinct()
            .sorted()
        if (seasons.isNotEmpty()) {
            return seasons.map { num -> Season(id = "$showId|$num", number = num) }
        }
        val fromEpisodes = doc.select("[data-episode]")
            .mapNotNull { el ->
                el.attr("data-episode").substringBefore("-").toIntOrNull()
            }
            .distinct()
            .sorted()
        return fromEpisodes.map { num -> Season(id = "$showId|$num", number = num) }
    }

    suspend fun episodesForSeason(
        doc: Document,
        showId: String,
        seasonNum: Int,
        language: String,
    ): List<Episode> {
        val title = doc.selectFirst("h1, h2, .section-title h2, .es-detail-grid h2")
            ?.text()?.trim().orEmpty()
        val tmdbTvShow = TmdbUtils.getTvShow(cleanTitle(title), language = language)
        val tmdbEpisodes = if (tmdbTvShow != null) {
            TmdbUtils.getEpisodesBySeason(tmdbTvShow.id, seasonNum, language = language)
        } else {
            emptyList()
        }

        val episodeEls = doc.select("[data-episode^=$seasonNum-], [data-episode]")
            .filter { el ->
                val raw = el.attr("data-episode")
                raw.startsWith("$seasonNum-") || raw.substringBefore("-") == seasonNum.toString()
            }

        return episodeEls.mapNotNull { el ->
            val raw = el.attr("data-episode")
            val epNumber = raw.substringAfterLast("-").toIntOrNull() ?: return@mapNotNull null
            val tmdbEp = tmdbEpisodes.find { it.number == epNumber }
            Episode(
                id = "$showId|$seasonNum|$epNumber",
                number = epNumber,
                title = tmdbEp?.title ?: el.text().ifBlank { "Episodio $epNumber" },
                poster = tmdbEp?.poster,
                overview = tmdbEp?.overview,
            )
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    fun serversFor(
        doc: Document,
        id: String,
        videoType: Video.Type,
    ): List<Video.Server> {
        if (id.count { it == '|' } >= 2) {
            val parts = id.split("|")
            val showUrl = parts[0]
            val season = parts.getOrNull(1)?.toIntOrNull()
            val episode = parts.getOrNull(2)?.toIntOrNull()
            if (season != null && episode != null) {
                val tmdbId = extractTmdbId(doc, showUrl)
                val playerBase = extractPlayerBase(doc.html())
                if (tmdbId != null) {
                    val src = "$playerBase/tv/$tmdbId/$season/$episode?lang=it"
                    return listOf(Video.Server(id = src, name = "VixSrc", src = src))
                }
            }
        }

        val html = doc.html()
        val playerBase = extractPlayerBase(html)
        val tmdbId = extractTmdbId(doc, id) ?: return emptyList()
        val mediaType = extractMediaType(html, id)
        val src = when (mediaType) {
            "movie" -> "$playerBase/movie/$tmdbId?lang=it"
            else -> {
                val (season, episode) = when (videoType) {
                    is Video.Type.Episode -> videoType.season.number to videoType.number
                    else -> 1 to 1
                }
                "$playerBase/tv/$tmdbId/$season/$episode?lang=it"
            }
        }
        // Prefer iframe src when already present on movie pages
        val iframeSrc = doc.selectFirst("iframe#player-iframe[src], iframe[src*=vixsrc]")
            ?.attr("src")?.takeIf { it.isNotBlank() }
        val finalSrc = iframeSrc?.takeIf { it.contains("vixsrc", true) } ?: src
        return listOf(Video.Server(id = finalSrc, name = "VixSrc", src = finalSrc))
    }

    fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .replace(Regex("""\s*\|\s*.*$"""), "")
            .trim()
    }

    fun showId(show: Show): String = when (show) {
        is Movie -> show.id
        is TvShow -> show.id
    }
}
