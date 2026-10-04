package com.dskja.betterstreamflix.utils

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtractorHttpTest {

    @Test
    fun extractorTimeoutsStayInsideFailoverBudget() {
        val client = OkHttpClient.Builder().withExtractorTimeouts().build()
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
        assertEquals(20_000, client.callTimeoutMillis)
    }
}
