package com.betterstreamflix.app.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class NetworkThroughputHistoryTest {

    @Before
    fun setUp() {
        NetworkThroughputHistory.elapsedRealtimeMs = { 50_000L }
        NetworkThroughputHistory.clearForTest()
    }

    @After
    fun tearDown() {
        NetworkThroughputHistory.clearForTest()
        NetworkThroughputHistory.elapsedRealtimeMs = {
            try {
                android.os.SystemClock.elapsedRealtime()
            } catch (_: RuntimeException) {
                System.nanoTime() / 1_000_000L
            }
        }
    }

    @Test
    fun estimate_nullWhenCold() {
        assertNull(NetworkThroughputHistory.estimateBytesPerSec())
    }

    @Test
    fun estimate_returnsMedianAfterSamples() {
        NetworkThroughputHistory.seedForTest(
            bytesPerSec = 2_000_000L,
            count = 5,
            networkClass = NetworkThroughputHistory.NetworkClass.WIFI,
        )
        val estimate = NetworkThroughputHistory.estimateBytesPerSec(
            preferNetworkClass = NetworkThroughputHistory.NetworkClass.WIFI,
        )
        assertNotNull(estimate)
        assertEquals(2_000_000L, estimate)
    }

    @Test
    fun recordBytes_ignoresTinyTransfers() {
        NetworkThroughputHistory.recordBytes(
            bytes = 100L,
            durationMs = 50L,
            source = NetworkThroughputHistory.Source.DOWNLOAD,
            networkClass = NetworkThroughputHistory.NetworkClass.WIFI,
        )
        assertEquals(0, NetworkThroughputHistory.sampleCount())
        assertNull(NetworkThroughputHistory.estimateBytesPerSec())
    }
}
