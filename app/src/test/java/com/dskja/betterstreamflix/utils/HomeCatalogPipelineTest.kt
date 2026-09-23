package com.dskja.betterstreamflix.utils

import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogPipelineTest {

    private val provider = object : Provider {
        override val baseUrl = "https://example.com"
        override val name = "TestProvider"
        override val logo = ""
        override val language = "en"
        override suspend fun getHome() = emptyList<Category>()
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

    @Test
    fun stampsProviderNameAndAbsoluteArtwork() {
        val movie = Movie(
            id = "1",
            title = "One",
            poster = "/img/a.jpg",
            banner = "//cdn.example.com/b.jpg",
        )
        val result = HomeCatalogPipeline.process(
            provider,
            listOf(Category(name = "Latest", list = listOf(movie))),
        )
        val featured = result.categories.first { it.name == Category.FEATURED }
        val stamped = featured.list.filterIsInstance<Movie>().first()
        assertEquals("TestProvider", stamped.providerName)
        assertEquals("https://example.com/img/a.jpg", stamped.poster)
        assertEquals("https://cdn.example.com/b.jpg", stamped.banner)
        assertTrue(result.warnings.any { it.contains("Featured") })
    }

    @Test
    fun dropsEmptyShelvesAndDedupes() {
        val a = Movie(id = "a", title = "A")
        val dup = Movie(id = "a", title = "A again")
        val result = HomeCatalogPipeline.process(
            provider,
            listOf(
                Category(name = Category.FEATURED, list = listOf(a, dup)),
                Category(name = "Empty", list = emptyList()),
                Category(name = "Latest", list = listOf(a)),
            ),
        )
        assertNull(result.categories.find { it.name == "Empty" })
        val featured = result.categories.first { it.name == Category.FEATURED }
        assertEquals(1, featured.list.size)
        assertNull(result.warningText)
    }

    @Test
    fun absoluteUrlHelpers() {
        assertEquals(
            "https://site.test/p.png",
            HomeCatalogPipeline.absoluteUrl("https://site.test/", "p.png"),
        )
        assertEquals(
            "https://cdn.test/x.png",
            HomeCatalogPipeline.absoluteUrl("https://site.test", "//cdn.test/x.png"),
        )
        assertEquals(
            "https://already.test/z.png",
            HomeCatalogPipeline.absoluteUrl("https://site.test", "https://already.test/z.png"),
        )
        assertNull(HomeCatalogPipeline.absoluteUrl("https://site.test", "  "))
        assertNotNull(HomeCatalogPipeline.absoluteUrl("", "relative.jpg"))
    }

    @Test
    fun neverPromotesDemoAddonTipIntoFeatured() {
        val tip = Movie(id = "demo-addon-tip", title = "Plugin system ready")
        val real = Movie(id = "real-1", title = "Real Movie")
        val result = HomeCatalogPipeline.process(
            provider,
            listOf(
                Category(name = "BetterStreamflix Addons", list = listOf(tip)),
                Category(name = "Latest", list = listOf(real)),
            ),
        )
        val featured = result.categories.first { it.name == Category.FEATURED }
        assertTrue(featured.list.none { it is Movie && it.id == "demo-addon-tip" })
        assertTrue(featured.list.any { it is Movie && it.id == "real-1" })
    }

    @Test
    fun mergesDuplicateShelfNames() {
        val a = Movie(id = "1", title = "One")
        val b = Movie(id = "2", title = "Two")
        val result = HomeCatalogPipeline.process(
            provider,
            listOf(
                Category(name = Category.FEATURED, list = listOf(a)),
                Category(name = "Hits", list = listOf(a)),
                Category(name = "Hits", list = listOf(b)),
            ),
        )
        val hits = result.categories.filter { it.name == "Hits" }
        assertEquals(1, hits.size)
        assertEquals(2, hits.first().list.size)
    }

    @Test
    fun featuredItemsAreClonedFromSharedProviderRefs() {
        val shared = Movie(id = "shared", title = "Shared")
        val result = HomeCatalogPipeline.process(
            provider,
            listOf(
                Category(name = Category.FEATURED, list = listOf(shared)),
                Category(name = "Latest", list = listOf(shared)),
            ),
        )
        val featured = result.categories.first { it.name == Category.FEATURED }
            .list.filterIsInstance<Movie>().first()
        val latest = result.categories.first { it.name == "Latest" }
            .list.filterIsInstance<Movie>().first()
        assertTrue(
            "FEATURED must not share identity with shelf rows (BETTERSTREAMFLIX-13)",
            featured !== latest && featured !== shared,
        )
        assertEquals("shared", featured.id)
        assertEquals("shared", latest.id)
    }

    @Test
    fun isolateFeaturedAlwaysClonesAndKeepsShelfRows() {
        val shared = Movie(id = "hero", title = "Hero")
        val isolated = HomeCatalogPipeline.isolateFeatured(
            listOf(
                Category(name = "Latest", list = listOf(shared)),
            ),
        )
        val featured = isolated.first { it.name == Category.FEATURED }
            .list.filterIsInstance<Movie>().first()
        val latest = isolated.first { it.name == "Latest" }
            .list.filterIsInstance<Movie>().first()
        assertTrue(featured !== latest)
        assertTrue(featured !== shared)
        assertEquals("hero", featured.id)
        latest.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
        featured.itemType = AppAdapter.Type.MOVIE_SWIPER_MOBILE_ITEM
        assertTrue(latest.itemType != featured.itemType)
    }

    @Test
    fun isolateFeaturedPreservesSelectedIndex() {
        val items = listOf(
            Movie(id = "a", title = "A"),
            Movie(id = "b", title = "B"),
            Movie(id = "c", title = "C"),
        )
        val source = Category(name = Category.FEATURED, list = items).also {
            it.selectedIndex = 2
        }
        val isolated = HomeCatalogPipeline.isolateFeatured(listOf(source))
        val featured = isolated.first { Category.isFeaturedName(it.name) }
        assertEquals(2, featured.selectedIndex)
    }

    @Test
    fun blankLegacyFeaturedNameNormalizesToConstant() {
        val movie = Movie(id = "1", title = "Legacy")
        val result = HomeCatalogPipeline.process(
            provider,
            listOf(Category(name = "", list = listOf(movie))),
        )
        val featured = result.categories.first { Category.isFeaturedName(it.name) }
        assertEquals(Category.FEATURED, featured.name)
        assertEquals("Featured", Category.FEATURED)
        assertTrue(Category.isFeaturedName(""))
        assertTrue(Category.isFeaturedName("featured"))
        assertTrue(!Category.isFeaturedName("Latest"))
    }

    @Test
    fun featuredNamedShelfIsCanonicalized() {
        val movie = Movie(id = "2", title = "Named")
        val result = HomeCatalogPipeline.process(
            provider,
            listOf(Category(name = "  Featured  ", list = listOf(movie))),
        )
        val featured = result.categories.first { it.name == Category.FEATURED }
        assertEquals(1, featured.list.size)
        assertEquals("2", (featured.list.first() as Movie).id)
    }

    @Test
    fun longFeaturedNamedShelfIsNotCollapsed() {
        val movie = Movie(id = "3", title = "Keep")
        val result = HomeCatalogPipeline.process(
            provider,
            listOf(Category(name = "Featured Hits Collection 2024", list = listOf(movie))),
        )
        // Long multi-word "Featured …" shelves stay distinct; Featured is synthesized from them.
        assertTrue(result.categories.any { it.name == "Featured Hits Collection 2024" })
        assertTrue(result.categories.any { it.name == Category.FEATURED })
    }

    @Test
    fun absoluteUrlAlsoNormalizesLogo() {
        val movie = Movie(id = "l", title = "Logo").apply { logo = "/logo.png" }
        val result = HomeCatalogPipeline.process(
            provider,
            listOf(Category(name = Category.FEATURED, list = listOf(movie))),
        )
        val featured = result.categories.first { it.name == Category.FEATURED }
            .list.filterIsInstance<Movie>().first()
        assertEquals("https://example.com/logo.png", featured.logo)
    }
}
