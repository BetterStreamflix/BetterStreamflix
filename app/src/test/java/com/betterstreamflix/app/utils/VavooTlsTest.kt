package com.betterstreamflix.app.utils

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class VavooTlsTest {

    @Test
    fun applyToAndRelaxForHostSharePolicy() {
        val a = OkHttpClient.Builder()
        val b = OkHttpClient.Builder()
        VavooTls.applyTo(a, "cdn.example.vavoo")
        val returned = VavooTls.relaxForHost(b, "cdn.example.vavoo")
        assertSame(b, returned)
        val clientA = a.build()
        val clientB = b.build()
        assertNotNull(clientA.hostnameVerifier)
        assertNotNull(clientB.hostnameVerifier)
        // Both must accept the allowlisted host even if the default verifier would not
        // (SAN-mismatch CDN). Exact session verification needs a real SSLSession —
        // here we only assert the shared builder path does not throw / null out.
        assertNotNull(clientA.sslSocketFactory)
        assertNotNull(clientB.sslSocketFactory)
    }

    @Test
    fun relaxationActiveBeforeTimeBomb() {
        assertTrue(VavooTls.isRelaxationActive(System.currentTimeMillis()))
        val past = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2030, Calendar.JANUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertFalse(VavooTls.isRelaxationActive(past))
    }
}
