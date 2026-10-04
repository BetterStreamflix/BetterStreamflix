package com.dskja.betterstreamflix.player

import androidx.media3.common.MimeTypes
import com.dskja.betterstreamflix.extractors.StreamMime

/**
 * MIME used for Exo media items and external Play-with intents.
 * A generic video/mp4 or video star mime on an HLS/DASH URL makes Exo sniff the
 * playlist as a progressive file and makes external players reject the stream.
 */
object PlaybackMime {
    fun forPlayback(explicit: String?, url: String?): String {
        val inferred = StreamMime.infer(url)
        val given = explicit?.takeIf { it.isNotBlank() } ?: return inferred ?: "video/*"
        if (
            inferred == MimeTypes.APPLICATION_M3U8 ||
            inferred == MimeTypes.APPLICATION_MPD
        ) {
            if (given == "video/*" || given.equals(MimeTypes.VIDEO_MP4, ignoreCase = true)) {
                return inferred
            }
        }
        return given
    }
}
