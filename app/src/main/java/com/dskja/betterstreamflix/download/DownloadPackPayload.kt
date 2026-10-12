package com.dskja.betterstreamflix.download

/**
 * Metadata-only download pack: N episodes the receiver re-resolves and queues.
 * No media bytes — share via [DownloadPackLinks] QR / deep link.
 */
data class DownloadPackPayload(
    val version: Int = VERSION,
    val providerName: String,
    val tvShowId: String,
    val tvShowTitle: String,
    val seasonNumber: Int,
    val posterUrl: String = "",
    val episodes: List<EpisodeRef>,
    /** Epoch ms when the pack link expires; 0 = no expiry (v1 / unlimited). */
    val expiresAtEpochMs: Long = 0L,
) {
    data class EpisodeRef(
        val id: String,
        val number: Int,
        val title: String = "",
        val posterUrl: String = "",
    )

    fun episodeCount(): Int = episodes.size

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs > 0L && nowMs > expiresAtEpochMs

    companion object {
        const val VERSION = 2
        const val VERSION_V1 = 1
        const val MAX_EPISODES = 40
        const val MAX_TITLE_CHARS = 120
        const val MAX_POSTER_CHARS = 240
        const val MAX_ID_CHARS = 96
        /** Default TTL applied when sharing a pack link (OS-063). */
        const val SHARE_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L
    }
}
