package com.betterstreamflix.app.utils

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.logging.HttpLoggingInterceptor
import java.net.Inet4Address
import java.net.InetAddress

object DnsResolver : Dns {
    private const val TAG = "DnsResolver"
    private const val LOOKUP_TIMEOUT_MS = 8_000L
    private val lookupExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "dns-lookup").apply { isDaemon = true }
    }
    private val logging = HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)

    // Never read UserPreferences during object clinit — prefs may not be
    // set up yet (BETTERSTREAMFLIX-1Z). App.onCreate calls setDnsUrl().
    // SSL / OkHttp are lazy so class load stays cheap on the main thread
    // (BETTERSTREAMFLIX-2J App.onCreate ANR watch).
    private var _url: String = ""
    private var _internalDoh: Dns = Dns.SYSTEM

    @Volatile
    private var client: OkHttpClient? = null

    private fun httpClient(): OkHttpClient {
        client?.let { return it }
        synchronized(this) {
            client?.let { return it }
            // System trust store only — never trust-all (SS-04 / DoH MITM).
            return OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .apply {
                    if (com.betterstreamflix.app.BuildConfig.DEBUG) addInterceptor(logging)
                }
                .build()
                .also { client = it }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trim()
        // Guard empty / placeholder hosts (subtitle + extractor bad URLs → host "_").
        if (host.isEmpty() || host == "_" || host == "." || !host.any { it.isLetterOrDigit() }) {
            throw java.net.UnknownHostException("Invalid hostname: '$hostname'")
        }
        val providerName = if (_url.isEmpty()) "SYSTEM" else _url
        Log.d(TAG, "Resolving host: $host using provider: $providerName")
        val future = lookupExecutor.submit<List<InetAddress>> {
            lookupUncapped(host, providerName)
        }
        return try {
            future.get(LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            Log.e(TAG, "DNS lookup timed out for $hostname after ${LOOKUP_TIMEOUT_MS}ms")
            throw java.net.UnknownHostException("DNS lookup timed out for $hostname")
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            if (cause is Exception) throw cause
            throw e
        }
    }

    private fun lookupUncapped(hostname: String, providerName: String): List<InetAddress> {
        return try {
            val addresses = preferIpv4(_internalDoh.lookup(hostname))
            Log.d(TAG, "Resolved $hostname to: ${addresses.joinToString { it.hostAddress ?: "" }}")
            addresses
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve $hostname with $providerName: ${e.message}")
            if (_internalDoh === Dns.SYSTEM) {
                throw e
            }

            Log.w(TAG, "Falling back to system DNS for host: $hostname")
            val fallbackAddresses = preferIpv4(Dns.SYSTEM.lookup(hostname))
            Log.d(TAG, "System DNS resolved $hostname to: ${fallbackAddresses.joinToString { it.hostAddress ?: "" }}")
            fallbackAddresses
        }
    }

    /**
     * Prefer IPv4 when available. Broken/hijacked IPv6 routes are a common cause of
     * long connect timeouts on some ISP networks (including for api.themoviedb.org).
     */
    private fun preferIpv4(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size <= 1) return addresses
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        if (ipv4.isEmpty()) return addresses
        val ipv6 = addresses.filterNot { it is Inet4Address }
        return ipv4 + ipv6
    }

    val doh: Dns get() = this

    @Synchronized
    fun setDnsUrl(newUrl: String) {
        val sanitized = newUrl.trim().takeIf {
            it.isEmpty() || (
                (it.startsWith("http://") || it.startsWith("https://")) &&
                    runCatching { it.toHttpUrl().host }
                        .getOrNull()
                        ?.let { host -> host.isNotBlank() && host != "_" } == true
                )
        } ?: ""
        Log.i(TAG, "DNS Change Requested: New URL = '$sanitized' (Current = '$_url')")
        if (sanitized != _url) {
            _url = sanitized
            _internalDoh = buildDoh(_url)
            Log.i(TAG, "DNS Engine updated successfully to: ${if (sanitized.isEmpty()) "SYSTEM" else sanitized}")
        } else {
            Log.d(TAG, "DNS URL is the same as current, skipping update.")
        }
    }

    @Synchronized
    private fun buildDoh(url: String): Dns {
        return if (url.isNotEmpty()) {
            try {
                DnsOverHttps.Builder()
                    .client(httpClient())
                    .url(url.toHttpUrl())
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "Error building DoH for $url, falling back to SYSTEM: ${e.message}")
                Dns.SYSTEM
            }
        } else {
            Log.d(TAG, "No DoH URL provided, using SYSTEM DNS")
            Dns.SYSTEM
        }
    }
}
