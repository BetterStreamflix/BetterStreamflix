package com.dskja.betterstreamflix.utils

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Vavoo-only TLS compatibility helper.
 *
 * A subset of Vavoo's resolved CDN hosts currently serves working HLS with an
 * expired certificate. This helper is only attached to the temporary player /
 * probe client created for a resolved Vavoo Live host. Normal BetterStreamflix
 * network traffic keeps the standard TLS validation path.
 */
object VavooTls {
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }.socketFactory
    }

    fun applyTo(builder: OkHttpClient.Builder, targetHost: String) {
        val host = targetHost.trim().lowercase()
        if (host.isBlank()) return

        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

        builder
            .sslSocketFactory(sslSocketFactory, trustManager)
            .hostnameVerifier { requestedHost, session ->
                requestedHost.equals(host, ignoreCase = true) ||
                    defaultVerifier.verify(requestedHost, session)
            }
    }

    fun relaxForHost(
        builder: OkHttpClient.Builder,
        allowedHost: String
    ): OkHttpClient.Builder {

        val normalHostnameVerifier =
            HttpsURLConnection.getDefaultHostnameVerifier()

        return builder
            .sslSocketFactory(
                sslSocketFactory,
                trustManager
            )
            .hostnameVerifier { hostname, session ->
                hostname.equals(
                    allowedHost,
                    ignoreCase = true
                ) &&
                    normalHostnameVerifier.verify(
                        hostname,
                        session
                    )
            }
    }

}
