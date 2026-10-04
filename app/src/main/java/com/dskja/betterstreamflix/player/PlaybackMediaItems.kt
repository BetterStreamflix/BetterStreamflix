package com.dskja.betterstreamflix.player

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi

/**
 * Subtitle changes must keep the current media item's stream keys and cache key.
 * Rebuilding from the URI alone makes offline HLS miss the download cache and 404.
 */
@OptIn(UnstableApi::class)
object PlaybackMediaItems {
    fun addSubtitle(
        current: MediaItem,
        extra: MediaItem.SubtitleConfiguration,
    ): MediaItem {
        val existing = current.localConfiguration?.subtitleConfigurations.orEmpty().map { cfg ->
            MediaItem.SubtitleConfiguration.Builder(cfg.uri)
                .setMimeType(cfg.mimeType)
                .setLabel(cfg.label)
                .setLanguage(cfg.language)
                .setSelectionFlags(0)
                .build()
        }
        return current.buildUpon()
            .setSubtitleConfigurations(existing + extra)
            .build()
    }
}
