@file:OptIn(UnstableApi::class)

package com.betterstreamflix.app.player

import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter

/**
 * Pulls chapter marks from Media3 track metadata (ID3 CHAP, MP4/MKV [Chapter] entries).
 */
object SkipIntroChapters {

    fun fromTracks(tracks: Tracks): List<SmartSkipIntro.ChapterMark> {
        val out = LinkedHashMap<String, SmartSkipIntro.ChapterMark>()
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val metadata = group.getTrackFormat(i).metadata ?: continue
                collect(metadata, out)
            }
        }
        return out.values.sortedBy { it.startMs }
    }

    fun fromMetadata(metadata: Metadata?): List<SmartSkipIntro.ChapterMark> {
        if (metadata == null) return emptyList()
        val out = LinkedHashMap<String, SmartSkipIntro.ChapterMark>()
        collect(metadata, out)
        return out.values.sortedBy { it.startMs }
    }

    private fun collect(
        metadata: Metadata,
        out: MutableMap<String, SmartSkipIntro.ChapterMark>,
    ) {
        for (j in 0 until metadata.length()) {
            val entry = metadata.get(j)
            if (entry !is Chapter) continue
            if (entry.isHidden) continue
            val start = entry.startTimeMs
            if (start < 0L || start == PlaybackProgress.TIME_UNSET) continue
            val rawEnd = entry.endTimeMs
            val end = if (rawEnd > start && rawEnd != PlaybackProgress.TIME_UNSET) {
                rawEnd
            } else {
                PlaybackProgress.TIME_UNSET
            }
            val title = entry.title?.value?.trim()?.takeIf { it.isNotEmpty() }
            val key = "$start|$end|${title.orEmpty()}"
            out.putIfAbsent(
                key,
                SmartSkipIntro.ChapterMark(
                    title = title,
                    startMs = start,
                    endMs = end,
                ),
            )
        }
    }
}
