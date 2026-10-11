package com.dskja.betterstreamflix.extractors

object DoodLaPlayback {

    fun findMd5Path(html: String): String? =
        Regex("""/pass_md5/[^'"\s<]+""")
            .find(html)
            ?.value

    fun findPageToken(html: String): String? =
        Regex("""[?&]token=([A-Za-z0-9_-]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)

    fun buildPlaybackUrl(
        videoPrefix: String,
        hash: String,
        token: String,
        expiryMs: Long,
    ): String = videoPrefix + hash + "?token=$token&expiry=$expiryMs"

    fun buildPlaybackHeaders(
        finalUrl: String,
        finalBaseUrl: String,
        playbackUserAgent: String,
        cookieHeader: String?,
    ): Map<String, String> {
        val playbackHeaders = linkedMapOf(
            "Referer" to finalUrl,
            "Origin" to finalBaseUrl,
            "User-Agent" to playbackUserAgent,
            "Accept" to "*/*"
        )
        val cookie = cookieHeader?.trim().orEmpty()
        if (cookie.isNotBlank()) {
            playbackHeaders["Cookie"] = cookie
        }
        return playbackHeaders
    }
}
