package com.dskja.betterstreamflix.player

/**
 * Headers attached to in-player and Cast requests.
 * A library "okhttp/…" user agent or a Referer that is the media file itself
 * makes many CDNs answer 403/404.
 */
object PlaybackRequestHeaders {

    fun build(
        fallbackUserAgent: String,
        videoHeaders: Map<String, String>?,
        pageUrl: String?,
        sourceUrl: String?,
    ): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        headers["User-Agent"] = fallbackUserAgent
        videoHeaders?.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
        }
        val uaKey = headers.keys.firstOrNull { it.equals("User-Agent", ignoreCase = true) }
        val ua = uaKey?.let { headers[it] }
        if (ua.isNullOrBlank() || ua.startsWith("okhttp", ignoreCase = true)) {
            if (uaKey != null && uaKey != "User-Agent") headers.remove(uaKey)
            headers["User-Agent"] = fallbackUserAgent
        }
        if (headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            val referer = refererFor(pageUrl, sourceUrl) ?: return headers
            headers["Referer"] = referer
            if (headers.keys.none { it.equals("Origin", ignoreCase = true) }) {
                originOf(referer)?.let { headers["Origin"] = it }
            }
        }
        return headers
    }

    /**
     * Prefer the embed/page URL. If the only URL we have is the media file,
     * send the origin (scheme + host), never the file URL itself.
     */
    fun refererFor(pageUrl: String?, sourceUrl: String?): String? {
        val page = httpUrl(pageUrl)
        if (page != null && !looksLikeMediaFile(page)) return page
        val source = httpUrl(sourceUrl) ?: page
        val origin = source?.let { originOf(it) } ?: return null
        return "$origin/"
    }

    fun looksLikeMediaFile(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") ||
            path.endsWith(".m3u") ||
            path.endsWith(".mpd") ||
            path.endsWith(".mp4") ||
            path.endsWith(".mkv") ||
            path.endsWith(".ts") ||
            path.endsWith(".webm") ||
            path.endsWith(".m4s")
    }

    fun originOf(url: String): String? {
        val schemeSep = url.indexOf("://")
        if (schemeSep <= 0) return null
        val hostStart = schemeSep + 3
        if (hostStart >= url.length) return null
        val slash = url.indexOf('/', hostStart)
        val hostPort = if (slash < 0) url.substring(hostStart) else url.substring(hostStart, slash)
        val host = hostPort.substringBefore('?').substringBefore('#')
        if (host.isBlank()) return null
        return url.substring(0, schemeSep) + "://" + host
    }

    private fun httpUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return null
    }
}
