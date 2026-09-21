package com.dskja.betterstreamflix.extractors

import androidx.media3.common.MimeTypes

/**
 * Infer ExoPlayer MIME type from a stream URL when extractors omit [Video.type].
 * Missing MIME is a common cause of UnrecognizedInputFormatException on HLS
 * (BETTERSTREAMFLIX-X): Exo falls back to ProgressiveMediaPeriod + sniff.
 */
object StreamMime {

    fun infer(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val u = url.lowercase()
        return when {
            looksLikeHls(u) -> MimeTypes.APPLICATION_M3U8
            looksLikeDash(u) -> MimeTypes.APPLICATION_MPD
            ".mp4" in u || "format=mp4" in u || "type=mp4" in u || "ext=mp4" in u ->
                MimeTypes.VIDEO_MP4
            ".mkv" in u || "ext=mkv" in u -> MimeTypes.VIDEO_MATROSKA
            ".webm" in u -> MimeTypes.VIDEO_WEBM
            // CDN token URLs without extension but path hints progressive/HLS.
            "/hls" in u || "hls/" in u || "master." in u -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
    }

    fun coalesce(explicit: String?, url: String?): String? =
        explicit?.takeIf { it.isNotBlank() } ?: infer(url)

    private fun looksLikeHls(u: String): Boolean =
        ".m3u8" in u ||
            ".m3u" in u ||
            "/hls/" in u ||
            "format=m3u8" in u ||
            "type=m3u8" in u ||
            "ext=m3u8" in u ||
            "playlist.m3u" in u ||
            "index.m3u8" in u ||
            "master.m3u8" in u ||
            u.contains("application/x-mpegurl") ||
            u.contains("application/vnd.apple.mpegurl")

    private fun looksLikeDash(u: String): Boolean =
        ".mpd" in u ||
            "format=mpd" in u ||
            "type=mpd" in u ||
            "manifest.mpd" in u ||
            u.contains("application/dash+xml")
}
