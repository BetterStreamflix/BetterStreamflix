package com.dskja.betterstreamflix.download

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Local-first download-pack deep links (metadata + queue, no file bytes).
 *
 * ```
 * https://betterstreamflix.app/pack?d=<deflate+base64url>
 * betterstreamflix://pack?d=<…>
 * ```
 *
 * Encoding is JVM-safe for unit tests (no Android JSON / Uri builders required).
 */
object DownloadPackLinks {
    const val SCHEME = "betterstreamflix"
    const val LEGACY_SCHEME = "streamflix"
    const val HOST = "pack"
    const val HTTPS_HOST = "betterstreamflix.app"
    const val QUERY_DATA = "d"

    /** Practical QR capacity (binary / URL payload). */
    const val MAX_ENCODED_CHARS = 2800

    private const val RS = '\u001e'
    private const val US = '\u001f'
    private val DATA_PATTERN = Regex("^[A-Za-z0-9_\\-]+=*$")

    fun buildUri(payload: DownloadPackPayload): String {
        val encoded = encode(payload)
        return "https://$HTTPS_HOST/$HOST?$QUERY_DATA=$encoded"
    }

    fun buildCustomSchemeUri(payload: DownloadPackPayload): String {
        val encoded = encode(payload)
        return "$SCHEME://$HOST?$QUERY_DATA=$encoded"
    }

    fun parse(uri: Uri?): DownloadPackPayload? = parse(uri?.toString())

    /** JVM-safe parser for deep links and unit tests. */
    fun parse(raw: String?): DownloadPackPayload? {
        val encoded = extractEncoded(raw) ?: return null
        return decode(encoded)
    }

    fun extractEncoded(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val match = Regex(
            "^(?:(?:$SCHEME|$LEGACY_SCHEME)://$HOST(?:/([^?&#]*))?(?:\\?([^#]*))?|" +
                "https?://(?:www\\.)?${Regex.escape(HTTPS_HOST)}/$HOST(?:/([^?&#]*))?(?:\\?([^#]*))?)",
            RegexOption.IGNORE_CASE,
        ).find(trimmed) ?: return null
        val pathPayload = listOf(match.groupValues[1], match.groupValues[3])
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(QUERY_DATA, ignoreCase = true) }
        if (pathPayload != null && DATA_PATTERN.matches(pathPayload)) {
            return pathPayload
        }
        val query = listOf(match.groupValues[2], match.groupValues[4])
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (query.isBlank()) return null
        val params = query.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            decodeUrl(part.substring(0, eq)) to decodeUrl(part.substring(eq + 1))
        }.toMap()
        val data = params[QUERY_DATA]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return data.takeIf { DATA_PATTERN.matches(it) }
    }

    fun encode(payload: DownloadPackPayload): String {
        require(
            payload.version == DownloadPackPayload.VERSION ||
                payload.version == DownloadPackPayload.VERSION_V1,
        ) { "unsupported pack version" }
        require(payload.providerName.isNotBlank()) { "provider required" }
        require(payload.tvShowId.isNotBlank()) { "show id required" }
        require(payload.episodes.isNotEmpty()) { "episodes required" }
        require(payload.episodes.size <= DownloadPackPayload.MAX_EPISODES) {
            "at most ${DownloadPackPayload.MAX_EPISODES} episodes"
        }
        val writeVersion = DownloadPackPayload.VERSION
        val lines = ArrayList<String>(4 + payload.episodes.size)
        lines += "v$writeVersion"
        val provider = requireId(payload.providerName, DownloadPackPayload.MAX_ID_CHARS, "providerName")
        val showId = requireId(payload.tvShowId, DownloadPackPayload.MAX_ID_CHARS, "tvShowId")
        lines += listOf(
            provider,
            showId,
            clip(payload.tvShowTitle, DownloadPackPayload.MAX_TITLE_CHARS),
            payload.seasonNumber.coerceAtLeast(0).toString(),
            clip(payload.posterUrl, DownloadPackPayload.MAX_POSTER_CHARS),
            payload.expiresAtEpochMs.coerceAtLeast(0L).toString(),
        ).joinToString(US.toString())
        payload.episodes.forEach { ep ->
            require(ep.id.isNotBlank()) { "episode id required" }
            lines += listOf(
                requireId(ep.id, DownloadPackPayload.MAX_ID_CHARS, "episodeId"),
                ep.number.coerceAtLeast(0).toString(),
                clip(ep.title, DownloadPackPayload.MAX_TITLE_CHARS),
                clip(ep.posterUrl, DownloadPackPayload.MAX_POSTER_CHARS),
            ).joinToString(US.toString())
        }
        val plain = lines.joinToString(RS.toString()).toByteArray(Charsets.UTF_8)
        val compressed = deflate(plain)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        require(encoded.length <= MAX_ENCODED_CHARS) {
            "pack too large for QR/link (${encoded.length} > $MAX_ENCODED_CHARS)"
        }
        return encoded
    }

    fun decode(encoded: String): DownloadPackPayload? {
        if (encoded.isBlank() || encoded.length > MAX_ENCODED_CHARS) return null
        if (!DATA_PATTERN.matches(encoded)) return null
        val bytes = runCatching {
            Base64.getUrlDecoder().decode(encoded)
        }.getOrNull() ?: return null
        val plain = inflate(bytes) ?: return null
        val text = runCatching { String(plain, Charsets.UTF_8) }.getOrNull() ?: return null
        val lines = text.split(RS)
        if (lines.size < 3) return null
        val version = lines[0].removePrefix("v").toIntOrNull() ?: return null
        if (version != DownloadPackPayload.VERSION && version != DownloadPackPayload.VERSION_V1) {
            return null
        }
        val header = lines[1].split(US)
        if (header.size < 5) return null
        val provider = header[0].trim().takeIf { it.isNotEmpty() } ?: return null
        val showId = header[1].trim().takeIf { it.isNotEmpty() } ?: return null
        val showTitle = header[2].trim()
        val season = header[3].toIntOrNull() ?: return null
        val poster = header.getOrNull(4).orEmpty().trim()
        val expiresAt = if (version >= DownloadPackPayload.VERSION) {
            header.getOrNull(5)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } else {
            0L
        }
        val episodes = lines.drop(2).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split(US)
            if (parts.isEmpty()) return@mapNotNull null
            val id = parts[0].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val number = parts.getOrNull(1)?.toIntOrNull() ?: 0
            DownloadPackPayload.EpisodeRef(
                id = id,
                number = number,
                title = parts.getOrNull(2).orEmpty(),
                posterUrl = parts.getOrNull(3).orEmpty(),
            )
        }
        if (episodes.isEmpty() || episodes.size > DownloadPackPayload.MAX_EPISODES) return null
        val payload = DownloadPackPayload(
            version = version,
            providerName = provider,
            tvShowId = showId,
            tvShowTitle = showTitle.ifBlank { showId },
            seasonNumber = season,
            posterUrl = poster,
            episodes = episodes,
            expiresAtEpochMs = expiresAt,
        )
        if (payload.isExpired()) return null
        return payload
    }

    fun isPackLink(raw: String?): Boolean = extractEncoded(raw) != null

    private fun clip(value: String, max: Int): String =
        value.trim().take(max)

    /** Id fields must not silently change — fail encode when over the limit (OS-064). */
    private fun requireId(value: String, max: Int, label: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "$label required" }
        require(trimmed.length <= max) {
            "$label exceeds $max chars (${trimmed.length})"
        }
        return trimmed
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, /* nowrap */ true)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream(input.size.coerceAtMost(512))
        val buf = ByteArray(512)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(input: ByteArray): ByteArray? {
        val inflater = Inflater(/* nowrap */ true)
        return try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(input.size * 4)
            val buf = ByteArray(512)
            var guard = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput()) break
                if (n > 0) out.write(buf, 0, n)
                guard += n
                if (guard > 64 * 1024) return null
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun decodeUrl(value: String): String =
        runCatching {
            java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
        }.getOrDefault(value)
}
