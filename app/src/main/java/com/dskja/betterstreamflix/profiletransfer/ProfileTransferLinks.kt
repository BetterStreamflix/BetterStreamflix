package com.dskja.betterstreamflix.profiletransfer

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * QR / deep-link transport for [ProfileTransferPayload].
 *
 * ```
 * https://betterstreamflix.app/profile?d=<deflate+base64url>
 * betterstreamflix://profile?d=<…>
 * ```
 */
object ProfileTransferLinks {
    const val SCHEME = "betterstreamflix"
    const val LEGACY_SCHEME = "streamflix"
    const val HOST = "profile"
    const val HTTPS_HOST = "betterstreamflix.app"
    const val QUERY_DATA = "d"
    const val MAX_ENCODED_CHARS = 2800

    private val DATA_PATTERN = Regex("^[A-Za-z0-9_\\-]+=*$")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

    fun buildUri(payload: ProfileTransferPayload): String {
        val encoded = encode(payload.forQr())
        return "https://$HTTPS_HOST/$HOST?$QUERY_DATA=$encoded"
    }

    fun buildCustomSchemeUri(payload: ProfileTransferPayload): String {
        val encoded = encode(payload.forQr())
        return "$SCHEME://$HOST?$QUERY_DATA=$encoded"
    }

    fun tryBuildUri(payload: ProfileTransferPayload): String? =
        runCatching { buildUri(payload) }.getOrNull()

    fun parse(uri: Uri?): ProfileTransferPayload? = parse(uri?.toString())

    fun parse(raw: String?): ProfileTransferPayload? {
        val encoded = extractEncoded(raw) ?: return null
        return decode(encoded)
    }

    fun extractEncoded(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        // Raw base64url blob (paste / scanner without scheme)
        if (DATA_PATTERN.matches(trimmed) && trimmed.length in 32..MAX_ENCODED_CHARS) {
            return trimmed
        }
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

    fun encode(payload: ProfileTransferPayload): String {
        require(payload.v == ProfileTransferPayload.VERSION) { "unsupported profile version" }
        require(payload.kind == ProfileTransferPayload.KIND) { "unsupported profile kind" }
        val plain = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val compressed = deflate(plain)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        require(encoded.length <= MAX_ENCODED_CHARS) {
            "profile too large for QR/link (${encoded.length} > $MAX_ENCODED_CHARS)"
        }
        return encoded
    }

    fun decode(encoded: String): ProfileTransferPayload? {
        if (encoded.isBlank() || encoded.length > MAX_ENCODED_CHARS) return null
        if (!DATA_PATTERN.matches(encoded)) return null
        val bytes = runCatching {
            Base64.getUrlDecoder().decode(encoded)
        }.getOrNull() ?: return null
        val plain = inflate(bytes) ?: return null
        val text = runCatching { String(plain, Charsets.UTF_8) }.getOrNull() ?: return null
        return ProfileTransferCodec.decodeFileJson(text)
    }

    fun isProfileLink(raw: String?): Boolean = extractEncoded(raw) != null

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
                if (guard > 256 * 1024) return null
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
