package com.betterstreamflix.app.providers

import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Parse SerienStream / TV bypass resolve links from deep links and HTTP landing QR codes.
 *
 * Pure JVM (no Android APIs) so unit tests can cover the QR / deep-link matrix.
 *
 * Supported forms:
 * - `betterstreamflix://resolve?ws=…&token=…`
 * - `streamflix://resolve?ws=…&token=…`
 * - `http(s)://TV-IP:port/resolve?ws=…&token=…` (QR path)
 *
 * Security (SEC-DL-01 / DL-04): [ws] must target a LAN / private peer (or an explicit
 * advertised-host override). Public attacker WebSockets are rejected at parse time.
 */
object SerienStreamResolveLink {

    data class Target(
        val ws: String,
        val token: String,
    ) {
        fun toDeepLink(scheme: String = "betterstreamflix"): String {
            val encodedWs = java.net.URLEncoder.encode(ws, StandardCharsets.UTF_8.name())
            val encodedToken = java.net.URLEncoder.encode(token, StandardCharsets.UTF_8.name())
            return "$scheme://resolve?ws=$encodedWs&token=$encodedToken"
        }
    }

    fun parse(raw: String?, advertisedHostOverride: String = ""): Target? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null

        val scheme = value.substringBefore("://", missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        if (scheme.isBlank()) return null

        val afterScheme = value.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isBlank()) return null

        val authorityAndPath = afterScheme.substringBefore('?')
        val query = afterScheme.substringAfter('?', missingDelimiterValue = "")

        val target = when (scheme) {
            "betterstreamflix", "streamflix" -> {
                val host = authorityAndPath.substringBefore('/').substringBefore('?')
                if (!host.equals("resolve", ignoreCase = true)) return null
                fromQuery(query)
            }
            "http", "https" -> {
                // QR landing must be served from a private/LAN host (SEC-DL-04).
                val httpHost = authorityAndPath.substringBefore('/').substringBefore(':')
                if (!isPrivateOrLocalHost(httpHost) && !hostMatchesOverride(httpHost, advertisedHostOverride)) {
                    return null
                }
                val path = authorityAndPath.substringAfter('/', missingDelimiterValue = "")
                if (!path.contains("resolve", ignoreCase = true) &&
                    !authorityAndPath.contains("/resolve", ignoreCase = true)
                ) {
                    return null
                }
                fromQuery(query)
            }
            else -> null
        } ?: return null

        if (!isAllowedResolverWs(target.ws, advertisedHostOverride)) return null
        return target
    }

    fun isResolveLink(raw: String?, advertisedHostOverride: String = ""): Boolean =
        parse(raw, advertisedHostOverride) != null

    /**
     * Allow only `ws`/`wss` peers on loopback, RFC1918, link-local, or an explicit
     * advertised-host override (emulator / custom LAN mapping).
     */
    fun isAllowedResolverWs(ws: String, advertisedHostOverride: String = ""): Boolean {
        val value = ws.trim()
        if (value.isBlank()) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "ws" && scheme != "wss") return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (host.isBlank()) return false
        if (hostMatchesOverride(host, advertisedHostOverride)) return true
        return isPrivateOrLocalHost(host)
    }

    fun isPrivateOrLocalHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT).trim().removePrefix("[").removeSuffix("]")
        if (h.isBlank()) return false
        if (h == "localhost" || h.endsWith(".localhost") || h == "0.0.0.0") return true
        if (h == "::1") return true
        if (h.startsWith("127.")) return true
        if (h.startsWith("10.")) return true
        if (h.startsWith("192.168.")) return true
        if (h.startsWith("169.254.") && h != "169.254.169.254") return true
        if (h.startsWith("172.")) {
            val second = h.substringAfter("172.").substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        // IPv6 ULA fc00::/7
        if (h.startsWith("fc") || h.startsWith("fd")) {
            val first = h.substringBefore(':')
            if (first.length >= 2) {
                val nibble = first[1]
                if (nibble in 'c'..'f' || nibble in 'C'..'F') return true
            }
        }
        return runCatching {
            val inet = InetAddress.getByName(h)
            inet.isLoopbackAddress ||
                inet.isLinkLocalAddress ||
                inet.isSiteLocalAddress ||
                inet.isAnyLocalAddress
        }.getOrDefault(false)
    }

    private fun hostMatchesOverride(host: String, override: String): Boolean {
        val raw = override.trim()
        if (raw.isBlank()) return false
        val overrideHost = runCatching {
            val withScheme = if (raw.contains("://")) raw else "ws://$raw"
            URI(withScheme).host?.lowercase(Locale.ROOT)
        }.getOrNull()?.trim().orEmpty()
        if (overrideHost.isBlank()) return false
        return host.lowercase(Locale.ROOT) == overrideHost
    }

    private fun fromQuery(query: String): Target? {
        if (query.isBlank()) return null
        val params = linkedMapOf<String, String>()
        query.split('&').forEach { part ->
            if (!part.contains('=')) return@forEach
            val name = decode(part.substringBefore('=')).lowercase(Locale.ROOT)
            val value = decode(part.substringAfter('='))
            if (name.isNotBlank()) params[name] = value
        }
        val ws = params["ws"]?.trim().orEmpty()
        val token = params["token"]?.trim().orEmpty()
        if (ws.isBlank() || token.isBlank()) return null
        return Target(ws = ws, token = token)
    }

    private fun decode(value: String): String {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }
}
