package com.betterstreamflix.app.tvlauncher

/**
 * Deep links from Android TV Watch Next / Preview Channel cards back into the app.
 *
 * Format: `betterstreamflix://watch?kind=movie|episode|tvshow&id=…&provider=…`
 * Legacy `streamflix://watch` is accepted for older cards.
 */
object TvWatchDeepLink {
    const val SCHEME = "betterstreamflix"
    const val LEGACY_SCHEME = "streamflix"
    const val HOST = "watch"

    enum class Kind(val wire: String) {
        MOVIE("movie"),
        EPISODE("episode"),
        TV_SHOW("tvshow"),
        ;

        companion object {
            fun fromWire(raw: String?): Kind? =
                entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
        }
    }

    data class Target(
        val kind: Kind,
        val id: String,
        val provider: String?,
    )

    fun buildUri(kind: Kind, id: String, provider: String?): String {
        val safeId = id.trim()
        require(safeId.isNotEmpty()) { "id required" }
        val base = "$SCHEME://$HOST?kind=${kind.wire}&id=${encode(safeId)}"
        val providerPart = provider?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "&provider=${encode(it)}" }
            .orEmpty()
        return base + providerPart
    }

    /** JVM-safe parser (unit tests + intent handlers). */
    fun parse(raw: String?): Target? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val schemeHost = Regex(
            "^(?:$SCHEME|$LEGACY_SCHEME)://$HOST(?:\\?|#|$)",
            RegexOption.IGNORE_CASE,
        )
        if (!schemeHost.containsMatchIn(trimmed)) return null
        val query = trimmed.substringAfter('?', missingDelimiterValue = "")
            .substringBefore('#')
        if (query.isBlank()) return null
        val params = query.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            decode(part.substring(0, eq)) to decode(part.substring(eq + 1))
        }.toMap()
        val kind = Kind.fromWire(params["kind"]) ?: return null
        val id = params["id"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val provider = params["provider"]?.trim()?.takeIf { it.isNotEmpty() }
        return Target(kind = kind, id = id, provider = provider)
    }

    fun contentId(kind: Kind, id: String): String = "${kind.wire}:$id"

    fun parseContentId(contentId: String?): Pair<Kind, String>? {
        if (contentId.isNullOrBlank()) return null
        val sep = contentId.indexOf(':')
        if (sep <= 0 || sep >= contentId.lastIndex) return null
        val kind = Kind.fromWire(contentId.substring(0, sep)) ?: return null
        val id = contentId.substring(sep + 1).trim().takeIf { it.isNotEmpty() } ?: return null
        return kind to id
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")

    private fun decode(value: String): String =
        runCatching {
            java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
        }.getOrDefault(value)
}
