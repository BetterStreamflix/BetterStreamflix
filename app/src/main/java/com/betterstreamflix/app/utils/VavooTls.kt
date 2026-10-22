package com.betterstreamflix.app.utils

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Vavoo-only TLS compatibility helper.
 *
 * A subset of Vavoo's resolved CDN hosts currently serves working HLS with an
 * expired certificate. This helper is only attached to the temporary player /
 * probe client created for a resolved Vavoo Live host. Normal BetterStreamflix
 * network traffic keeps the standard TLS validation path.
 *
 * Policy: host-scoped hostname verifier + **expiry-only** trust bypass (not
 * trust-all). Hard time-bomb stops the bypass after [RELAX_UNTIL_YEAR]/[RELAX_UNTIL_MONTH].
 */
object VavooTls {
    /** Stop relaxing TLS after this UTC month (exclusive upper bound: first of next month). */
    private const val RELAX_UNTIL_YEAR = 2027
    private const val RELAX_UNTIL_MONTH = Calendar.JANUARY // 0-based; January 2027

    private val defaultTrustManager: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            defaultTrustManager.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                defaultTrustManager.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                if (!isExpiryOnlyFailure(chain, e)) throw e
                // Allowlisted CDN host (hostnameVerifier) presented an expired-but-otherwise
                // acceptable chain — accept for Vavoo playback only.
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            defaultTrustManager.acceptedIssuers
    }

    private val sslSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }.socketFactory
    }

    fun isRelaxationActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(RELAX_UNTIL_YEAR, RELAX_UNTIL_MONTH, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return nowMs < cal.timeInMillis
    }

    /**
     * Host-scoped TLS relaxation shared by probe and Exo player clients.
     *
     * Accept when the peer hostname equals the allowlisted Vavoo CDN host
     * **or** the platform default verifier accepts the session (odd-SAN CDNs
     * that still present a valid cert for the requested name).
     */
    fun applyTo(builder: OkHttpClient.Builder, targetHost: String) {
        val host = targetHost.trim().lowercase()
        if (host.isBlank()) return
        if (!isRelaxationActive()) return

        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

        builder
            .sslSocketFactory(sslSocketFactory, trustManager)
            .hostnameVerifier { requestedHost, session ->
                requestedHost.equals(host, ignoreCase = true) ||
                    defaultVerifier.verify(requestedHost, session)
            }
    }

    /** Alias for [applyTo] — keep one policy for probe + player. */
    fun relaxForHost(
        builder: OkHttpClient.Builder,
        allowedHost: String
    ): OkHttpClient.Builder {
        applyTo(builder, allowedHost)
        return builder
    }

    private fun isExpiryOnlyFailure(
        chain: Array<X509Certificate>,
        error: CertificateException,
    ): Boolean {
        val messages = generateSequence(error as Throwable) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        val namedExpiry = generateSequence(error as Throwable) { it.cause }
            .any { it is CertificateExpiredException || it is CertificateNotYetValidException }
        if (namedExpiry) return true
        if ("expired" !in messages && "not yet valid" !in messages) return false
        if (chain.isEmpty()) return false
        // Leaf must fail validity only for expiry / not-yet-valid.
        return try {
            chain[0].checkValidity()
            false
        } catch (_: CertificateExpiredException) {
            true
        } catch (_: CertificateNotYetValidException) {
            true
        } catch (_: Exception) {
            false
        }
    }
}
