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
    private val ATOB_M3U8 = Regex("""atob\('([^']+)'\)""")
    private val XOR_ARRAY = Regex(
        """var\s+_(\w+)=\[([^\]]+)\],_(\w+)=(\d+),_(\w+)=(\d+)""",
    )
    private val CDNLIVE_DECODER = Regex("""function\s+(\w+)\(s\)\{[^}]*atob""")
    private val CDNLIVE_ASSIGN = Regex(
        """var\s+(\w+)=((?:\w+\(\w+\)\+?)+);\s*var\s+_p2pMode""",
    )

    fun extractM3u8(html: String): String? {
        if (html.isBlank()) return null
        extractCdnLiveTvM3u8(html)?.let { return it }
        extractXorArrayM3u8(html)?.let { return it }
        extractAtobM3u8(html)?.let { return it }
        return GENERIC_M3U8.find(html)?.value?.replace("\\/", "/")
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
        Regex(
            """<iframe[^>]+id=["']thatframe["'][^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it }
        Regex(
            """<iframe[^>]+src=["']([^"']+)["'][^>]+id=["']thatframe["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { return it }
        Regex(
            """<iframe[^>]+class=["'][^"']*video[^"']*["'][^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).lastOrNull()?.groupValues?.getOrNull(1)?.let { return it }
        Regex(
            """<iframe[^>]+src=["'](https?://[^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).findAll(html)
            .map { it.groupValues[1] }
            .firstOrNull {
                it.contains("embed", true) ||
                    it.contains("player", true) ||
                    it.contains("stream", true)
            }
            ?.let { return it }
        return null
    }

    private fun decodeBase64Chunk(value: String): String {
        var padded = value.replace('-', '+').replace('_', '/')
        while (padded.length % 4 != 0) padded += "="
        return String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)
    }
}
