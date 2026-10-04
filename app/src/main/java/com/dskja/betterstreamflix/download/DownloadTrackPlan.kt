package com.dskja.betterstreamflix.download

import androidx.media3.common.C
import androidx.media3.common.StreamKey

/**
 * Quality choices for a download.
 *
 * A video-only [StreamKey] list drops audio. Playback then requests the audio
 * playlist from the expired CDN and surfaces HTTP 404. Every rendition keeps
 * the audio and text keys. "Best" uses an empty key list so Media3 downloads
 * every track.
 */
object DownloadTrackPlan {
    data class TrackRef(
        val type: Int,
        val periodIndex: Int,
        val groupIndex: Int,
        val trackIndex: Int,
        val height: Int,
    )

    fun build(
        tracks: List<TrackRef>,
        bestLabel: String,
        dataSaverLabel: String,
    ): List<DownloadTrackOption> {
        val audioAndText = tracks
            .filter { it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT }
            .map { it.toKey() }
        val videos = tracks
            .filter { it.type == C.TRACK_TYPE_VIDEO && it.height > 0 }
            .distinctBy { it.height }
            .sortedByDescending { it.height }
        if (videos.isEmpty()) {
            return listOf(DownloadTrackOption(bestLabel, emptyList()))
        }
        val options = mutableListOf<DownloadTrackOption>()
        options += DownloadTrackOption(bestLabel, emptyList())
        videos.forEach { video ->
            options += DownloadTrackOption(
                label = "${video.height}p",
                streamKeys = listOf(video.toKey()) + audioAndText,
            )
        }
        if (videos.size > 1) {
            options += DownloadTrackOption(
                label = dataSaverLabel,
                streamKeys = listOf(videos.last().toKey()) + audioAndText,
            )
        }
        return options
    }

    private fun TrackRef.toKey() = StreamKey(periodIndex, groupIndex, trackIndex)
}
