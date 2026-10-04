package com.dskja.betterstreamflix.extractors

/**
 * VOE pages often include a CDN stylesheet before the real player hop.
 * Playback has to follow location/meta refresh, not the first https host.
 */
internal object VoeRedirect {
    private val ignoredHosts = listOf(
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        "ajax.googleapis.com",
        "www.google.com",
        "www.gstatic.com",
        "cdnjs.cloudflare.com",
        "cdn.jsdelivr.net",
        "code.jquery.com",
        "stackpath.bootstrapcdn.com",
        "maxcdn.bootstrapcdn.com",
        "unpkg.com",
        "www.googletagmanager.com",
        "www.google-analytics.com",
        "ssl.google-analytics.com",
    )

    fun baseUrl(html: String, currentHost: String): String? {
        val explicit = listOf(
            Regex("""(?i)(?:window\.)?location(?:\.href)?\s*(?:=|\.replace\s*\()\s*['"](https?://[^'"]+)"""),
            Regex("""(?i)http-equiv\s*=\s*['"]refresh['"][^>]*url\s*=\s*(https?://[^'">\s]+)"""),
            Regex("""(?i)url\s*=\s*(https?://[^'">\s]+)[^'"]*['"][^>]*http-equiv\s*=\s*['"]refresh"""),
        ).firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.getOrNull(1)
        }
        if (explicit != null) return hostBase(explicit)

        val current = currentHost.lowercase().removePrefix("www.")
        val picked = Regex("""https://([a-zA-Z0-9.-]+)""")
            .findAll(html)
            .map { it.groupValues[1] }
            .firstOrNull { host ->
                val normalized = host.lowercase().removePrefix("www.")
                normalized != current && ignoredHosts.none { ignored ->
                    normalized == ignored || normalized.endsWith(".$ignored")
                }
            }
        return picked?.let { "https://$it/" }
    }

    private fun hostBase(url: String): String? {
        val host = Regex("""https?://([a-zA-Z0-9.-]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        return "https://$host/"
    }
}

/**
 * AniWorld labels VOE as "VOE - DUB" / "VOE - SUB". Canonicalize those to voe.sx
 * so a rotating embed host still reaches [VoeExtractor].
 */
internal fun canonicalHosterUrl(serverName: String, resolvedUrl: String, encodedPath: String): String {
    val hoster = serverName.substringBefore(" - ").substringBefore(" (").trim()
    if (!hoster.equals("VOE", ignoreCase = true)) return resolvedUrl
    val path = when {
        encodedPath.isBlank() -> ""
        encodedPath.startsWith("/") -> encodedPath
        else -> "/$encodedPath"
    }
    return "https://voe.sx$path"
}
