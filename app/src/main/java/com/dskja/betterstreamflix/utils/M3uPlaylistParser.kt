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
                curName = extinf.substringAfterLast(",").trim()
                curLogo = Regex("""tvg-logo="([^"]+)"""").find(extinf)?.groupValues?.get(1).orEmpty()
                curGroup = Regex("""group-title="([^"]+)"""").find(extinf)?.groupValues?.get(1).orEmpty()
                curUA = Regex("""http-user-agent="([^"]+)"""").find(extinf)?.groupValues?.getOrNull(1)
                curRef = Regex("""http-referrer="([^"]+)"""").find(extinf)?.groupValues?.getOrNull(1)
                curOrigin = Regex("""http-origin="([^"]+)"""").find(extinf)?.groupValues?.getOrNull(1)
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
                if (curName.isNotEmpty() && isPlayableUrl(t)) {
                    channels.add(
                        Channel(
                            name = curName,
                            url = t,
                            logo = curLogo.takeIf { it.isNotBlank() },
                            group = curGroup.takeIf { it.isNotBlank() },
                            userAgent = curUA,
                            referrer = curRef,
                            origin = curOrigin,
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
}
