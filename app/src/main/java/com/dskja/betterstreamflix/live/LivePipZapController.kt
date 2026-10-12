package com.dskja.betterstreamflix.live

import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.iptv.IptvLivePlayback
import com.dskja.betterstreamflix.iptv.IptvLiveSession
import com.dskja.betterstreamflix.iptv.IptvZapPreloader
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.player.PlaybackHttp
import com.dskja.betterstreamflix.player.PlaybackMime
import com.dskja.betterstreamflix.player.PlaybackRequestHeaders
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.providers.ProviderTrustLayer
import com.dskja.betterstreamflix.ui.FeaturedProviderSwitch
import com.dskja.betterstreamflix.utils.CrossProviderLibrary
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.utils.NetworkClient
import com.dskja.betterstreamflix.utils.ParentalControlUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * In-player Mini-Live overlay: muted secondary ExoPlayer preview over VOD,
 * tap/OK promotes to full live; optional return ticket restores VOD.
 *
 * Not Android system Picture-in-Picture.
 */
@OptIn(UnstableApi::class)
class LivePipZapController(
    private val fragment: Fragment,
    private val overlayRoot: View,
    private val host: Host,
) {
    interface Host {
        fun isLivePlayback(): Boolean
        fun isOfflinePlayback(): Boolean
        fun isCasting(): Boolean
        fun isSystemPip(): Boolean
        fun videoType(): Video.Type
        fun contentId(): String
        fun title(): String
        fun subtitle(): String
        fun preferredServerName(): String?
        fun positionMs(): Long
        fun playLiveChannel(channel: IptvLiveSession.Channel)
        fun isTvSurface(): Boolean
    }

    private val previewPanel: View = overlayRoot.findViewById(R.id.live_pip_preview_panel)
    private val playerView: PlayerView = overlayRoot.findViewById(R.id.pv_live_pip)
    private val titleView: TextView = overlayRoot.findViewById(R.id.tv_live_pip_title)
    private val hintView: TextView = overlayRoot.findViewById(R.id.tv_live_pip_hint)
    private val returnButton: TextView = overlayRoot.findViewById(R.id.btn_live_pip_return_vod)

    private var previewPlayer: ExoPlayer? = null
    private var previewListener: Player.Listener? = null
    private var httpFactory: OkHttpDataSource.Factory? = null
    private var streamingClient: OkHttpClient? = null
    private var candidate: LivePipZapPolicy.Candidate? = null
    /** Pin+favorite ring cached during resolve for promote (PZ-006, main-thread safe). */
    private var promoteRingCandidates: List<LivePipZapPolicy.Candidate> = emptyList()
    /** null = not resolved yet; false = pins+favorites empty. */
    private var knownHasCandidate: Boolean? = null
    private var resolveJob: Job? = null
    private var chromeVisible: Boolean = true
    private var started: Boolean = false

    fun attach() {
        previewPanel.setOnClickListener { promoteToLive() }
        previewPanel.setOnKeyListener { _, keyCode, event ->
            if (!host.isTvSurface()) return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                -> {
                    promoteToLive()
                    true
                }
                else -> false
            }
        }
        returnButton.setOnClickListener { returnToVod() }
        returnButton.setOnKeyListener { _, keyCode, event ->
            if (!host.isTvSurface()) return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                -> {
                    returnToVod()
                    true
                }
                else -> false
            }
        }
        applyTvFocusPolicy()
        hideAll()
    }

    fun onPrimaryPlaybackReady() {
        if (!fragment.isAdded) return
        refresh()
    }

    fun onCastingChanged() = refresh()

    fun onSystemPipChanged() = refresh()

    fun onChromeVisibilityChanged(visible: Boolean) {
        chromeVisible = visible
        applyTvFocusPolicy()
    }

    fun refresh() {
        if (!fragment.isAdded) return
        if (host.isLivePlayback()) {
            stopPreview(cancelJob = true)
            showReturnChipIfNeeded()
            return
        }
        returnButton.isGone = true
        if (!baseEligible()) {
            stopPreview(cancelJob = true)
            hideAll()
            return
        }
        if (started && previewPlayer != null) {
            // PZ-003: re-check eligibility even while preview is running.
            if (!baseEligible()) {
                stopPreview(cancelJob = true)
                hideAll()
                return
            }
            overlayRoot.isVisible = true
            previewPanel.isVisible = true
            applyTvFocusPolicy()
            return
        }
        startPreview()
    }

    fun release() {
        stopPreview(cancelJob = true)
        hideAll()
        LivePipZapSession.onPlayerDestroyed(wasLivePlayback = host.isLivePlayback())
        started = false
    }

    private fun baseEligible(): Boolean =
        LivePipZapPolicy.isEligible(
            LivePipZapPolicy.Eligibility(
                isLivePlayback = host.isLivePlayback(),
                isOffline = host.isOfflinePlayback(),
                isKidsOrAgeGated = ProfileManager.isKidsActive() || LiveHubCatalog.isAgeGated(),
                isCasting = host.isCasting(),
                isSystemPip = host.isSystemPip(),
                hasChannelCandidate = hasChannelCandidate(),
            ),
        )

    /** PZ-011: real candidate check — pins sync, or last resolve / active candidate. */
    private fun hasChannelCandidate(): Boolean {
        if (candidate != null) return true
        if (knownHasCandidate == false) return false
        val ctx = fragment.context?.applicationContext
        if (ctx != null && ChannelPinStore.pinCount(ctx) > 0) return true
        // Unknown until IO — allow first resolve attempt when not proven empty.
        return knownHasCandidate != false
    }

    private fun startPreview() {
        resolveJob?.cancel()
        resolveJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            val picked = withContext(Dispatchers.IO) { resolveCandidate() }
            if (!fragment.isAdded) return@launch
            knownHasCandidate = picked != null
            if (picked == null || !baseEligible()) {
                stopPreview(cancelJob = false)
                hideAll()
                return@launch
            }
            candidate = picked
            // PZ-008: resolve video before showing overlay.
            val video = withContext(Dispatchers.IO) { resolvePreviewVideo(picked) }
            if (!fragment.isAdded || video == null || host.isLivePlayback() || !baseEligible()) {
                stopPreview(cancelJob = false)
                hideAll()
                return@launch
            }
            titleView.text = picked.title
            hintView.setText(R.string.live_pip_zap_tap_hint)
            overlayRoot.isVisible = true
            previewPanel.isVisible = true
            returnButton.isGone = true
            applyTvFocusPolicy()
            playPreview(picked, video)
            started = true
        }
    }

    private suspend fun resolveCandidate(): LivePipZapPolicy.Candidate? {
        val context = fragment.context?.applicationContext ?: return null
        val favorites = runCatching {
            CrossProviderLibrary.loadFavoriteTvShows(context)
        }.getOrDefault(emptyList())
            .filter { LiveHubCatalog.isLiveFavorite(it) }
        ChannelPinStore.seedFromFavoritesIfEmpty(context, favorites)
        val pins = ChannelPinStore.list(context).map {
            LivePipZapPolicy.Candidate(
                id = it.id,
                provider = it.provider,
                title = it.title,
                poster = it.poster,
            )
        }
        val favCandidates = favorites.map {
            LivePipZapPolicy.Candidate(
                id = it.id,
                provider = it.providerName.orEmpty(),
                title = it.title,
                poster = it.poster,
            )
        }
        promoteRingCandidates = LivePipZapPolicy.mergeCandidates(pins, favCandidates)
        val recent = IptvLiveSession.recent(1).firstOrNull()?.let { ch ->
            val provider = pins.firstOrNull { it.id == ch.id }?.provider
                ?: favCandidates.firstOrNull { it.id == ch.id }?.provider
            if (provider.isNullOrBlank()) {
                null
            } else {
                LivePipZapPolicy.Candidate(
                    id = ch.id,
                    provider = provider,
                    title = ch.name,
                    poster = ch.logo,
                )
            }
        }
        return LivePipZapPolicy.pickChannel(pins, favCandidates, recent)
    }

    private suspend fun resolvePreviewVideo(candidate: LivePipZapPolicy.Candidate): Video? {
        val provider = Provider.findByName(candidate.provider) as? IptvProvider ?: return null
        val channel = IptvLiveSession.Channel(
            id = candidate.id,
            name = candidate.title,
            logo = candidate.poster,
        )
        val episode = IptvLiveSession.toEpisodeType(channel)
        val servers = IptvZapPreloader.peekCachedServers(candidate.provider, candidate.id)
            ?: runCatching { provider.getServers(candidate.id, episode) }.getOrNull()
            ?: return null
        if (servers.isEmpty()) return null
        IptvZapPreloader.rememberServers(candidate.provider, candidate.id, servers)
        // PZ-007: trust-layer order for preview server pick.
        val ordered = ProviderTrustLayer.prioritizeServers(
            provider = provider,
            servers = servers,
            preferredServerName = null,
        )
        val first = ordered.firstOrNull() ?: return null
        val cached = IptvZapPreloader.peekCachedVideo(first.id)
        if (cached != null) return cached
        val video = runCatching { provider.getVideo(first) }.getOrNull() ?: return null
        if (video.source.isBlank()) return null
        IptvZapPreloader.rememberVideo(first.id, video)
        IptvZapPreloader.warmChannel(provider, channel)
        return video
    }

    private fun playPreview(candidate: LivePipZapPolicy.Candidate, video: Video) {
        if (!fragment.isAdded) return
        val context = fragment.requireContext()
        stopPreviewPlayerOnly()
        val player = buildMutedPreviewPlayer(context)
        previewPlayer = player
        playerView.player = player
        val headers = PlaybackRequestHeaders.build(
            fallbackUserAgent = NetworkClient.USER_AGENT,
            videoHeaders = video.headers,
            pageUrl = null,
            sourceUrl = video.source,
        )
        httpFactory?.setDefaultRequestProperties(headers)
        val mediaItem = MediaItem.Builder()
            .setUri(video.source.toUri())
            .setMimeType(PlaybackMime.forPlayback(video.type, video.source))
            .setLiveConfiguration(
                IptvLivePlayback.liveConfigurationForProvider(candidate.provider),
            )
            .build()
        player.setMediaItem(mediaItem)
        player.volume = 0f
        player.prepare()
        player.playWhenReady = true
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "Mini-Live preview failed: ${error.message}")
                if (fragment.isAdded) {
                    stopPreview(cancelJob = true)
                    hideAll()
                }
            }
        }
        previewListener = listener
        player.addListener(listener)
    }

    private fun buildMutedPreviewPlayer(context: android.content.Context): ExoPlayer {
        val constrained = DeviceCapabilities.shouldUseConstrainedPlayback(context)
        val minBufferMs = if (constrained) 2_000 else 3_000
        val maxBufferMs = if (constrained) 15_000 else 25_000
        val playbackMs = if (constrained) 1_000 else 1_250
        val rebufferMs = if (constrained) 2_000 else 2_500
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, playbackMs, rebufferMs)
            .build()
        // PZ-004: reuse one client; shut down in stopPreviewPlayerOnly.
        val okHttp = streamingClient ?: PlaybackHttp.newStreamingClient().also { streamingClient = it }
        val http = OkHttpDataSource.Factory(okHttp)
        httpFactory = http
        val dataSource = DefaultDataSource.Factory(context, http)
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .build()
        player.volume = 0f
        // PZ-013: keep muted; leave USAGE_MEDIA as-is (handleAudioFocus=false).
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ false,
        )
        return player
    }

    private fun promoteToLive() {
        val picked = candidate ?: return
        if (!fragment.isAdded || host.isLivePlayback()) return
        // PZ-005: do not sticky-switch / stash when parental filter blocks live.
        if (ParentalControlUtils.isFilteringActive()) return

        val ticket = LivePipZapPolicy.buildReturnTicket(
            providerName = UserPreferences.currentProvider?.name,
            id = host.contentId(),
            videoType = host.videoType(),
            title = host.title(),
            subtitle = host.subtitle(),
            preferredServerName = host.preferredServerName(),
            positionMs = host.positionMs(),
        )

        // PZ-006: pin/favorite-scoped zap ring (not leftover sport session).
        val ring = buildPromoteRing(picked)
        val provider = Provider.findByName(picked.provider)
        IptvLiveSession.replaceWindow(ring, provider, complete = true)
        IptvLiveSession.setCurrent(picked.id)

        stopPreview(cancelJob = true)
        hideAll()
        FeaturedProviderSwitch.switchSticky(picked.provider, fragment.context)
        if (ticket != null) {
            LivePipZapSession.stash(ticket)
        }
        val channel = IptvLiveSession.Channel(
            id = picked.id,
            name = picked.title,
            logo = picked.poster,
        )
        host.playLiveChannel(channel)
    }

    private fun buildPromoteRing(picked: LivePipZapPolicy.Candidate): List<IptvLiveSession.Channel> {
        val merged = LinkedHashMap<String, IptvLiveSession.Channel>()
        promoteRingCandidates.forEach { c ->
            merged.putIfAbsent(
                c.key,
                IptvLiveSession.Channel(id = c.id, name = c.title, logo = c.poster),
            )
        }
        // Fallback: sync pins if resolve cache empty.
        if (merged.isEmpty()) {
            fragment.context?.applicationContext?.let { ctx ->
                ChannelPinStore.list(ctx).forEach { pin ->
                    merged.putIfAbsent(
                        pin.key,
                        IptvLiveSession.Channel(id = pin.id, name = pin.title, logo = pin.poster),
                    )
                }
            }
        }
        merged.putIfAbsent(
            picked.key,
            IptvLiveSession.Channel(id = picked.id, name = picked.title, logo = picked.poster),
        )
        return merged.values.toList()
    }

    private fun showReturnChipIfNeeded() {
        val ticket = LivePipZapSession.peek()
        if (ticket == null) {
            hideAll()
            return
        }
        overlayRoot.isVisible = true
        previewPanel.isGone = true
        returnButton.isVisible = true
        returnButton.text = fragment.getString(
            R.string.live_pip_zap_return_vod,
            ticket.title.ifBlank { ticket.id },
        )
        applyTvFocusPolicy()
    }

    private fun returnToVod() {
        // PZ-002: peek first; consume only when nav is available; restore on failure.
        val ticket = LivePipZapSession.peek() ?: return
        if (!fragment.isAdded) return
        val nav = runCatching { fragment.findNavController() }.getOrNull()
            ?: (fragment.activity?.supportFragmentManager
                ?.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment)
                ?.navController
        if (nav == null) return
        val consumed = LivePipZapSession.beginReturn() ?: return
        stopPreview(cancelJob = true)
        hideAll()
        FeaturedProviderSwitch.switchSticky(consumed.providerName, fragment.context)
        val args = bundleOf(
            "id" to consumed.id,
            "title" to consumed.title,
            "subtitle" to consumed.subtitle,
            "videoType" to consumed.videoType,
            "preferredServerName" to consumed.preferredServerName,
            // PZ-009: pass start position for resume on return.
            "startPositionMs" to consumed.positionMs,
        )
        val navigated = runCatching {
            when {
                nav.currentDestination?.getAction(R.id.action_playerMobileFragment_self) != null ->
                    nav.navigate(R.id.action_playerMobileFragment_self, args)
                nav.currentDestination?.getAction(R.id.action_playerTvFragment_self) != null ->
                    nav.navigate(R.id.action_playerTvFragment_self, args)
                else -> nav.navigate(R.id.player, args)
            }
            true
        }.getOrDefault(false)
        if (!navigated) {
            LivePipZapSession.restore(consumed)
            showReturnChipIfNeeded()
        }
    }

    private fun applyTvFocusPolicy() {
        val tv = host.isTvSurface()
        val focusPreview = tv &&
            LivePipZapPolicy.tvOverlayFocusable(chromeVisible) &&
            previewPanel.isVisible
        // PZ-010: return chip focusable whenever visible (chrome optional).
        val focusReturn = tv &&
            LivePipZapPolicy.tvReturnChipFocusable(returnButton.isVisible)
        previewPanel.isFocusable = focusPreview
        previewPanel.isFocusableInTouchMode = focusPreview
        previewPanel.isClickable = previewPanel.isVisible
        returnButton.isFocusable = focusReturn
        returnButton.isFocusableInTouchMode = focusReturn
        if (!tv) {
            previewPanel.isFocusable = false
            previewPanel.isFocusableInTouchMode = false
            returnButton.isFocusable = false
            returnButton.isFocusableInTouchMode = false
        }
    }

    private fun stopPreview(cancelJob: Boolean) {
        if (cancelJob) {
            resolveJob?.cancel()
            resolveJob = null
        }
        stopPreviewPlayerOnly()
        started = false
    }

    private fun stopPreviewPlayerOnly() {
        playerView.player = null
        previewPlayer?.let { p ->
            runCatching {
                previewListener?.let { p.removeListener(it) }
                p.playWhenReady = false
                p.stop()
                p.clearMediaItems()
                p.release()
            }
        }
        previewPlayer = null
        previewListener = null
        httpFactory = null
        // PZ-004: shut down dedicated OkHttp client.
        streamingClient?.let { client ->
            runCatching {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
        streamingClient = null
    }

    private fun hideAll() {
        previewPanel.isGone = true
        returnButton.isGone = true
        overlayRoot.isGone = true
    }

    companion object {
        private const val TAG = "LivePipZap"
    }
}
