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
) {
    data class EpisodeRef(
        val id: String,
        val number: Int,
        val title: String = "",
        val posterUrl: String = "",
    )

    fun episodeCount(): Int = episodes.size

    companion object {
        const val VERSION = 1
        const val MAX_EPISODES = 40
        const val MAX_TITLE_CHARS = 120
        const val MAX_POSTER_CHARS = 240
        const val MAX_ID_CHARS = 96
    }
}
