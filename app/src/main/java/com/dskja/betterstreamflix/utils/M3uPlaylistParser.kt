package com.dskja.betterstreamflix.utils

/**
 * Shared M3U / M3U8 playlist parser for IPTV providers.
 *
 * Tolerates common playlist defects that otherwise drop large channel sets:
 * - bare `EXTINF:` lines missing the leading `#`
 * - placeholder / dead hosts (e.g. sinurl.com) that always 404 at play time
 */
object M3uPlaylistParser {

    data class Channel(
        val name: String,
        val url: String,
        val logo: String? = null,
        val group: String? = null,
        val userAgent: String? = null,
        val referrer: String? = null,
        val origin: String? = null,
    )

    private val DEAD_HOST_MARKERS = listOf(
        "sinurl.com",
        "example.com",
        "example.org",
        "0.0.0.0",
        "127.0.0.1",
        "localhost",
        "about:blank",
    )

    fun isPlayableUrl(url: String): Boolean {
        val value = url.trim()
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        val lower = value.lowercase()
        return DEAD_HOST_MARKERS.none { it in lower }
    }

    fun parse(m3uRaw: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var curName = ""
        var curLogo = ""
        var curGroup = ""
        var curUA: String? = null
        var curRef: String? = null
        var curOrigin: String? = null

        for (line in m3uRaw.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue

            val extinf = when {
                t.startsWith("#EXTINF", ignoreCase = true) -> t
                // MAGIS / CineCity playlists often omit the leading '#'
                t.startsWith("EXTINF", ignoreCase = true) -> "#$t"
                else -> null
            }

            if (extinf != null) {
                // No comma means there is no display label; substringAfterLast would
                // otherwise keep the whole EXTINF line and hide tvg-name.
                val commaLabel = if (',' in extinf) extinf.substringAfterLast(",").trim() else ""
                curName = commaLabel.ifBlank {
                    attr(extinf, "tvg-name")
                }
                curLogo = attr(extinf, "tvg-logo")
                curGroup = attr(extinf, "group-title")
                curUA = attr(extinf, "http-user-agent").ifBlank { null }
                curRef = attr(extinf, "http-referrer").ifBlank { null }
                curOrigin = attr(extinf, "http-origin").ifBlank { null }
                continue
            }

            if (t.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                when {
                    t.contains("http-user-agent=", ignoreCase = true) ->
                        curUA = t.substringAfter("http-user-agent=", "").trim()
                    t.contains("http-referrer=", ignoreCase = true) ->
                        curRef = t.substringAfter("http-referrer=", "").trim()
                    t.contains("http-origin=", ignoreCase = true) ->
                        curOrigin = t.substringAfter("http-origin=", "").trim()
                }
                continue
            }

            if (t.startsWith("#")) continue

            if (t.startsWith("http://", ignoreCase = true) ||
                t.startsWith("https://", ignoreCase = true)
            ) {
                val piped = splitPipeUrl(t)
                if (curName.isNotEmpty() && isPlayableUrl(piped.url)) {
                    channels.add(
                        Channel(
                            name = curName,
                            url = piped.url,
                            logo = curLogo.takeIf { it.isNotBlank() },
                            group = curGroup.takeIf { it.isNotBlank() },
                            userAgent = curUA ?: piped.userAgent,
                            referrer = curRef ?: piped.referrer,
                            origin = curOrigin ?: piped.origin,
                        ),
                    )
                }
                curName = ""
                curLogo = ""
                curGroup = ""
                curUA = null
                curRef = null
                curOrigin = null
            }
        }
        return channels
    }

    private fun attr(extinf: String, key: String): String {
        Regex("""$key="([^"]*)"""").find(extinf)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""$key='([^']*)'""").find(extinf)?.groupValues?.getOrNull(1)?.let { return it }
        return ""
    }

    private data class PipedUrl(
        val url: String,
        val userAgent: String? = null,
        val referrer: String? = null,
        val origin: String? = null,
    )

    /** `http://host/a.m3u8|User-Agent=...|Referer=...` is not a playable Exo URL. */
    private fun splitPipeUrl(raw: String): PipedUrl {
        if ('|' !in raw) return PipedUrl(raw)
        val parts = raw.split('|')
        var userAgent: String? = null
        var referrer: String? = null
        var origin: String? = null
        parts.drop(1).forEach { part ->
            val key = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (value.isBlank()) return@forEach
            when {
                key.equals("user-agent", true) || key.equals("http-user-agent", true) ->
                    userAgent = value
                key.equals("referer", true) ||
                    key.equals("referrer", true) ||
                    key.equals("http-referrer", true) -> referrer = value
                key.equals("origin", true) || key.equals("http-origin", true) ->
                    origin = value
            }
        }
        return PipedUrl(parts.first().trim(), userAgent, referrer, origin)
    }
}
