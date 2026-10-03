package com.dskja.betterstreamflix.utils

import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * Shared HTML → HLS helpers for free live-TV mirrors
 * (CDN Live TV / StreamSports99, DaddyLive epiembeds, generic embeds).
 */
object LiveStreamHtmlExtractor {

    private val GENERIC_M3U8 = Regex(
        """https?://[^"'\\\s<>]+?\.m3u8[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE,
    )
    private val PROTOCOL_RELATIVE_M3U8 = Regex(
        """(?<![:\w])//[^"'\\\s<>]+?\.m3u8[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE,
    )
    private val ASSIGNED_SRC_M3U8 = Regex(
        """(?:const|let|var)\s+SRC\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val ATOB_M3U8 = Regex("""atob\('([^']+)'\)""")
    private val XOR_ARRAY = Regex(
        """var\s+_(\w+)=\[([^\]]+)\],_(\w+)=(\d+),_(\w+)=(\d+)""",
    )
    private val CDNLIVE_DECODER = Regex("""function\s+(\w+)\(s\)\{[^}]*atob""")
    private val CDNLIVE_ASSIGN = Regex(
        """var\s+(\w+)=((?:\w+\(\w+\)\+?)+);\s*var\s+_p2pMode""",
    )

    private val AD_EMBED_HOST_MARKERS = listOf(
        "exmxbxe.",
        "assetrage.",
        "llvpn.com",
        "histats.",
        "aclib.",
        "wpnxis",
        "blogspot.com",
    )

    fun extractM3u8(html: String): String? {
        if (html.isBlank()) return null
        extractAssignedSrcM3u8(html)?.let { return it }
        extractCdnLiveTvM3u8(html)?.let { return it }
        extractXorArrayM3u8(html)?.let { return it }
        extractAtobM3u8(html)?.let { return it }
        extractSourceTagM3u8(html)?.let { return it }
        extractClapprM3u8(html)?.let { return it }
        GENERIC_M3U8.find(html)?.value?.replace("\\/", "/")?.let { return it }
        PROTOCOL_RELATIVE_M3U8.find(html)?.value
            ?.replace("\\/", "/")
            ?.let { return "https:$it" }
        return null
    }

    fun extractAssignedSrcM3u8(html: String): String? =
        ASSIGNED_SRC_M3U8.find(html)?.groupValues?.getOrNull(1)?.replace("\\/", "/")

    fun extractSourceTagM3u8(html: String): String? {
        Regex(
            """<source[^>]+src=["'](https?://[^"']+\.m3u8[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it.replace("\\/", "/") }
        Regex(
            """["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it.replace("\\/", "/") }
        return null
    }

    fun extractClapprM3u8(html: String): String? {
        Regex(
            """source\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it.replace("\\/", "/") }
        Regex(
            """source\s*:\s*["'](//[^"']+\.m3u8[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { return "https:${it.replace("\\/", "/")}" }
        Regex(
            """sources?\s*:\s*\[\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it.replace("\\/", "/") }
        return null
    }

    fun extractCdnLiveTvM3u8(html: String): String? {
        val decoder = CDNLIVE_DECODER.find(html)?.groupValues?.getOrNull(1) ?: return null
        val assign = CDNLIVE_ASSIGN.find(html)?.groupValues?.getOrNull(2) ?: return null
        val vars = Regex("""var\s+(\w+)\s*=\s*'([^']*)'""")
            .findAll(html)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val url = Regex("""$decoder\((\w+)\)""")
            .findAll(assign)
            .mapNotNull { vars[it.groupValues[1]]?.let(::decodeBase64Chunk) }
            .joinToString("")
        return url.takeIf { it.contains(".m3u8", ignoreCase = true) }
    }

    fun extractXorArrayM3u8(html: String): String? {
        val match = XOR_ARRAY.find(html) ?: return null
        val nums = match.groupValues[2]
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
        if (nums.isEmpty()) return null
        val xor = match.groupValues[4].toIntOrNull() ?: return null
        val sub = match.groupValues[6].toIntOrNull() ?: return null
        val decoded = nums.joinToString("") { n ->
            ((n xor xor) - sub + 256).and(255).toChar().toString()
        }
        return GENERIC_M3U8.find(decoded)?.value
            ?: Regex("""SIGNED_URL\s*=\s*"([^"]+\.m3u8[^"]*)"""").find(decoded)
                ?.groupValues?.getOrNull(1)
            ?: Regex("""url\s*=\s*"([^"]+\.m3u8[^"]*)"""").find(decoded)
                ?.groupValues?.getOrNull(1)
    }

    fun extractAtobM3u8(html: String): String? {
        ATOB_M3U8.findAll(html).forEach { match ->
            runCatching {
                val decoded = decodeBase64Chunk(match.groupValues[1])
                if (decoded.startsWith("http") && decoded.contains(".m3u8", true)) {
                    return decoded
                }
            }
        }
        return null
    }

    fun extractEmbedUrl(html: String): String? {
        val candidates = linkedSetOf<String>()

        Regex(
            """<iframe[^>]+id=["']thatframe["'][^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { candidates += it }
        Regex(
            """<iframe[^>]+src=["']([^"']+)["'][^>]+id=["']thatframe["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { candidates += it }
        Regex(
            """<iframe[^>]+class=["'][^"']*video[^"']*["'][^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).forEach { candidates += it.groupValues[1] }
        Regex(
            """<iframe[^>]+src=["'](https?://[^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).forEach { candidates += it.groupValues[1] }

        val scored = candidates
            .map { it.trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
            .filterNot { isAdEmbed(it) }
        scored.firstOrNull { isDaddyLivePlayerEmbed(it) }?.let { return it }
        scored.firstOrNull {
            it.contains("embed", true) ||
                it.contains("player", true) ||
                it.contains("stream", true) ||
                it.contains("premiumtv", true)
        }?.let { return it }
        return scored.firstOrNull()
    }

    /**
     * DaddyLive retired `daddy3.php` (HTTP 404). Prefer `daddy.php` on the same host
     * and the current dembed mirror.
     */
    fun normalizeDaddyLiveEmbed(embedUrl: String): List<String> {
        val trimmed = embedUrl.trim()
        if (trimmed.isBlank()) return emptyList()
        val out = linkedSetOf<String>()
        out += trimmed
        val daddyN = Regex(
            """^(https?://[^/]+)/premiumtv/daddy(\d+)\.php(\?.*)?$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(trimmed)
        if (daddyN != null) {
            val host = daddyN.groupValues[1]
            val query = daddyN.groupValues[3]
            out += "$host/premiumtv/daddy.php$query"
            out += "https://dembed.top/premiumtv/daddy.php$query"
            out += "https://daddyliveplayer.st/premiumtv/daddy.php$query"
        } else if (trimmed.contains("/premiumtv/daddy.php", ignoreCase = true)) {
            val query = trimmed.substringAfter("daddy.php", "")
            out += "https://dembed.top/premiumtv/daddy.php$query"
            out += "https://daddyliveplayer.st/premiumtv/daddy.php$query"
        }
        return out.toList()
    }

    private fun isDaddyLivePlayerEmbed(url: String): Boolean =
        url.contains("premiumtv/daddy", ignoreCase = true) ||
            url.contains("dembed.top", ignoreCase = true) ||
            url.contains("daddyliveplayer.", ignoreCase = true)

    private fun isAdEmbed(url: String): Boolean {
        val lower = url.lowercase()
        return AD_EMBED_HOST_MARKERS.any { it in lower }
    }

    private fun decodeBase64Chunk(value: String): String {
        var padded = value.replace('-', '+').replace('_', '/')
        while (padded.length % 4 != 0) padded += "="
        return String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)
    }
}
