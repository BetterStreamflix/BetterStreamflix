package com.dskja.betterstreamflix.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ProviderAudioLanguage
import com.dskja.betterstreamflix.utils.SubtitleOffset
import com.dskja.betterstreamflix.utils.SubtitleOffsetRenderersFactory
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Shared ExoPlayer construction for mobile + TV so decoder/buffering fixes land once.
 * Addresses Android TV / OEM crashes (e.g. Xiaomi TV) via always-on decoder fallback
 * and constrained buffers on Fire Stick / low-RAM devices.
 */
@OptIn(UnstableApi::class)
object PlayerBuilderFactory {

    data class Options(
        val extraBuffering: Boolean = false,
        /** Effective SW/extension prefer (Settings On or Auto-on). */
        val softwareDecoder: Boolean = false,
        /** True when the user forced Software Decoder On (not Auto). */
        val softwareDecoderForced: Boolean = false,
        val seekIncrementsMs: Long? = 10_000L,
        val preferStereoAudio: Boolean = false,
        /** Shorter buffers + quicker start for IPTV / live HLS. */
        val liveOptimized: Boolean = false,
        /**
         * Per-series sticky audio (e.g. `de`). Wins over the provider catalog language
         * when set so “always DE” applies before the first frame.
         */
        val preferredAudioLanguage: String? = null,
    )

    fun build(
        context: Context,
        dataSourceFactory: DataSource.Factory,
        options: Options,
    ): ExoPlayer {
        SubtitleOffset.reset()
        val constrained = DeviceCapabilities.shouldUseConstrainedPlayback(context)
        val minBufferMs: Int
        val maxBufferMs: Int
        val playbackMs: Int
        val rebufferMs: Int
        when {
            options.liveOptimized && constrained -> {
                minBufferMs = 2_000
                maxBufferMs = 15_000
                playbackMs = 1_000
                rebufferMs = 2_000
            }
            options.liveOptimized -> {
                minBufferMs = 3_000
                maxBufferMs = 25_000
                playbackMs = 1_250
                rebufferMs = 2_500
            }
            options.extraBuffering && constrained -> {
                minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
                maxBufferMs = 90_000
                playbackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
                rebufferMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            }
            options.extraBuffering -> {
                minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
                maxBufferMs = 300_000
                playbackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
                rebufferMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            }
            else -> {
                minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
                maxBufferMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
                playbackMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
                rebufferMs = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            }
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, playbackMs, rebufferMs)
            .build()

        val renderersFactory = SubtitleOffsetRenderersFactory(context).apply {
            // [Options.softwareDecoder] already encodes Settings Auto (device default) /
            // On / Off — do not OR DeviceCapabilities again or Off becomes a no-op.
            val preferSoftware = options.softwareDecoder
            // Product "Software Decoder" is NOT FFmpeg video decode: Media3's decoder_ffmpeg
            // module is unpublished on Maven and only ships FfmpegAudioRenderer. Video path
            // relies on always-on MediaCodec software fallback + player rebuild on retry.
            // EXTENSION_RENDERER_MODE_* only helps when a local extension AAR is present.
            setEnableDecoderFallback(true)
            if (preferSoftware) {
                // Forced On → MODE_ON (try extension/SW first). Auto-prefer → PREFER.
                setExtensionRendererMode(
                    if (options.softwareDecoderForced) {
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    } else {
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    },
                )
            }
        }

        val mediaSourceFactory: MediaSource.Factory = DefaultMediaSourceFactory(dataSourceFactory)
        var builder = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
        options.seekIncrementsMs?.let { seek ->
            builder = builder
                .setSeekBackIncrementMs(seek)
                .setSeekForwardIncrementMs(seek)
        }
        val player = builder.build()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus= */ true,
        )

        val selector = player.trackSelector as? DefaultTrackSelector
        if (selector != null) {
            val params = selector.buildUponParameters()
            val seriesAudio = SeriesAudioPreference.canonical(options.preferredAudioLanguage)
            val providerLang = UserPreferences.currentProvider?.language?.substringBefore("-")
            val subtitlesOff = UserPreferences.subtitleName?.trim().isNullOrEmpty()
            val audioCodes = seriesAudio?.let { SeriesAudioPreference.exoCodes(it) }
                ?: ProviderAudioLanguage.preferredAudioLanguages(providerLang)
            audioCodes?.takeIf { it.isNotEmpty() }?.let { codes ->
                params.setPreferredAudioLanguages(*codes)
            }
            // Preferring provider text languages re-enables embedded captions after
            // an Off selection (new Exo / software-decoder rebuild). Skip when Off.
            if (!subtitlesOff) {
                ProviderAudioLanguage.preferredAudioLanguages(providerLang)?.let { codes ->
                    params.setPreferredTextLanguages(*codes)
                }
            }
            if (subtitlesOff) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                params.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
            }
            if (options.preferStereoAudio || constrained) {
                params.setMaxAudioChannelCount(2)
            }
            UserPreferences.playbackVideoQuality.applyMaxVideoSize(params)
            val types = IntArray(player.rendererCount) { index -> player.getRendererType(index) }
            AudioRendererPolicy.secondaryRendererIndices(types, C.TRACK_TYPE_AUDIO).forEach { index ->
                params.setRendererDisabled(index, true)
            }
            selector.setParameters(params)
        }
        return player
    }
}
