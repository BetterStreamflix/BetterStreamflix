package com.dskja.betterstreamflix.extractors

/**
 * Shared permanent-hoster failure matcher used by [Extractor.extract] retry logic
 * and [com.dskja.betterstreamflix.fragments.player.PlayerViewModel] Sentry filtering.
 * Kept outside [Extractor]'s companion so unit tests do not initialize every extractor.
 */
object ExtractorFailureClassifier {

    fun isPermanent(error: Throwable): Boolean {
        val msg = error.message.orEmpty()
        return (error as? retrofit2.HttpException)?.code() == 404 ||
            msg.contains("No source found", ignoreCase = true) ||
            msg.contains("source not found", ignoreCase = true) ||
            msg.contains("no playable mirrors", ignoreCase = true) ||
            msg.contains("No extractors found", ignoreCase = true) ||
            msg.contains("packed source not found", ignoreCase = true) ||
            msg.contains("(404)", ignoreCase = true) ||
            msg.contains("Video removed", ignoreCase = true) ||
            msg.contains("Unable to locate video", ignoreCase = true) ||
            msg.contains("Can't retrieve script", ignoreCase = true) ||
            msg.contains("Can't retrieve m3u8", ignoreCase = true) ||
            msg.contains("botlink JavaScript not found", ignoreCase = true) ||
            msg.contains("Script with eval function not found", ignoreCase = true) ||
            msg.contains("file deleted", ignoreCase = true) ||
            msg.contains("file was deleted", ignoreCase = true) ||
            msg.contains("failed HTTP 404", ignoreCase = true) ||
            msg.contains("failed HTTP 410", ignoreCase = true) ||
            msg.contains("failed HTTP 503", ignoreCase = true) ||
            msg.contains("HTTP Client Error with status code: 503", ignoreCase = true) ||
            msg.contains("HTTP Client Error with status code: 502", ignoreCase = true) ||
            msg.contains("HTTP Client Error with status code: 504", ignoreCase = true) ||
            msg.contains("returned empty body", ignoreCase = true) ||
            msg.contains("non-ciphertext", ignoreCase = true) ||
            msg.contains("No video source found", ignoreCase = true) ||
            msg.contains("source URL empty", ignoreCase = true) ||
            msg.contains("sources[0] missing", ignoreCase = true) ||
            msg.contains("Failed to load embed page", ignoreCase = true) ||
            msg.contains("Could not find md5 path", ignoreCase = true) ||
            msg.contains("Decryption function not found", ignoreCase = true) ||
            msg.contains("No video found", ignoreCase = true) ||
            msg.contains("Unpack failed", ignoreCase = true) ||
            msg.contains("Pluto channel URL missing", ignoreCase = true) ||
            msg.contains("Pluto info card is not playable", ignoreCase = true) ||
            msg.contains("No se encontró", ignoreCase = true) ||
            msg.contains("No se encontraron", ignoreCase = true) ||
            msg.contains("No se pudo desempacar", ignoreCase = true) ||
            msg.contains("#EXTM3U", ignoreCase = true) ||
            msg.contains("contentIsMalformed", ignoreCase = true) ||
            msg.contains("Source error", ignoreCase = true) ||
            (msg.contains("not found", ignoreCase = true) &&
                (msg.contains("video", ignoreCase = true) || msg.contains("source", ignoreCase = true)))
    }

    /** Expected stream/CDN noise that must never reach Sentry as errors. */
    fun isExpectedStreamNoise(error: Throwable?): Boolean {
        if (error == null) return false
        if (isPermanent(error)) return true
        val type = error.javaClass.name
        val msg = error.message.orEmpty()
        val causeMsg = error.cause?.message.orEmpty()
        return type.contains("SentryHttpClientException") ||
            type.contains("ExoPlaybackException") ||
            type.contains("ParserException") ||
            type.contains("HttpDataSource") ||
            msg.contains("status code: 5", ignoreCase = true) ||
            causeMsg.contains("#EXTM3U", ignoreCase = true) ||
            causeMsg.contains("contentIsMalformed", ignoreCase = true)
    }
}
