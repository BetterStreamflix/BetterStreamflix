package com.betterstreamflix.app.providers

import com.betterstreamflix.app.models.Video
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ServerWhyTest {

    @Before
    fun setUp() {
        ProviderServerPriority.memoryStore = mutableMapOf()
        ProviderServerAb.memoryStore = mutableMapOf()
        ProviderServerAb.clearSession()
        ServerWhy.clearSessionFlags()
    }

    @Test
    fun pinnedBeatsPreferredAndLearned() {
        ProviderServerPriority.pin("SerienStream", "vidoza")
        ProviderServerAb.memoryStore = mutableMapOf(
            "SerienStream" to mutableMapOf(
                "voe" to ProviderServerAb.Stats(successes = 10, failures = 0, lastSuccessAtMs = 1L),
            ),
        )
        val servers = listOf(
            Video.Server(id = "1", name = "VOE"),
            Video.Server(id = "2", name = "Vidoza"),
        )
        val reason = ServerWhy.reasonForServer(
            providerName = "SerienStream",
            server = servers[1],
            preferredServerName = "VOE",
            orderedServers = servers,
        )
        assertEquals(ServerWhy.Reason.PINNED, reason)
    }

    @Test
    fun failoverFlagWinsOnExplain() {
        ServerWhy.markFailoverHop()
        val server = Video.Server(id = "1", name = "VOE")
        val explanation = ServerWhy.explainSelection(
            providerName = "SerienStream",
            server = server,
            preferredServerName = null,
            orderedServers = listOf(server),
        )
        assertEquals(ServerWhy.Reason.FAILOVER, explanation.reason)
        assertEquals("VOE", explanation.serverName)
    }

    @Test
    fun stuckFlagWinsOverFailover() {
        ServerWhy.markStuckSwitch()
        val server = Video.Server(id = "1", name = "Vidoza")
        val explanation = ServerWhy.explainSelection(
            providerName = "SerienStream",
            server = server,
            preferredServerName = null,
            orderedServers = listOf(server),
        )
        assertEquals(ServerWhy.Reason.STUCK_SWITCH, explanation.reason)
    }

    @Test
    fun learnedLeadWhenTopOfAb() {
        ProviderServerAb.memoryStore = mutableMapOf(
            "SerienStream" to mutableMapOf(
                "voe" to ProviderServerAb.Stats(successes = 10, failures = 0, lastSuccessAtMs = 1L),
            ),
        )
        val servers = listOf(
            Video.Server(id = "1", name = "VOE"),
            Video.Server(id = "2", name = "Vidoza"),
        )
        val reason = ServerWhy.reasonForServer(
            providerName = "SerienStream",
            server = servers[0],
            preferredServerName = null,
            orderedServers = servers,
        )
        assertEquals(ServerWhy.Reason.LEARNED, reason)
    }
}
