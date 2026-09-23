package com.dskja.betterstreamflix.iptv

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Shared live-playback helpers for IPTV (Media3 live window + go-to-live).
 */
object IptvLivePlayback {

    /** Target distance behind the live edge — stable on most HLS IPTV feeds. */
    const val TARGET_OFFSET_MS = 3_500L
    const val MIN_OFFSET_MS = 1_500L
    const val MAX_OFFSET_MS = 12_000L
    /** Sports / high-jitter feeds tolerate a slightly deeper edge. */
    const val SPORTS_TARGET_OFFSET_MS = 4_500L

    fun liveConfiguration(sportsTuned: Boolean = false): MediaItem.LiveConfiguration {
        val target = if (sportsTuned) SPORTS_TARGET_OFFSET_MS else TARGET_OFFSET_MS
        return MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(target)
            .setMinOffsetMs(MIN_OFFSET_MS)
            .setMaxOffsetMs(MAX_OFFSET_MS)
            .setMaxPlaybackSpeed(if (sportsTuned) 1.06f else 1.04f)
            .build()
    }

    /** Prefer sports-tuned live offsets for known sports IPTV providers. */
    fun liveConfigurationForProvider(providerName: String?): MediaItem.LiveConfiguration {
        val sports = providerName.orEmpty().lowercase().let { name ->
            name.contains("sport") ||
                name.contains("daddy") ||
                name.contains("famelack") ||
                name.contains("ntv") ||
                name.contains("futbol") ||
                name.contains("pelota") ||
                name.contains("cablevision") ||
                name.contains("tvporinternet")
        }
        return liveConfiguration(sportsTuned = sports)
    }

    /**
     * True when the playhead is meaningfully behind the live edge
     * (user paused / scrubbed into the DVR window).
     */
    @OptIn(UnstableApi::class)
    fun isBehindLiveEdge(player: Player, thresholdMs: Long = 2_500L): Boolean {
        if (!player.isCurrentMediaItemLive) return false
        val liveOffset = player.currentLiveOffset
        if (liveOffset == androidx.media3.common.C.TIME_UNSET) return false
        val target = player.currentMediaItem?.liveConfiguration?.targetOffsetMs
            ?.takeIf { it > 0 }
            ?: TARGET_OFFSET_MS
        return liveOffset > target + thresholdMs
    }

    /** Jump to the configured live edge (or default live position). */
    @OptIn(UnstableApi::class)
    fun seekToLiveEdge(player: Player) {
        if (!player.isCurrentMediaItemLive) {
            // Some IPTV HLS feeds omit EXT-X-PROGRAM-DATE-TIME; still nudge forward.
            player.seekToDefaultPosition()
            if (!player.isPlaying) player.play()
            return
        }
        val liveConfig = player.currentMediaItem?.liveConfiguration
        val target = liveConfig?.targetOffsetMs?.takeIf { it > 0 } ?: TARGET_OFFSET_MS
        val duration = player.duration
        if (duration != androidx.media3.common.C.TIME_UNSET && duration > 0) {
            val seekPos = (duration - target).coerceAtLeast(0L)
            player.seekTo(seekPos)
        } else {
            player.seekToDefaultPosition()
        }
        if (!player.isPlaying) player.play()
    }
}
