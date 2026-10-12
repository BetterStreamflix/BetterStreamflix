package com.dskja.betterstreamflix.player

import com.dskja.betterstreamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverTimelineSessionTest {

    @Test
    fun recordOk_onlyOnceAndSuccessShowNeedsPriorFailure() {
        val session = FailoverTimelineSession()
        val servers = listOf(
            Video.Server(id = "1", name = "A"),
            Video.Server(id = "2", name = "B"),
        )
        session.recordOk(servers[0], servers)
        assertFalse(session.consumeSuccessShow())
        session.recordServerFailure(servers[0], servers, Exception("timeout"))
        session.recordOk(servers[1], servers)
        // Second OK ignored once one exists.
        session.recordOk(servers[1], servers)
        assertEquals(1, session.snapshot().count { it.kind == FailoverStepKind.OK })
        assertTrue(session.consumeSuccessShow())
        assertFalse(session.consumeSuccessShow())
    }

    @Test
    fun recordStuckHop_addsTimeoutStep() {
        val session = FailoverTimelineSession()
        val servers = listOf(Video.Server(id = "1", name = "A"))
        session.recordStuckHop(servers[0], servers)
        assertEquals(FailoverStepKind.TIMEOUT, session.snapshot().single().kind)
    }
}
