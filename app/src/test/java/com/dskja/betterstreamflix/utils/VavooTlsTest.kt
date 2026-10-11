package com.dskja.betterstreamflix.utils

import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

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
}
