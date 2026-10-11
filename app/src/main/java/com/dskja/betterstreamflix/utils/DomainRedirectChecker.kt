package com.dskja.betterstreamflix.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.Locale

object DomainRedirectChecker {

    data class Result(
        val originalUrl: String,
        val finalUrl: String,
        val changed: Boolean,
    )

    private val client by lazy {
        NetworkClient.default.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /** Host suffixes that are never valid provider homes (parking / interstitial). */
    private val BLOCKED_HOST_MARKERS = listOf(
        "parking",
        "sedo.com",
        "godaddy.com",
        "domainmarket.com",
        "afternic.com",
        "hugedomains.com",
        "dan.com",
        "cloudflare.com",
        "challenge.cloudflare",
    )

    private val PARKING_HTML_MARKERS = listOf(
        "domain is for sale",
        "buy this domain",
        "this domain is parked",
        "parked domain",
        "sedo parking",
        "related searches",
        "godaddy",
        "hugedomains",
    )

    suspend fun check(url: String): Result = withContext(Dispatchers.IO) {
        val normalized = normalizeInput(url)

        val request = Request.Builder()
            .url(normalized)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "HTTP ${response.code}"
                )
            }

            val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            val finalHttpUrl = response.request.url
            val finalHost = finalHttpUrl.host.removePrefix("www.").lowercase(Locale.US)

            if (isBlockedHost(finalHost)) {
                throw IllegalStateException(
                    "Redirect landed on blocked host: $finalHost",
                )
            }
            if (looksLikeParkingPage(body)) {
                throw IllegalStateException(
                    "Redirect landed on a parking / interstitial page ($finalHost)",
                )
            }

            val finalUrl = finalHttpUrl
                .newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .trimEnd('/')

            val originalBase = request.url
                .newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .trimEnd('/')

            Result(
                originalUrl = originalBase,
                finalUrl = finalUrl,
                changed = !sameHost(originalBase, finalUrl),
            )
        }
    }

    fun hostOnly(url: String): String {
        val normalized = normalizeInput(url)
        return normalized.toHttpUrl()
            .host
            .removePrefix("www.")
    }

    private fun isBlockedHost(host: String): Boolean {
        if (host.isBlank()) return true
        return BLOCKED_HOST_MARKERS.any { marker ->
            host == marker || host.endsWith(".$marker") || host.contains(marker)
        }
    }

    private fun looksLikeParkingPage(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase(Locale.US)
        // Tiny challenge shells are not provider homes.
        if (html.length < 400 && lower.contains("just a moment")) return true
        return PARKING_HTML_MARKERS.any { lower.contains(it) }
    }

    private fun normalizeInput(value: String): String {
        val trimmed = value.trim()

        if (
            trimmed.startsWith("http://") ||
            trimmed.startsWith("https://")
        ) {
            return trimmed
        }

        return "https://$trimmed"
    }

    private fun sameHost(first: String, second: String): Boolean {
        val a = first.toHttpUrl().host.removePrefix("www.")
        val b = second.toHttpUrl().host.removePrefix("www.")
        return a.equals(b, ignoreCase = true)
    }
}
