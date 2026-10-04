package com.dskja.betterstreamflix.platform.playerbackend

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerBackendSelectorTest {
    @Test
    fun candidatePackagesPreferMpvFirst() {
        assertEquals("is.xyz.mpv", ExternalMpvBackend.CANDIDATE_PACKAGES.first())
        assertEquals(2, ExternalMpvBackend.CANDIDATE_PACKAGES.size)
        assertEquals(false, ExternalMpvBackend.CANDIDATE_PACKAGES.contains("org.videolan.vlc"))
    }
}
