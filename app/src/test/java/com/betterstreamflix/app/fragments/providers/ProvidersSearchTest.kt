package com.betterstreamflix.app.fragments.providers

import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Genre
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.People
import com.betterstreamflix.app.models.Provider as ModelProvider
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersSearchTest {

    private fun stub(name: String): ModelProvider {
        val provider = object : Provider {
            override val baseUrl = "https://example.com"
            override val name = name
            override val logo = ""
            override val language = "en"
            override suspend fun getHome() = emptyList<com.betterstreamflix.app.models.Category>()
            override suspend fun search(query: String, page: Int) = emptyList<AppAdapter.Item>()
            override suspend fun getMovies(page: Int) = emptyList<Movie>()
            override suspend fun getTvShows(page: Int) = emptyList<TvShow>()
            override suspend fun getMovie(id: String) = Movie(id = id, title = id)
            override suspend fun getTvShow(id: String) = TvShow(id = id, title = id)
            override suspend fun getEpisodesBySeason(seasonId: String) = emptyList<Episode>()
            override suspend fun getGenre(id: String, page: Int) = Genre(id = id, name = "")
            override suspend fun getPeople(id: String, page: Int) = People(id = id, name = "")
            override suspend fun getServers(id: String, videoType: Video.Type) = emptyList<Video.Server>()
            override suspend fun getVideo(server: Video.Server) = Video(source = "")
        }
        return ModelProvider(
            name = name,
            logo = "",
            language = "en",
            provider = provider,
        )
    }

    @Test
    fun emptyQueryKeepsOrder() {
        val list = listOf(stub("AniWorld"), stub("SerienStream"), stub("TMDb (English)"))
        assertEquals(list, ProvidersSearch.filter(list, "  "))
    }

    @Test
    fun filtersByNameCaseInsensitive() {
        val list = listOf(stub("AniWorld"), stub("SerienStream"), stub("AnimeSaturn"))
        val filtered = ProvidersSearch.filter(list, "ani")
        assertEquals(listOf("AniWorld", "AnimeSaturn"), filtered.map { it.name })
    }

    @Test
    fun noMatchReturnsEmpty() {
        val list = listOf(stub("AniWorld"), stub("SerienStream"))
        assertTrue(ProvidersSearch.filter(list, "zzz").isEmpty())
    }

    @Test
    fun foldsGermanDiacritics() {
        val list = listOf(stub("Größe"), stub("Müller"), stub("KinoGer"))
        assertEquals(listOf("Größe"), ProvidersSearch.filter(list, "grosse").map { it.name })
        assertEquals(listOf("Müller"), ProvidersSearch.filter(list, "muller").map { it.name })
    }

    @Test
    fun focusDownAvoidsEmptyList() {
        assertEquals(ProvidersSearch.FocusDown.LIST, ProvidersSearch.focusDown(true, false))
        assertEquals(ProvidersSearch.FocusDown.EMPTY_CTA, ProvidersSearch.focusDown(false, true))
        assertEquals(ProvidersSearch.FocusDown.STAY, ProvidersSearch.focusDown(false, false))
    }
}
