package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthDashboardTest {

    private fun stubProvider(name: String, configUrl: Boolean = false): Provider {
        if (!configUrl) {
            return object : Provider {
                override val baseUrl = "https://example.test/"
                override val name = name
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
                override suspend fun getServers(id: String, videoType: Video.Type) =
                    emptyList<Video.Server>()
                override suspend fun getVideo(server: Video.Server) = Video(source = "")
            }
        }
        return object : Provider, ProviderConfigUrl {
            override val baseUrl = "https://example.test/"
            override val name = name
            override val logo = ""
            override val language = "en"
            override val defaultBaseUrl = baseUrl
            override val changeUrlMutex = Mutex()
            override suspend fun onChangeUrl(forceRefresh: Boolean) = defaultBaseUrl
            override suspend fun getHome() = emptyList<Category>()
            override suspend fun search(query: String, page: Int) = emptyList<AppAdapter.Item>()
            override suspend fun getMovies(page: Int) = emptyList<Movie>()
            override suspend fun getTvShows(page: Int) = emptyList<TvShow>()
            override suspend fun getMovie(id: String) = Movie(id = id, title = id)
            override suspend fun getTvShow(id: String) = TvShow(id = id, title = id)
            override suspend fun getEpisodesBySeason(seasonId: String) = emptyList<Episode>()
            override suspend fun getGenre(id: String, page: Int) = Genre(id = id, name = "")
            override suspend fun getPeople(id: String, page: Int) = People(id = id, name = "")
            override suspend fun getServers(id: String, videoType: Video.Type) =
                emptyList<Video.Server>()
            override suspend fun getVideo(server: Video.Server) = Video(source = "")
        }
    }

    @Test
    fun snapshot_marksQuarantinedAndParkedAsDead() {
        val snapshot = ProviderHealthDashboard.snapshotFromRuntime(
            providers = listOf(
                stubProvider("StreamingIta", configUrl = true),
                stubProvider("AnyMovie", configUrl = true),
                stubProvider("SportsBite", configUrl = true),
                stubProvider("HealthyProbe", configUrl = true),
            ),
        )
        val byName = snapshot.rows.associateBy { it.name }
        assertEquals(ProviderHealthDashboard.Severity.DEAD, byName.getValue("StreamingIta").severity)
        assertEquals(ProviderHealthDashboard.Reason.PARKED, byName.getValue("StreamingIta").reason)
        assertEquals(ProviderHealthDashboard.Severity.DEAD, byName.getValue("AnyMovie").severity)
        assertEquals(ProviderHealthDashboard.Severity.DEAD, byName.getValue("SportsBite").severity)
        assertEquals(
            ProviderHealthDashboard.Reason.QUARANTINED,
            byName.getValue("SportsBite").reason,
        )
        assertEquals(ProviderHealthDashboard.Severity.OK, byName.getValue("HealthyProbe").severity)
        assertFalse(byName.getValue("StreamingIta").canUpdateDomain)
        assertTrue(snapshot.deadCount >= 3)
    }

    @Test
    fun snapshot_probeFailureOverridesHealthyRuntime() {
        val probes = mapOf(
            "KinoGer" to ProviderHealthDashboard.ProbeOutcome(
                ok = false,
                latencyMs = 1200L,
                detail = "HTTP 503",
            ),
        )
        val snapshot = ProviderHealthDashboard.snapshotFromRuntime(
            providers = listOf(stubProvider("KinoGer", configUrl = true)),
            probes = probes,
        )
        val row = snapshot.rows.single()
        assertEquals(ProviderHealthDashboard.Severity.DEAD, row.severity)
        assertEquals(ProviderHealthDashboard.Reason.PROBE_FAILED, row.reason)
        assertTrue(row.canUpdateDomain)
    }

    @Test
    fun snapshot_slowProbeMarksSlow() {
        val probes = mapOf(
            "KinoGer" to ProviderHealthDashboard.ProbeOutcome(
                ok = true,
                latencyMs = ProviderHealthDashboard.SLOW_PROBE_MS + 500L,
                detail = null,
            ),
        )
        val snapshot = ProviderHealthDashboard.snapshotFromRuntime(
            providers = listOf(stubProvider("KinoGer", configUrl = true)),
            probes = probes,
        )
        val row = snapshot.rows.single()
        assertEquals(ProviderHealthDashboard.Severity.SLOW, row.severity)
        assertEquals(ProviderHealthDashboard.Reason.PROBE_SLOW, row.reason)
        assertTrue(row.canUpdateDomain)
    }

    @Test
    fun deadRowsSortBeforeHealthy() {
        val snapshot = ProviderHealthDashboard.snapshotFromRuntime(
            providers = listOf(
                stubProvider("ZebraHealthy"),
                stubProvider("StreamingIta", configUrl = true),
                stubProvider("KinoGer", configUrl = true),
            ),
        )
        assertEquals("StreamingIta", snapshot.rows.first().name)
        assertEquals(ProviderHealthDashboard.Severity.DEAD, snapshot.rows.first().severity)
    }
}
