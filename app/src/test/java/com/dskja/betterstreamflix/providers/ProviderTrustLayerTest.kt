package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Genre
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.People
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderTrustLayerTest {

    private fun stubProvider(name: String): Provider = object : Provider {
        override val baseUrl = "https://example.test"
        override val name = name
        override val logo = ""
        override val language = "de"
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

    private val provider = stubProvider("SerienStream")

    @Before
    fun setUp() {
        ProviderServerPriority.memoryStore = mutableMapOf()
        ProviderServerAb.memoryStore = mutableMapOf()
        ProviderServerAb.clearSession()
        ServerWhy.clearSessionFlags()
    }

    @Test
    fun prioritizePreferredThenPinsThenAb() {
        ProviderServerPriority.pin("SerienStream", "vidoza")
        ProviderServerAb.memoryStore = mutableMapOf(
            "SerienStream" to mutableMapOf(
                "voe" to ProviderServerAb.Stats(successes = 10, failures = 0, lastSuccessAtMs = 1L),
                "streamtape" to ProviderServerAb.Stats(successes = 1, failures = 8, lastSuccessAtMs = 0L),
            ),
        )
        val servers = listOf(
            Video.Server(id = "1", name = "Streamtape"),
            Video.Server(id = "2", name = "Vidoza"),
            Video.Server(id = "3", name = "VOE"),
            Video.Server(id = "4", name = "Doodstream"),
        )
        val ordered = ProviderTrustLayer.prioritizeServers(
            provider = provider,
            servers = servers,
            preferredServerName = "Doodstream",
        )
        assertEquals("Doodstream", ordered[0].name)
        assertEquals("Vidoza", ordered[1].name)
        assertEquals("VOE", ordered[2].name)
    }

    @Test
    fun outageIgnoresQuarantinedAndParked() {
        val health = ProviderHealthDashboard.Snapshot(
            rows = listOf(
                ProviderHealthDashboard.Row(
                    name = "ParkedA",
                    provider = stubProvider("ParkedA"),
                    severity = ProviderHealthDashboard.Severity.DEAD,
                    reason = ProviderHealthDashboard.Reason.PARKED,
                    canUpdateDomain = false,
                ),
                ProviderHealthDashboard.Row(
                    name = "QuarantineB",
                    provider = stubProvider("QuarantineB"),
                    severity = ProviderHealthDashboard.Severity.DEAD,
                    reason = ProviderHealthDashboard.Reason.QUARANTINED,
                    canUpdateDomain = false,
                ),
                ProviderHealthDashboard.Row(
                    name = "QuarantineC",
                    provider = stubProvider("QuarantineC"),
                    severity = ProviderHealthDashboard.Severity.DEAD,
                    reason = ProviderHealthDashboard.Reason.QUARANTINED,
                    canUpdateDomain = false,
                ),
            ),
            deadCount = 3,
            slowCount = 0,
            okCount = 0,
            probedAtMs = 1L,
        )
        val passport = ProviderPassport.Snapshot(
            rows = emptyList(),
            freshCount = 0,
            captchaDueCount = 0,
            loginDueCount = 0,
            probedAtMs = 1L,
        )
        val snap = ProviderTrustLayer.snapshot(
            health = health,
            passport = passport,
            currentProvider = provider,
            nowMs = 1L,
        )
        assertEquals(ProviderTrustLayer.OutageLevel.NONE, snap.outageLevel)
        assertTrue(snap.outageProviderNames.isEmpty())
    }

    @Test
    fun titleCaseServerName_matchesHealthStyle() {
        assertEquals("Voe", ProviderTrustLayer.titleCaseServerName("voe"))
        assertEquals("Stream Tape", ProviderTrustLayer.titleCaseServerName("stream_tape"))
    }

    @Test
    fun snapshotOutageSevereWhenManyDead() {
        val health = ProviderHealthDashboard.Snapshot(
            rows = listOf("A", "B", "C").map { name ->
                ProviderHealthDashboard.Row(
                    name = name,
                    provider = stubProvider(name),
                    severity = ProviderHealthDashboard.Severity.DEAD,
                    reason = ProviderHealthDashboard.Reason.PROBE_FAILED,
                    canUpdateDomain = false,
                )
            },
            deadCount = 3,
            slowCount = 0,
            okCount = 0,
            probedAtMs = 1L,
        )
        val passport = ProviderPassport.Snapshot(
            rows = emptyList(),
            freshCount = 0,
            captchaDueCount = 0,
            loginDueCount = 0,
            probedAtMs = 1L,
        )
        val snap = ProviderTrustLayer.snapshot(
            health = health,
            passport = passport,
            currentProvider = provider,
            nowMs = 1L,
        )
        assertEquals(ProviderTrustLayer.OutageLevel.SEVERE, snap.outageLevel)
        assertEquals(3, snap.outageProviderNames.size)
        assertTrue(snap.hasIssues)
        assertTrue(ProviderOutagePolicy.shouldShow(snap, context = null))
    }
}
