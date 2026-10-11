package com.dskja.betterstreamflix.fragments.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import android.util.Rational
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.SubtitleView
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.kids.KidsBedtime
import com.dskja.betterstreamflix.player.EndCardPolicy
import com.dskja.betterstreamflix.support.ThanksAfterBingeBinder
import com.dskja.betterstreamflix.player.FailoverTimelineSession
import com.dskja.betterstreamflix.player.FailoverTimelineUi
import com.dskja.betterstreamflix.player.PlaybackFailover
import com.dskja.betterstreamflix.player.PlaybackProgress
import com.dskja.betterstreamflix.player.PlaybackLifecycleGuard
import com.dskja.betterstreamflix.player.PlayerTeardownPolicy
import com.dskja.betterstreamflix.player.PlayerBuilderFactory
import com.dskja.betterstreamflix.player.SerienStreamBypassHelper
import com.dskja.betterstreamflix.player.SkipIntroChapters
import com.dskja.betterstreamflix.player.SkipIntroSilenceProbe
import com.dskja.betterstreamflix.player.SmartMuteAds
import com.dskja.betterstreamflix.player.SmartSkipIntro
import com.dskja.betterstreamflix.player.StreamFailureReason
import com.dskja.betterstreamflix.player.StuckBufferDetector
import androidx.core.os.bundleOf
import com.dskja.betterstreamflix.providers.ProviderDomainUpdater
import com.dskja.betterstreamflix.providers.SerienStreamAuthManager
import com.dskja.betterstreamflix.utils.CrashReporter
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dskja.betterstreamflix.activities.tools.BypassWebViewActivity
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.ContentExoControllerMobileBinding
import com.dskja.betterstreamflix.databinding.FragmentPlayerMobileBinding
import com.dskja.betterstreamflix.download.SmartDownloadsManager
import com.dskja.betterstreamflix.download.ui.DownloadOptionsController
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.models.WatchItem
import com.dskja.betterstreamflix.providers.SerienStreamProvider
import com.dskja.betterstreamflix.sync.CloudSyncHooks
import com.dskja.betterstreamflix.ui.EpisodeNotesUi
import com.dskja.betterstreamflix.ui.PlayerMobileView
import com.dskja.betterstreamflix.utils.EpisodeNotesStore
import com.dskja.betterstreamflix.utils.MediaServer
import com.dskja.betterstreamflix.utils.SubtitleOffset
import com.dskja.betterstreamflix.utils.SubtitleOffsetRenderersFactory
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.utils.UserPreferences
import com.google.android.gms.cast.framework.CastButtonFactory
import com.dskja.betterstreamflix.cast.CastMediaFactory
import com.dskja.betterstreamflix.cast.CastPlaybackHub
import com.dskja.betterstreamflix.cast.CastQueueCoordinator
import com.dskja.betterstreamflix.utils.UserDataCache
import com.dskja.betterstreamflix.utils.ProviderAudioLanguage
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.getFileName
import com.dskja.betterstreamflix.utils.next
import com.dskja.betterstreamflix.utils.plus
import com.dskja.betterstreamflix.utils.setMediaServerId
import com.dskja.betterstreamflix.utils.setMediaServers
import com.dskja.betterstreamflix.utils.toSubtitleMimeType
import com.dskja.betterstreamflix.utils.subtitleConfigurationsForPlayback
import com.dskja.betterstreamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import androidx.core.net.toUri
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.dskja.betterstreamflix.fragments.player.settings.PlayerSettingsView
import java.util.Base64 
import java.io.File
import java.io.FileOutputStream
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import androidx.navigation.NavOptions
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.utils.DnsResolver
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.NetworkClient
import com.dskja.betterstreamflix.utils.EpisodeManager
import com.dskja.betterstreamflix.utils.PlayerGestureHelper
import com.dskja.betterstreamflix.utils.UserDataCache.toEpisode
import com.dskja.betterstreamflix.utils.UserDataCache.toMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.dskja.betterstreamflix.player.PlaybackHttp
import com.dskja.betterstreamflix.player.PlaybackMediaItems
import com.dskja.betterstreamflix.player.PlaybackMime
import com.dskja.betterstreamflix.player.PlaybackRequestHeaders
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
import com.dskja.betterstreamflix.ui.TrailerStreamCrossfade
import java.util.Locale
import com.dskja.betterstreamflix.extractors.TokenManager

@OptIn(UnstableApi::class)
class PlayerMobileFragment : Fragment(),
    com.dskja.betterstreamflix.sync.WatchPartyUi.WatchPartyHostAware {
    companion object {
        private const val NEXT_EPISODE_PREFETCH_THRESHOLD_MS = 60_000L
        private const val NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS = 30_000L
    }

    private var _binding: FragmentPlayerMobileBinding? = null
    private val binding get() = _binding!!
    private var isSetupDone = false

    private val PlayerControlView.binding
        get() = ContentExoControllerMobileBinding.bind(this.findViewById(R.id.cl_exo_controller))

    private val args by navArgs<PlayerMobileFragmentArgs>()
    private val database get() = AppDatabase.getInstanceOrNull(requireContext())
    private val viewModel by viewModelsFactory {
        PlayerViewModel(args.videoType, args.id, args.preferredServerName)
    }

    private val watchPartyBridge =
        object : com.dskja.betterstreamflix.sync.WatchPartyPlayerHooks.PlayerBridge {
            override fun isLiveOrOffline(): Boolean =
                isLiveTvPlayback() || isOfflinePlayback()

            override fun videoType(): Video.Type = args.videoType

            override fun currentServer(): Video.Server? =
                this@PlayerMobileFragment.currentServer

            override fun servers(): List<Video.Server> =
                this@PlayerMobileFragment.servers

            override fun positionMs(): Long =
                runCatching { activePlayer().currentPosition }.getOrDefault(0L)

            override fun isPlaying(): Boolean =
                runCatching { activePlayer().isPlaying }.getOrDefault(false)

            override fun seekTo(positionMs: Long) {
                runCatching { activePlayer().seekTo(positionMs) }
            }

            override fun setPlayWhenReady(playing: Boolean) {
                runCatching {
                    activePlayer().playWhenReady = playing
                    if (playing) activePlayer().play() else activePlayer().pause()
                }
            }

            override fun requestServer(server: Video.Server) {
                viewModel.getVideo(server)
            }
        }

    private lateinit var player: ExoPlayer
    private var playbackListener: Player.Listener? = null
    private var bufferEtaController: com.dskja.betterstreamflix.player.BufferEtaController? = null
    private var castPlayer: CastPlayer? = null
    private var castEndedListener: Player.Listener? = null
    private var castQueueExhaustedListener: (() -> Unit)? = null
    private var castQueueChangedListener: ((Int) -> Unit)? = null
    private var castQueueAdvancedListener: (() -> Unit)? = null
    private var isCasting = false
    /** Skip one onResume auto-play after handing off to an external player. */
    private var skipNextAutoResume = false
    private var lastCastHeaders: Map<String, String> = emptyMap()
    private lateinit var httpDataSource: HttpDataSource.Factory
    private lateinit var dataSourceFactory: DataSource.Factory
    private lateinit var mediaSession: MediaSession
    private lateinit var progressHandler: android.os.Handler
    private lateinit var progressRunnable: Runnable
    private lateinit var gestureHelper: PlayerGestureHelper

    private var servers = listOf<Video.Server>()
    private val failoverTimeline = FailoverTimelineSession()
    private var zoomToast: Toast? = null
    private var controlsLocked = false
    private var defaultControllerShowTimeoutMs = 2000
    private var savedControllerShowTimeoutMs = 2000
    private var settingsKeepingController = false

    private var currentVideo: Video? = null
    private var currentServer: Video.Server? = null
    private val stuckDetector = StuckBufferDetector()
    private var stuckHandler: android.os.Handler? = null
    private var stuckRunnable: Runnable? = null
    /** True when [currentServer] is the offline download server — uses cache DataSource. */
    private var playingOffline: Boolean = false
    private var offlineCacheBlocked: Boolean = false
    private var isIgnoringPip = false
    private var waitingForBypass = false

    private var skipIntroContentId: String? = null
    private var skipIntroConsumed = false
    private var skipIntroChapters: List<SmartSkipIntro.ChapterMark> = emptyList()
    private var skipIntroProbe: SkipIntroSilenceProbe? = null
    private var activeSkipOpportunity: SmartSkipIntro.Opportunity? = null
    private val smartMuteAdsWatchdog = SmartMuteAds.Watchdog()
    private var bypassDone = false
    private var nextEpisodePrefetchTargetId: String? = null
    private var nextEpisodePrefetchJob: Job? = null
    private var nextEpisodeOverlayDismissed = false
    private var endCardVisible = false
    private var endCardCountdownCancelled = false
    private var endCardCountdownJob: Job? = null
    private var kidsBedtimeWatchJob: Job? = null
    private var kidsBedtimeChipArmed = false
    /** Set once the user leaves the player — blocks further UI/player work. */
    private var isTearingDown = false
    private var playbackSoftStopped = false
    private var playerViewDetached = false
    private var playerReleased = false
    private var mediaSessionReleased = true
    private var chooserReceiverRegistered = false
    private var castNextQueueJob: Job? = null
    private var stateCollectJob: Job? = null
    private val playbackStopHandle = PlaybackLifecycleGuard.StopHandle {
        forceStopPlayback()
    }
    private var subtitleCollectJob: Job? = null
    private var episodeNavJob: Job? = null

    /** Active playback surface: Cast when a session is connected, otherwise ExoPlayer. */
    private fun activePlayer(): Player =
        if (isCasting || CastPlaybackHub.isCasting) castPlayer ?: CastPlaybackHub.playerOrNull() ?: player
        else player
    private val bypassWebViewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (isTearingDown || !isAdded) return@registerForActivityResult
            val cookies =
                result.data?.getStringExtra(BypassWebViewActivity.EXTRA_COOKIE_HEADER)?.trim()
            val resolvedStream =
                result.data?.getStringExtra(BypassWebViewActivity.EXTRA_RESOLVED_STREAM_URL)?.trim()

            if (result.resultCode != android.app.Activity.RESULT_OK) {
                waitingForBypass = false
                bypassDone = false
                notifyPlayer(
                    R.string.player_bypass_cancelled,
                    R.string.player_bypass_qr_title,
                )
                safeNavigateUp()
                return@registerForActivityResult
            }

            if (cookies.isNullOrBlank() && resolvedStream.isNullOrBlank()) {
                waitingForBypass = false
                bypassDone = false
                notifyPlayer(
                    R.string.bypass_status_complete_bypass_first,
                    R.string.player_bypass_qr_title,
                )
                safeNavigateUp()
                return@registerForActivityResult
            }

            val bypassUrl = servers.firstOrNull { isSerienStreamBypassUrl(it.id) || isSerienStreamBypassUrl(it.src) }?.id
                ?: buildSerienStreamBypassUrl()
            if (!bypassUrl.isNullOrBlank() && !cookies.isNullOrBlank()) {
                applyBypassCookies(bypassUrl, cookies)
                SerienStreamAuthManager.persist(cookies)
            }
            waitingForBypass = false
            bypassDone = true

            if (!resolvedStream.isNullOrBlank() &&
                !SerienStreamProvider.isSerienStreamHost(resolvedStream)
            ) {
                // Hoster URL captured from WebView — play it directly.
                val synthetic = Video.Server(
                    id = resolvedStream,
                    name = getString(R.string.player_bypass_resolved_server),
                    src = resolvedStream,
                )
                viewModel.getVideo(synthetic)
                return@registerForActivityResult
            }

            lifecycleScope.launch {
                delay(300)
                viewModel.reloadServersAfterBypass()
            }
        }

    private val chooserReceiver =
        com.dskja.betterstreamflix.platform.playerbackend.ExternalStreamHandoff.chosenComponentReceiver()

    private val pickLocalSubtitle = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        if (!isAdded || !::player.isInitialized) return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val fileName = uri.getFileName(ctx) ?: uri.toString()

        val mediaUri = player.currentMediaItem?.localConfiguration?.uri
        if (mediaUri == null || mediaUri.toString().isBlank()) {
            notifyPlayer(R.string.player_subtitle_needs_playback, R.string.player_settings_subtitles_title)
            return@registerForActivityResult
        }

        val currentPosition = player.currentPosition
        UserPreferences.subtitleName = fileName
        installExtraSubtitle(
            MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(fileName.toSubtitleMimeType())
                .setLabel(fileName)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build(),
            currentPosition,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerMobileBinding.bind(
            inflater.inflate(
                R.layout.fragment_player_mobile,
                container,
                false,
            )
        )
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (!isSetupDone) {
            requireActivity().requestedOrientation = playerPlaybackOrientation()
            
            val window = requireActivity().window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            isSetupDone = true
        }
        isIgnoringPip = false
        if (::player.isInitialized && !playerReleased && _binding != null) {
            binding.pvPlayer.useController = true
            maybeResumeLocalPlayback()
            if (player.isPlaying) {
                startProgressHandler()
                if (isLiveTvPlayback()) startLiveEdgeWatcher()
            }
        }
        
        if (!chooserReceiverRegistered) {
            try {
                val filter = IntentFilter(com.dskja.betterstreamflix.platform.playerbackend.ExternalStreamHandoff.ACTION_PLAYER_CHOSEN)
                ContextCompat.registerReceiver(
                    requireContext().applicationContext,
                    chooserReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                chooserReceiverRegistered = true
            } catch (_: Exception) {
                // Leave flag false so a later resume can retry; never clear a prior true.
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val trailerBridge = TrailerStreamCrossfade.attachBridge(
            bridge = binding.ivTrailerStreamBridge,
            contentId = args.id,
        )
        if (ExperimentalMobileDesign.enabled()) {
            // Skip fade-from-black when a Trailer→Stream cover is already up.
            if (!trailerBridge) {
                ExpMotion.enterScreen(binding.root)
            }
            ExperimentalMobileDesign.applyReducedGlass(binding.root)
            binding.root.findViewById<View>(R.id.layout_next_episode_overlay)
                ?.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            binding.root.findViewById<View>(R.id.ll_player_error)
                ?.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
        }
        runCatching { TrailerPlaybackController.silenceAllActive() }
        initializePlayer(false)
        setupCastControls()
        initializeVideo()
        gestureHelper = PlayerGestureHelper(
            requireContext(), 
            binding.pvPlayer, 
            binding.llBrightness, 
            binding.pbBrightness, 
            binding.tvBrightnessPercentage,
            binding.llVolume, 
            binding.pbVolume, 
            binding.tvVolumePercentage
        )
        defaultControllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs.coerceAtLeast(2000)
        savedControllerShowTimeoutMs = defaultControllerShowTimeoutMs
        applyPlayerSafeAreaInsets()
        startKidsBedtimeWatch()

        // Stato Video
        stateCollectJob?.cancel()
        stateCollectJob = viewLifecycleOwner.lifecycleScope.launch { 
            viewModel.state.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect { state ->
                if (isTearingDown || !isAdded || _binding == null) return@collect
                when (state) {
                    PlayerViewModel.State.LoadingServers -> {
                        // Pause only. stop/clear while PlayerView still owns the surface
                        // races OEM codec teardown (crash on open and on Back).
                        if (::player.isInitialized && !playerReleased && !player.isPlaying) {
                            player.playWhenReady = false
                        }
                    }
                    is PlayerViewModel.State.SuccessLoadingServers -> {
                        servers = state.servers
                        failoverTimeline.clear()
                        FailoverTimelineUi.clearErrorTimeline(_binding?.root)
                        com.dskja.betterstreamflix.providers.ProviderServerAb.clearSession()
                        val sToServer = servers.firstOrNull {
                            isSerienStreamBypassUrl(it.id) || isSerienStreamBypassUrl(it.src)
                        }

                        if (sToServer != null && !waitingForBypass && !bypassDone) {
                            val bypassUrl = buildSerienStreamBypassUrl(servers)
                            if (bypassUrl.isNullOrBlank()) {
                                waitingForBypass = false
                                // Continue without blocking if we cannot open a bypass page —
                                // native SerienStream servers may still play after cookies exist.
                                val providerName = UserPreferences.currentProvider?.name ?: ""
                                val isTmdb = providerName.contains("TMDb", ignoreCase = true)
                                if (servers.isEmpty()) {
                                    notifyPlayer(
                                        if (isTmdb) {
                                            getString(R.string.player_not_available_lang_message, "Deutsch")
                                        } else {
                                            "Unable to open SerienStream bypass page."
                                        },
                                    )
                                    return@collect
                                }
                                // Fall through to normal playback with remaining servers
                                player.playlistMetadata = MediaMetadata.Builder()
                                    .setTitle(state.toString())
                                    .setMediaServers(state.servers.map {
                                        MediaServer(id = it.id, name = PlayerViewModel.mediaServerLabel(requireContext(), it))
                                    })
                                    .build()
                                binding.settings.setOnServerSelectedListener { server ->
                                    val selected = state.servers.find { server.id == it.id }
                                    if (selected == null) {
                                        notifyPlayer(R.string.player_server_unavailable, R.string.player_settings_servers_title)
                                        return@setOnServerSelectedListener
                                    }
                                    viewModel.getVideo(selected)
                                }
                                val preferredServer = state.servers.firstOrNull {
                                    it.name.equals(args.preferredServerName, ignoreCase = true)
                                }
                                val playServer = state.servers.firstOrNull {
                                    !isSerienStreamBypassUrl(it.id) && !isSerienStreamBypassUrl(it.src)
                                } ?: preferredServer ?: state.servers.first()
                                viewModel.getVideo(playServer)
                                return@collect
                            }

                            // Seed pasted/previous session cookies before the CF WebView.
                            SerienStreamBypassHelper.applyStoredSessionCookies(bypassUrl)
                            SerienStreamAuthManager.seedRuntimeCookies()
                            val stored = UserPreferences.serienStreamSessionCookies
                            if (SerienStreamAuthManager.canSkipInteractiveBypass()) {
                                // Clearance cookie OR confirmed login/session token — no captcha.
                                waitingForBypass = false
                                bypassDone = true
                                if (stored.isNotBlank()) {
                                    applyBypassCookies(bypassUrl, stored)
                                }
                                lifecycleScope.launch {
                                    delay(250)
                                    viewModel.reloadServersAfterBypass()
                                }
                                return@collect
                            }

                            waitingForBypass = true
                            bypassWebViewLauncher.launch(
                                Intent(requireContext(), BypassWebViewActivity::class.java)
                                    .putExtra(BypassWebViewActivity.EXTRA_URL, bypassUrl)
                            )
                        } else {
                            val providerName = UserPreferences.currentProvider?.name ?: ""
                            val isTmdb = providerName.contains("TMDb", ignoreCase = true)
                            val isAD = providerName.contains("AfterDark", ignoreCase = true)

                            if (servers.isEmpty()) {
                                val message = if (isTmdb || isAD) {
                                    val langCode = providerName.substringAfter("(").substringBefore(")")
                                    val locale = Locale.forLanguageTag(langCode)
                                    val langDisplayName = locale.getDisplayLanguage(Locale.getDefault())
                                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                                    if (isTmdb) getString(R.string.player_not_available_lang_message, langDisplayName)
                                    else getString(R.string.player_retry_later_message)
                                } else {
                                    getString(R.string.player_stream_fail_generic_message)
                                }
                                // Stay in the player with a typed failure — do not dump to the list.
                                showStreamFailure(
                                    error = Exception(message),
                                    serversExhausted = true,
                                    fallbackMessage = message,
                                )
                                return@collect
                            }

                            player.playlistMetadata = MediaMetadata.Builder()
                                .setTitle(state.toString())
                                .setMediaServers(state.servers.map {
                                    MediaServer(
                                        id = it.id,
                                        name = PlayerViewModel.mediaServerLabel(requireContext(), it),
                                    )
                                })
                                .build()
                            binding.settings.setOnServerSelectedListener { server ->
                                val selected = state.servers.find { server.id == it.id }
                                if (selected == null) {
                                    notifyPlayer(R.string.player_server_unavailable, R.string.player_settings_servers_title)
                                    return@setOnServerSelectedListener
                                }
                                viewModel.getVideo(selected)
                            }
                            val preferredServer = state.servers.firstOrNull {
                                it.name.equals(args.preferredServerName, ignoreCase = true)
                            }
                            viewModel.getVideo(preferredServer ?: state.servers.first())
                        }

                    }

                    is PlayerViewModel.State.FailedLoadingServers -> {
                        revealTrailerStreamBridge()
                        showStreamFailure(
                            error = state.error,
                            serversExhausted = true,
                            fallbackMessage = state.error.message,
                        )
                    }

                    is PlayerViewModel.State.LoadingVideo -> {
                        stuckDetector.setResolveInFlight(true)
                        hideStuckOverlay()
                        // Do not stop/clear an attached surface while the next server
                        // resolves — that is the same OEM crash as leaving the player.
                        if (::player.isInitialized && !playerReleased && !player.isPlaying) {
                            player.playWhenReady = false
                        }
                    }

                    is PlayerViewModel.State.SuccessLoadingVideo -> {
                        stuckDetector.setResolveInFlight(false)
                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)
                        if (PlayerSettingsView.Settings.SoftwareDecoder.selectedValue == null) {
                            PlayerSettingsView.Settings.SoftwareDecoder.init(
                                com.dskja.betterstreamflix.ui.StickEnergyMode.preferSoftwareDecoder(
                                    requireContext(),
                                ),
                            )
                        }
                        failoverTimeline.recordOk(state.server, servers)
                        maybeShowFailoverTimeline()
                        displayVideo(state.video, state.server)
                    }

                    is PlayerViewModel.State.FailedLoadingVideo -> {
                        stuckDetector.setResolveInFlight(false)
                        failoverTimeline.recordServerFailure(state.server, servers, state.error)
                        when (
                            val action = PlaybackFailover.decide(
                                currentServerIndex = PlaybackFailover.indexOfServer(servers, state.server),
                                serverCount = servers.size,
                                playbackAlreadyStarted = false,
                                softwareDecoderAlreadyEnabled = currentSoftwareDecoder,
                                allowMidPlaybackFailover = true,
                                externalPlayerAvailable = !isOfflinePlayback() && (
                                    com.dskja.betterstreamflix.platform.playerbackend.ExternalMpvBackend.canResolve(requireContext()) ||
                                        com.dskja.betterstreamflix.platform.playerbackend.PlayerBackendSelector.shouldHandoffToExternal()
                                    ),
                                externalPlayerAlreadyTried = currentExternalPlayerTried,
                                error = state.error,
                            )
                        ) {
                            is PlaybackFailover.Action.TryNextServer -> {
                                pendingPlayWithAfterResolve = false
                                val next = servers.getOrNull(action.nextIndex)
                                if (next != null) {
                                    showFailoverServerCue(action.nextIndex, servers.size)
                                    viewModel.getVideo(next)
                                } else {
                                    CrashReporter.logNonFatal(
                                        "PlayerMobileFragment",
                                        "All servers failed",
                                        state.error,
                                    )
                                    showStreamFailure(state.error, serversExhausted = true)
                                }
                            }
                            PlaybackFailover.Action.RetrySoftwareDecoder -> {
                                pendingPlayWithAfterResolve = false
                                failoverTimeline.recordSoftwareDecoder()
                                // Leave currentSoftwareDecoder stale so needsReinit rebuilds Exo.
                                PlayerSettingsView.Settings.SoftwareDecoder.init(true)
                                PlayerSettingsView.Settings.SoftwareDecoder.selectedValue = true
                                showPlayerCue(R.string.player_retry_software_decoder)
                                viewModel.getVideo(state.server)
                            }
                            PlaybackFailover.Action.TryExternalPlayer -> {
                                pendingPlayWithAfterResolve = false
                                currentExternalPlayerTried = true
                                failoverTimeline.recordExternalPlayer()
                                val video = currentVideo
                                if (video != null) {
                                    showPlayerCue(R.string.player_retry_external_player)
                                    val pos = runCatching { player.currentPosition }.getOrDefault(0L)
                                    com.dskja.betterstreamflix.platform.playerbackend.ExternalMpvBackend.open(
                                        requireContext(),
                                        video.source,
                                        video.headers.orEmpty(),
                                        positionMs = pos,
                                        title = resolvePlayerTitle(),
                                    )
                                } else {
                                    viewModel.getVideo(state.server)
                                }
                            }
                            PlaybackFailover.Action.GiveUp -> {
                                pendingPlayWithAfterResolve = false
                                if (isOfflinePlayback()) {
                                    showPlayerError(getString(R.string.player_offline_missing))
                                } else {
                                    CrashReporter.logNonFatal(
                                        "PlayerMobileFragment",
                                        "All servers failed",
                                        state.error,
                                    )
                                    showStreamFailure(state.error, serversExhausted = true)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Stato Sottotitoli
        subtitleCollectJob?.cancel()
        subtitleCollectJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.subtitleState.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect { state ->
                if (isTearingDown || !isAdded || _binding == null) return@collect
                when (state) {
                    PlayerViewModel.SubtitleState.Loading -> {
                        binding.settings.showSubtitleProvidersLoading()
                    }
                    is PlayerViewModel.SubtitleState.SuccessOpenSubtitles -> {
                        binding.settings.openSubtitles = state.subtitles
                    }
                    is PlayerViewModel.SubtitleState.FailedOpenSubtitles -> {
                        binding.settings.openSubtitles = emptyList()
                        notifyPlayer(
                            getString(
                                R.string.player_subtitle_search_failed,
                                state.error.message?.take(80).orEmpty().ifBlank { "OpenSubtitles" },
                            ),
                            R.string.player_settings_open_subtitles_title,
                        )
                    }

                    PlayerViewModel.SubtitleState.DownloadingOpenSubtitle -> {}
                    is PlayerViewModel.SubtitleState.SuccessDownloadingOpenSubtitle -> {
                        val mediaUri = player.currentMediaItem?.localConfiguration?.uri
                        if (mediaUri == null || mediaUri.toString().isBlank()) {
                            notifyPlayer(R.string.player_subtitle_needs_playback, R.string.player_settings_subtitles_title)
                            return@collect
                        }
                        val fileName = state.uri.getFileName(requireContext()) ?: state.uri.toString()
                        val currentPosition = player.currentPosition
                        UserPreferences.subtitleName =
                            state.subtitle.languageName?.takeIf { it.isNotBlank() } ?: fileName
                        installExtraSubtitle(
                            MediaItem.SubtitleConfiguration.Builder(state.uri)
                                .setMimeType(fileName.toSubtitleMimeType())
                                .setLabel(fileName)
                                .setLanguage(state.subtitle.languageName)
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build(),
                            currentPosition,
                        )
                    }
                    is PlayerViewModel.SubtitleState.FailedDownloadingOpenSubtitle -> {
                        notifyPlayer("${state.subtitle.subFileName}: ${state.error.message}", R.string.player_settings_open_subtitles_title)
                    }

                    is PlayerViewModel.SubtitleState.SuccessSubDLSubtitles -> {
                        binding.settings.subDLSubtitles = state.subtitles
                    }
                    is PlayerViewModel.SubtitleState.FailedSubDLSubtitles -> {
                        binding.settings.subDLSubtitles = emptyList()
                        notifyPlayer(
                            getString(
                                R.string.player_subtitle_search_failed,
                                state.error.message?.take(80).orEmpty().ifBlank { "SubDL" },
                            ),
                            R.string.player_settings_subdl_title,
                        )
                    }

                    PlayerViewModel.SubtitleState.DownloadingSubDLSubtitle -> {}
                    is PlayerViewModel.SubtitleState.SuccessDownloadingSubDLSubtitle -> {
                        val mediaUri = player.currentMediaItem?.localConfiguration?.uri
                        if (mediaUri == null || mediaUri.toString().isBlank()) {
                            notifyPlayer(R.string.player_subtitle_needs_playback, R.string.player_settings_subtitles_title)
                            return@collect
                        }
                        val fileName = state.uri.getFileName(requireContext()) ?: state.uri.toString()
                        val currentPosition = player.currentPosition
                        UserPreferences.subtitleName =
                            state.subtitle.lang?.takeIf { it.isNotBlank() }
                                ?: state.subtitle.language?.takeIf { it.isNotBlank() }
                                ?: state.subtitle.releaseName
                                ?: state.subtitle.name
                                ?: fileName
                        installExtraSubtitle(
                            MediaItem.SubtitleConfiguration.Builder(state.uri)
                                .setMimeType(fileName.toSubtitleMimeType())
                                .setLabel(state.subtitle.releaseName ?: state.subtitle.name ?: fileName)
                                .setLanguage(state.subtitle.lang ?: state.subtitle.language ?: "Unknown")
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build(),
                            currentPosition,
                        )
                    }
                    is PlayerViewModel.SubtitleState.FailedDownloadingSubDLSubtitle -> {
                        notifyPlayer("${state.subtitle.name}: ${state.error.message}", R.string.player_settings_subdl_title)
                    }
                }
            }
        }

        episodeNavJob?.cancel()
        episodeNavJob = viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.playPreviousOrNextEpisode.collect { nextEpisode ->
                    if (isTearingDown || !isAdded || _binding == null) return@collect
                    // Navigating away — soft-stop; hard release happens in onDestroyView.
                    releasePlayer(ReleaseMode.SOFT_LEAVE)
                    isSetupDone = false
                    val live = UserPreferences.currentProvider is IptvProvider
                    val action = PlayerMobileFragmentDirections
                        .actionPlayerMobileFragmentSelf(
                            id = nextEpisode.id,
                            videoType = nextEpisode,
                            title = nextEpisode.tvShow.title,
                            subtitle = if (live) {
                                getString(R.string.player_live_channel)
                            } else {
                                "S${nextEpisode.season.number} E${nextEpisode.number}  •  ${nextEpisode.title}"
                            },
                            // Live zap must not reuse the previous channel's server name.
                            preferredServerName = if (live) null else currentServer?.name,
                        )

                    hideNextEpisodeOverlay()
                    if (isTearingDown || !isAdded) return@collect
                    runCatching {
                        findNavController().navigate(
                            action,
                            NavOptions.Builder()
                                .setPopUpTo(
                                    findNavController().currentDestination?.id ?: return@runCatching,
                                    true,
                                )
                                .setLaunchSingleTop(false)
                                .build()
                        )
                    }
                }
            }
        }


    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        val b = _binding
        if (b != null) {
            b.pvPlayer.useController = !isInPictureInPictureMode
            if (isInPictureInPictureMode) {
                b.settings.hide()
                restoreControllerTimeoutAfterSettings()
            }
        }
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    fun onUserLeaveHint() {
        if (isTearingDown || isRemoving) return
        if (isCasting || CastPlaybackHub.isCasting) return
        if (!isIgnoringPip && ::player.isInitialized && !playerReleased &&
            runCatching { player.isPlaying }.getOrDefault(false)
        ) {
            enterPIPMode()
        }
    }

    /**
     * Hard-stop local audio/video. Called from Back, destination changes, and teardown.
     * Idempotent — safe if already released. Clean Exit: mute first, no audio zombie.
     */
    fun forceStopPlayback() {
        isTearingDown = true
        com.dskja.betterstreamflix.player.PlayerCleanExit.noteLeavingPlayer()
        val live = if (::player.isInitialized && !playerReleased) player else null
        com.dskja.betterstreamflix.player.PlayerCleanExit.silenceExo(live)
        releasePlayer(ReleaseMode.SOFT_LEAVE)
        runCatching { com.dskja.betterstreamflix.ui.TrailerPlaybackController.silenceAllActive() }
    }

    private fun mayRunPlayerListenerWork(): Boolean =
        PlayerTeardownPolicy.allowListenerWork(
            tearingDown = isTearingDown,
            playerReleased = playerReleased,
            hasBinding = _binding != null,
        )

    private fun isInPipMode(): Boolean =
        activity?.isInPictureInPictureMode == true

    override fun onPause() {
        super.onPause()
        // Pause immediately when leaving the player screen (Back / bottom nav / Home).
        // Skip while in PiP or casting so background playback can continue intentionally.
        // Soft leave must not stop/clear — that races OEM surface teardown on Back.
        if (!isInPipMode() &&
            !isCasting &&
            !CastPlaybackHub.isCasting &&
            ::player.isInitialized &&
            !playerReleased
        ) {
            runCatching {
                player.playWhenReady = false
                player.pause()
            }
        }
        if (::player.isInitialized && !playerReleased && !isTearingDown && !isLiveTvPlayback()) {
            runCatching {
                reportTraktProgress(
                    videoType = args.videoType,
                    positionMs = player.currentPosition,
                    durationMs = player.duration,
                    isPlaying = false,
                )
            }
        }
        stopProgressHandler()
        stopLiveEdgeWatcher()
        hideNextEpisodeOverlay()
    }

    override fun onStop() {
        super.onStop()
        // Keep local Exo paused only when we are not casting — otherwise Cast owns playback.
        if (::player.isInitialized && !playerReleased && !isCasting && !CastPlaybackHub.isCasting) {
            runCatching {
                player.playWhenReady = false
                player.pause()
            }
        }
        if (isRemoving || isTearingDown) {
            releasePlayer(ReleaseMode.SOFT_LEAVE)
        }
    }

    override fun onWatchPartyHostStarted() {
        if (!isAdded || _binding == null) return
        com.dskja.betterstreamflix.sync.WatchPartyPlayerHooks.ensureSyncRunning(this, watchPartyBridge)
        com.dskja.betterstreamflix.sync.WatchPartyPlayerHooks.refreshChrome(
            binding.pvPlayer.controller.binding.btnExoWatchParty,
            watchPartyBridge,
        )
    }

    override fun watchPartyServerSource():
        com.dskja.betterstreamflix.sync.WatchPartyLobbyUi.ServerSource =
        object : com.dskja.betterstreamflix.sync.WatchPartyLobbyUi.ServerSource {
            override fun servers(): List<Video.Server> = watchPartyBridge.servers()
            override fun currentServer(): Video.Server? = watchPartyBridge.currentServer()
            override fun onServerPicked(server: Video.Server) {
                watchPartyBridge.requestServer(server)
            }
            override fun pauseLocalPlayback() {
                watchPartyBridge.setPlayWhenReady(false)
            }
            override fun resumeLocalPlayback() {
                watchPartyBridge.setPlayWhenReady(true)
            }
        }

    override fun onDestroyView() {
        // Tear down player/UI while the fragment is still attached. Doing this after
        // super.onDestroyView() races with OEM back stacks (crash on Zurück).
        isTearingDown = true
        com.dskja.betterstreamflix.sync.WatchPartyPlayerHooks.onDestroyView(this)
        com.dskja.betterstreamflix.sync.CastRoomPlayerHooks.onDestroyView(this)
        PlaybackLifecycleGuard.unregister(playbackStopHandle)
        val appContext = context?.applicationContext
        val hostActivity = activity
        runCatching {
            com.dskja.betterstreamflix.platform.player.PlayerPlaybackReporter.resetSession()
        }
        stateCollectJob?.cancel()
        subtitleCollectJob?.cancel()
        episodeNavJob?.cancel()
        castNextQueueJob?.cancel()
        nextEpisodePrefetchJob?.cancel()
        stateCollectJob = null
        subtitleCollectJob = null
        episodeNavJob = null
        castNextQueueJob = null
        nextEpisodePrefetchJob = null
        stopProgressHandler()
        stopLiveEdgeWatcher()
        hideNextEpisodeOverlay()
        releaseSkipIntroProbe()
        waitingForBypass = false
        if (::gestureHelper.isInitialized) {
            runCatching { gestureHelper.release() }
        }
        castEndedListener?.let { listener ->
            runCatching { castPlayer?.removeListener(listener) }
        }
        castEndedListener = null
        castQueueExhaustedListener?.let { CastPlaybackHub.removeQueueExhaustedListener(it) }
        castQueueExhaustedListener = null
        castQueueChangedListener?.let { CastPlaybackHub.removeQueueChangedListener(it) }
        castQueueChangedListener = null
        castQueueAdvancedListener?.let { CastPlaybackHub.removeQueueAdvancedListener(it) }
        castQueueAdvancedListener = null
        releasePlayer(ReleaseMode.HARD_DESTROY)
        runCatching {
            appContext?.let { CastPlaybackHub.detachUi(it) }
        }
        castPlayer = CastPlaybackHub.playerOrNull()
        if (appContext != null && chooserReceiverRegistered) {
            try {
                appContext.unregisterReceiver(chooserReceiver)
            } catch (_: Exception) {
            }
            chooserReceiverRegistered = false
        }
        hostActivity?.window?.let { window ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
                WindowCompat.getInsetsController(
                    window,
                    window.decorView
                ).run {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    show(WindowInsetsCompat.Type.systemBars())
                }
                hostActivity.requestedOrientation = playerExitOrientation()
            }
        }
        controlsLocked = false
        settingsKeepingController = false
        _binding = null
        isSetupDone = false
        super.onDestroyView()
    }

    private fun safeNavigateUp() {
        if (!isAdded) return
        if (!isTearingDown) {
            // Clean Exit: mute + soft-stop before pop; Exo release waits for onDestroyView.
            forceStopPlayback()
        } else {
            com.dskja.betterstreamflix.player.PlayerCleanExit.noteLeavingPlayer()
        }
        runCatching { findNavController().navigateUp() }
    }

    private fun startKidsBedtimeWatch() {
        kidsBedtimeWatchJob?.cancel()
        kidsBedtimeWatchJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && isAdded && !isTearingDown) {
                refreshKidsBedtimeChip()
                if (isLiveTvPlayback() && KidsBedtime.shouldStopLiveNow()) {
                    finishForKidsBedtime()
                    return@launch
                }
                if (KidsBedtime.shouldBlockAutoplay()) {
                    endCardCountdownCancelled = true
                    endCardCountdownJob?.cancel()
                }
                delay(15_000L)
            }
        }
    }

    private fun refreshKidsBedtimeChip() {
        val chip = _binding?.tvKidsBedtimeChip ?: return
        if (!KidsBedtime.shouldStopAfterEpisode()) {
            chip.isGone = true
            kidsBedtimeChipArmed = false
            return
        }
        chip.isVisible = true
        chip.text = getString(
            if (isLiveTvPlayback()) R.string.kids_bedtime_chip_live
            else R.string.kids_bedtime_chip,
        )
        if (!kidsBedtimeChipArmed) {
            kidsBedtimeChipArmed = true
            ExpMotion.popIn(chip)
        }
    }

    private fun finishForKidsBedtime() {
        if (isTearingDown || !isAdded) return
        KidsBedtime.engageLock()
        endCardCountdownCancelled = true
        endCardCountdownJob?.cancel()
        hideEndCardChooser()
        hideNextEpisodeOverlay()
        Toast.makeText(
            requireContext(),
            R.string.kids_bedtime_stopped_toast,
            Toast.LENGTH_LONG,
        ).show()
        safeNavigateUp()
        (activity as? androidx.fragment.app.FragmentActivity)?.let { act ->
            act.window?.decorView?.post {
                com.dskja.betterstreamflix.kids.KidsBedtimeUi.maybeShowLock(act)
            }
        }
    }

    fun onBackPressed(): Boolean {
        val b = _binding ?: return false
        return when {
            b.pvPlayer.isManualZoomEnabled -> {
                b.pvPlayer.exitManualZoomMode()
                true
            }
            b.settings.isVisible -> {
                b.settings.onBackPressed()
            }
            stuckDetector.isOverlayVisible() -> {
                // Offer a hoster hop before rage-quit Back exits the player.
                if (tryStuckServerHop()) {
                    true
                } else {
                    hideStuckOverlay()
                    openServerPickerFromStuck()
                    true
                }
            }
            else -> false
        }
    }


    private fun initializeVideo() {
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        requireActivity().requestedOrientation = playerPlaybackOrientation()
        when (val type = args.videoType) {
            is Video.Type.Episode -> {
                nextEpisodeOverlayDismissed = false
                nextEpisodePrefetchTargetId = null
                hideEndCardChooser()
                if (isLiveTvPlayback()) {
                    EpisodeManager.clearEpisodes()
                    hideNextEpisodeOverlay()
                    updatePlayerHeader(type)
                } else if (EpisodeManager.listIsEmpty(type)) {
                    EpisodeManager.clearEpisodes()
                    lifecycleScope.launch(Dispatchers.IO) {
                        database?.let { EpisodeManager.addEpisodesFromDb(type, it) }
                        withContext(Dispatchers.Main) {
                            EpisodeManager.setCurrentEpisode(type)
                            updatePlayerHeader(type)
                            setupEpisodeNavigationButtons()
                            refreshEpisodeNavigation(type)
                        }
                    }
                } else {
                    EpisodeManager.setCurrentEpisode(type)
                    setupEpisodeNavigationButtons()
                    refreshEpisodeNavigation(type)
                }
            }
            is Video.Type.Movie -> {
                nextEpisodeOverlayDismissed = false
                nextEpisodePrefetchTargetId = null
                EpisodeManager.clearEpisodes()
                hideNextEpisodeOverlay()
                hideEndCardChooser()
            }
        }


        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }
        binding.settings.setOnSoftwareDecoderSelectedListener {
            // Flag is already in Settings; leave currentSoftwareDecoder stale so needsReinit fires.
            displayVideo(
                currentVideo ?: return@setOnSoftwareDecoderSelectedListener,
                currentServer ?: return@setOnSoftwareDecoderSelectedListener
            )
        }
        binding.pvPlayer.resizeMode = UserPreferences.playerResize.resizeMode
        binding.pvPlayer.subtitleView?.apply {
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize)
            setStyle(UserPreferences.captionStyle)
            setPadding(0, 0, 0, UserPreferences.captionMargin.dp(context))
        }
        setupEpisodeNavigationButtons()

        if (ExperimentalMobileDesign.enabled()) {
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                listOf(
                    binding.pvPlayer.controller.binding.btnExoBack,
                    binding.pvPlayer.controller.binding.btnExoExternalPlayer,
                    binding.pvPlayer.controller.binding.exoReplay,
                    binding.pvPlayer.controller.binding.exoPlayPause,
                    binding.pvPlayer.controller.binding.exoRew,
                    binding.pvPlayer.controller.binding.exoFfwd,
                    binding.pvPlayer.controller.binding.btnCustomPrev,
                    binding.pvPlayer.controller.binding.btnCustomNext,
                    binding.pvPlayer.controller.binding.btnExoUnlock,
                    binding.pvPlayer.controller.binding.btnSkipIntro,
                    binding.pvPlayer.controller.binding.btnExoLock,
                    binding.pvPlayer.controller.binding.btnExoPictureInPicture,
                    binding.pvPlayer.controller.binding.btnExoAspectRatio,
                    binding.pvPlayer.controller.binding.btnExoAudio,
                    binding.pvPlayer.controller.binding.exoSettings,
                    binding.pvPlayer.controller.binding.btnGoLive,
                    binding.btnNextEpisodeAction,
                    binding.btnNextEpisodeDismiss,
                ).forEach { it.applyExpPress() }
                runCatching {
                    binding.pvPlayer.controller.binding.mediaRouteButton.applyExpPress()
                }
            }
            listOf(
                binding.pvPlayer.controller.binding.btnExoBack,
                binding.pvPlayer.controller.binding.btnExoExternalPlayer,
                binding.pvPlayer.controller.binding.btnExoLock,
                binding.pvPlayer.controller.binding.btnExoPictureInPicture,
                binding.pvPlayer.controller.binding.btnExoAspectRatio,
                binding.pvPlayer.controller.binding.btnExoAudio,
                binding.pvPlayer.controller.binding.exoSettings,
            ).forEach { btn ->
                btn.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
            }
            runCatching {
                binding.pvPlayer.controller.binding.mediaRouteButton
                    .setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
            }
            listOf(
                binding.pvPlayer.controller.binding.exoPlayPause,
                binding.pvPlayer.controller.binding.exoRew,
                binding.pvPlayer.controller.binding.exoFfwd,
                binding.pvPlayer.controller.binding.exoReplay,
            ).forEach { btn ->
                btn.setBackgroundResource(ExperimentalMobileDesign.controlsPillBackground())
            }
        }

        binding.pvPlayer.controller.binding.btnExoBack.setOnClickListener {
            ExpMotion.hapticTap(it)
            safeNavigateUp()
        }

        updatePlayerHeader()

        wirePlayWithChromeButton(binding.pvPlayer.controller.binding)
        com.dskja.betterstreamflix.sync.WatchPartyPlayerHooks.wireChromeButton(
            this,
            binding.pvPlayer.controller.binding.btnExoWatchParty,
            watchPartyBridge,
        )
        com.dskja.betterstreamflix.sync.CastRoomPlayerHooks.wireChromeButton(
            this,
            binding.pvPlayer.controller.binding.btnExoCastRoom,
        )

        binding.pvPlayer.controller.binding.exoReplay.setOnClickListener {
            ExpMotion.hapticTap(it)
            activePlayer().seekTo(0)
        }

        binding.pvPlayer.controller.binding.btnExoLock.setOnClickListener {
            ExpMotion.hapticTap(it)
            setControlsLocked(true)
            if (ExperimentalMobileDesign.enabled()) {
                binding.pvPlayer.controller.binding.btnExoUnlock.setBackgroundResource(
                    ExperimentalMobileDesign.primaryButtonBackground(),
                )
                androidx.core.widget.ImageViewCompat.setImageTintList(
                    binding.pvPlayer.controller.binding.btnExoUnlock,
                    android.content.res.ColorStateList.valueOf(
                        com.google.android.material.color.MaterialColors.getColor(
                            binding.pvPlayer.controller.binding.btnExoUnlock,
                            com.google.android.material.R.attr.colorOnPrimary,
                        ),
                    ),
                )
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                    binding.pvPlayer.controller.binding.btnExoUnlock.applyExpPress()
                }
                ExpMotion.popIn(binding.pvPlayer.controller.binding.btnExoUnlock)
            }
        }

        binding.pvPlayer.controller.binding.btnExoUnlock.setOnClickListener {
            ExpMotion.hapticTap(it)
            setControlsLocked(false)
            if (ExperimentalMobileDesign.enabled()) {
                val c = binding.pvPlayer.controller.binding
                c.btnExoUnlock.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
                androidx.core.widget.ImageViewCompat.setImageTintList(
                    c.btnExoUnlock,
                    android.content.res.ColorStateList.valueOf(
                        com.google.android.material.color.MaterialColors.getColor(
                            c.btnExoUnlock,
                            androidx.appcompat.R.attr.colorPrimary,
                        ),
                    ),
                )
                ExpMotion.revealHeader(c.tvExoTitle, c.tvExoSubtitle)
                ExpMotion.popIn(c.exoBottomBar)
                ExpMotion.popIn(c.exoCenterControls)
            }
        }

        binding.pvPlayer.controller.binding.btnExoPictureInPicture.setOnClickListener {
            ExpMotion.hapticTap(it)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                notifyPlayer(
                    R.string.player_picture_in_picture_not_supported,
                    R.string.player_settings_title,
                )
            } else {
                enterPIPMode()
            }
        }

        binding.pvPlayer.controller.binding.btnExoAspectRatio.setOnClickListener {
            ExpMotion.hapticTap(it)
            val newResize = UserPreferences.playerResize.next()
            if (ExperimentalMobileDesign.enabled()) {
                showPlayerCue(newResize.stringRes)
            } else {
                zoomToast?.cancel()
                zoomToast = Toast.makeText(requireContext(), newResize.stringRes, Toast.LENGTH_SHORT)
                zoomToast?.show()
            }

            UserPreferences.playerResize = newResize
            binding.pvPlayer.showController()
            updatePlayerScale()
        }

        binding.pvPlayer.controller.binding.exoSettings.setOnClickListener {
            ExpMotion.hapticTap(it)
            keepControllerVisibleForSettings()
            binding.settings.seriesId = seriesIdForAudioPref()
            binding.settings.show()
        }
        binding.pvPlayer.controller.binding.btnExoAudio.setOnClickListener {
            ExpMotion.hapticTap(it)
            keepControllerVisibleForSettings()
            binding.settings.seriesId = seriesIdForAudioPref()
            binding.settings.showAudio()
        }
        binding.settings.onHidden = {
            restoreControllerTimeoutAfterSettings()
        }

        binding.settings.setOnLocalSubtitlesClickedListener {
            isIgnoringPip = true
            pickLocalSubtitle.launch(
                arrayOf(
                    "text/plain",
                    "text/str",
                    "application/octet-stream",
                    MimeTypes.TEXT_UNKNOWN,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.TEXT_SSA,
                    MimeTypes.APPLICATION_TTML,
                    MimeTypes.APPLICATION_MP4VTT,
                    MimeTypes.APPLICATION_SUBRIP,
                )
            )
        }

        binding.settings.setOnOpenSubtitleSelectedListener { subtitle ->
            viewModel.downloadSubtitle(subtitle.openSubtitle)
        }

        binding.settings.setOnSubDLSubtitleSelectedListener { subtitle ->
            viewModel.downloadSubDLSubtitle(subtitle.subDLSubtitle)
        }

        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }

        binding.pvPlayer.controller.binding.btnSkipIntro.setOnClickListener {
            ExpMotion.hapticTap(it)
            val target = activeSkipOpportunity?.endMs
                ?: (activePlayer().currentPosition + 85_000L)
            runCatching { activePlayer().seekTo(target.coerceAtLeast(0L)) }
            skipIntroConsumed = true
            activeSkipOpportunity = null
            showSkipIntroButton(false)
        }

        binding.btnNextEpisodeAction.setOnClickListener {
            ExpMotion.hapticTap(it)
            hideNextEpisodeOverlay()
            playNextEpisodeAcrossSeasons()
        }
        binding.btnNextEpisodeDismiss.setOnClickListener {
            ExpMotion.hapticTap(it)
            nextEpisodeOverlayDismissed = true
            hideNextEpisodeOverlay()
        }
        wireEndCardChooserButtons()

        binding.settings.onManualZoomClicked = {
            binding.settings.hide()
            binding.pvPlayer.hideController()
            binding.pvPlayer.enterManualZoomMode()
        }
        binding.settings.setOnDownloadClickedListener {
            val video = currentVideo ?: return@setOnDownloadClickedListener
            val server = currentServer ?: return@setOnDownloadClickedListener
            DownloadOptionsController.enqueueFromPlayer(
                this,
                currentVideoTypeForUi(),
                server,
                video,
            )
        }
        binding.settings.setOnPlayWithClickedListener {
            openCurrentStreamExternally(forceChooser = true)
        }
        binding.settings.setOnDownloadWithClickedListener {
            openCurrentStreamWithDownloader()
        }
        binding.settings.setOnServerPlayWithListener { serverItem ->
            val selected = servers.firstOrNull { it.id == serverItem.id }
            if (selected == null) {
                notifyPlayer(R.string.player_server_unavailable, R.string.player_settings_servers_title)
                return@setOnServerPlayWithListener
            }
            if (currentServer?.id == selected.id && currentVideo != null && !isOfflinePlayback()) {
                openCurrentStreamExternally(forceChooser = true)
                return@setOnServerPlayWithListener
            }
            pendingPlayWithAfterResolve = true
            viewModel.getVideo(selected)
        }
    }

 private fun updatePlayerScale() {
        val videoSurfaceView = binding.pvPlayer.videoSurfaceView
        val playerResize = UserPreferences.playerResize 

        binding.pvPlayer.resizeMode = playerResize.resizeMode 

        when (playerResize) { 
            UserPreferences.PlayerResize.Stretch43 -> {
                val scale = 1.33f 
                videoSurfaceView?.scaleX = scale
                videoSurfaceView?.scaleY = 1f
            }
            UserPreferences.PlayerResize.StretchVertical -> {
                videoSurfaceView?.scaleX = 1f
                videoSurfaceView?.scaleY = 1.25f
            }
            UserPreferences.PlayerResize.SuperZoom -> {
                videoSurfaceView?.scaleX = 1.5f
                videoSurfaceView?.scaleY = 1.5f
            }
            else -> {
                videoSurfaceView?.scaleX = 1f
                videoSurfaceView?.scaleY = 1f
            }
        }
    }

    fun setupEpisodeNavigationButtons() {
        if (isLiveTvPlayback()) {
            setupLiveChannelZapButtons()
            return
        }
        val btnPrevious = binding.pvPlayer.controller.binding.btnCustomPrev
        val btnNext = binding.pvPlayer.controller.binding.btnCustomNext

        fun handleNavigationButton(
            button: ImageView,
            hasEpisode: () -> Boolean,
            playEpisode: () -> Unit
        ) {
            if (!hasEpisode()) {
                button.isGone = true
                return
            }

            val wasGone = button.isGone
            button.isGone = false
            if (ExperimentalMobileDesign.enabled()) {
                button.setBackgroundResource(ExperimentalMobileDesign.controlsPillBackground())
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { button.applyExpPress() }
                if (wasGone) ExpMotion.popIn(button)
            }
            button.setOnClickListener listener@{
                ExpMotion.hapticTap(button)
                if (!hasEpisode()) return@listener

                val videoType = args.videoType

                val watchItem: WatchItem? = when (videoType) {
                    is Video.Type.Movie -> database?.movieDao()?.getById(videoType.id)
                    is Video.Type.Episode -> database?.episodeDao()?.getById(videoType.id)
                }

                when (videoType) {
                    is Video.Type.Movie -> {
                        val provider = UserPreferences.currentProvider ?: return@listener
                        val movie = watchItem as? Movie
                        movie?.let { database?.movieDao()?.update(it) }
                        movie?.let { UserDataCache.addMovieToContinueWatching(requireContext(), provider, it) }
                    }

                    is Video.Type.Episode -> {
                        val provider = UserPreferences.currentProvider ?: return@listener
                        val episode = watchItem as? Episode
                        episode?.let {
                            if (player.hasFinished()) {
                                database?.episodeDao()?.resetProgressionFromEpisode(videoType.id)
                                UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, it.id)
                            }
                            database?.episodeDao()?.update(it)

                            if (!player.hasFinished()) {
                                UserDataCache.addEpisodeToContinueWatching(requireContext(), provider, it)
                            }

                            it.tvShow?.let { tvShow ->
                                database?.tvShowDao()?.getById(tvShow.id)
                            }?.let { tvShow ->
                                val episodeDao = database?.episodeDao() ?: return@let
                                val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                                val updatedTvShow = tvShow.copy().apply {
                                    merge(tvShow)
                                    isWatching = !player.hasReallyFinished() || isStillWatching
                                }
                                database?.tvShowDao()?.update(updatedTvShow)
                                CloudSyncHooks.tvShow(requireContext(), provider, updatedTvShow)
                            }
                        }
                    }
                }

                playEpisode()
            }
        }

        handleNavigationButton(
            btnPrevious,
            EpisodeManager::hasPreviousEpisode,
            ::playPreviousEpisodeAcrossSeasons,
        )
        handleNavigationButton(btnNext, EpisodeManager::hasNextEpisode, ::playNextEpisodeAcrossSeasons)
    }

    private fun refreshEpisodeNavigation(type: Video.Type.Episode) {
        lifecycleScope.launch(Dispatchers.IO) {
            database?.let { EpisodeManager.ensureNextEpisodeAvailable(type, it) }
            withContext(Dispatchers.Main) {
                setupEpisodeNavigationButtons()
            }
        }
    }

    private fun playPreviousEpisodeAcrossSeasons() {
        val type = args.videoType as? Video.Type.Episode ?: return
        if (!EpisodeManager.hasPreviousEpisode()) return

        lifecycleScope.launch {
            if (isOfflinePlayback()) {
                val prevEp = EpisodeManager.peekPreviousEpisode()
                if (prevEp != null) {
                    val prevType = Video.Type.Episode(
                        id = prevEp.id,
                        number = prevEp.number,
                        title = prevEp.title,
                        poster = prevEp.poster,
                        overview = prevEp.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = prevEp.tvShow.id,
                            title = prevEp.tvShow.title,
                            poster = prevEp.tvShow.poster,
                            banner = prevEp.tvShow.banner,
                            releaseDate = prevEp.tvShow.releaseDate,
                            imdbId = prevEp.tvShow.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = prevEp.season.number,
                            title = prevEp.season.title,
                        ),
                    )
                    val hasOffline = withContext(Dispatchers.IO) {
                        com.dskja.betterstreamflix.download.OfflinePlayback.findCompleted(
                            requireContext(),
                            prevType,
                        ) != null ||
                            com.dskja.betterstreamflix.download.OfflinePlayback.findCompletedAnyProvider(
                                requireContext(),
                                prevType,
                            ) != null
                    }
                    if (!hasOffline) {
                        showOfflinePreviousEpisodeDialog(prevType)
                        return@launch
                    }
                }
            }
            viewModel.playPreviousEpisode()
        }
    }

    private fun showOfflinePreviousEpisodeDialog(previousEpisode: Video.Type.Episode) {
        if (!isAdded || _binding == null) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.player_offline_prev_title)
            .setMessage(R.string.player_offline_prev_message)
            .setPositiveButton(R.string.player_offline_next_online) { _, _ ->
                navigateToPreviousEpisode(previousEpisode, preferredServerName = null)
            }
            .setNegativeButton(R.string.option_cancel, null)
            .show()
    }

    private fun navigateToPreviousEpisode(
        previousEpisode: Video.Type.Episode,
        preferredServerName: String?,
    ) {
        if (isTearingDown || !isAdded || _binding == null) return
        EpisodeManager.getPreviousEpisode()
        releasePlayer(ReleaseMode.SOFT_LEAVE)
        isSetupDone = false
        val live = UserPreferences.currentProvider is IptvProvider
        val action = PlayerMobileFragmentDirections
            .actionPlayerMobileFragmentSelf(
                id = previousEpisode.id,
                videoType = previousEpisode,
                title = previousEpisode.tvShow.title,
                subtitle = if (live) {
                    getString(R.string.player_live_channel)
                } else {
                    "S${previousEpisode.season.number} E${previousEpisode.number}  •  ${previousEpisode.title}"
                },
                preferredServerName = preferredServerName,
            )
        hideNextEpisodeOverlay()
        runCatching {
            findNavController().navigate(
                action,
                NavOptions.Builder()
                    .setPopUpTo(
                        findNavController().currentDestination?.id ?: return@runCatching,
                        true,
                    )
                    .setLaunchSingleTop(false)
                    .build(),
            )
        }
    }

    private fun playNextEpisodeAcrossSeasons(autoplay: Boolean = false) {
        val type = args.videoType as? Video.Type.Episode ?: return

        lifecycleScope.launch {
            if (KidsBedtime.shouldBlockAutoplay()) {
                finishForKidsBedtime()
                return@launch
            }
            if (autoplay && viewModel.isVideoResolveInFlight()) {
                // Do not cancel an in-flight failover getVideo by navigating away.
                return@launch
            }
            val hasNextEpisode = withContext(Dispatchers.IO) {
                database?.let { EpisodeManager.ensureNextEpisodeAvailable(type, it) } ?: false
            }

            setupEpisodeNavigationButtons()

            if (!hasNextEpisode) return@launch
            if (autoplay && !UserPreferences.autoplay) return@launch

            if (isOfflinePlayback()) {
                val nextEp = EpisodeManager.peekNextEpisode()
                if (nextEp != null) {
                    val nextType = Video.Type.Episode(
                        id = nextEp.id,
                        number = nextEp.number,
                        title = nextEp.title,
                        poster = nextEp.poster,
                        overview = nextEp.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = nextEp.tvShow.id,
                            title = nextEp.tvShow.title,
                            poster = nextEp.tvShow.poster,
                            banner = nextEp.tvShow.banner,
                            releaseDate = nextEp.tvShow.releaseDate,
                            imdbId = nextEp.tvShow.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = nextEp.season.number,
                            title = nextEp.season.title,
                        ),
                    )
                    val hasOffline = withContext(Dispatchers.IO) {
                        com.dskja.betterstreamflix.download.OfflinePlayback.findCompleted(
                            requireContext(),
                            nextType,
                        ) != null ||
                            com.dskja.betterstreamflix.download.OfflinePlayback.findCompletedAnyProvider(
                                requireContext(),
                                nextType,
                            ) != null
                    }
                    if (!hasOffline) {
                        if (autoplay) {
                            // Don't silently fail network getServers with offline preferredServerName.
                            return@launch
                        }
                        showOfflineNextEpisodeDialog(nextType)
                        return@launch
                    }
                }
            }

            viewModel.playNextEpisode()
        }
    }

    private fun showOfflineNextEpisodeDialog(nextEpisode: Video.Type.Episode) {
        if (!isAdded || _binding == null) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.player_offline_next_title)
            .setMessage(R.string.player_offline_next_message)
            .setPositiveButton(R.string.player_offline_next_online) { _, _ ->
                navigateToNextEpisode(nextEpisode, preferredServerName = null)
            }
            .setNegativeButton(R.string.option_cancel, null)
            .show()
    }

    private fun navigateToNextEpisode(
        nextEpisode: Video.Type.Episode,
        preferredServerName: String?,
    ) {
        if (isTearingDown || !isAdded || _binding == null) return
        EpisodeManager.getNextEpisode() // advance index to match playNextEpisode
        releasePlayer(ReleaseMode.SOFT_LEAVE)
        isSetupDone = false
        val live = UserPreferences.currentProvider is IptvProvider
        val action = PlayerMobileFragmentDirections
            .actionPlayerMobileFragmentSelf(
                id = nextEpisode.id,
                videoType = nextEpisode,
                title = nextEpisode.tvShow.title,
                subtitle = if (live) {
                    getString(R.string.player_live_channel)
                } else {
                    "S${nextEpisode.season.number} E${nextEpisode.number}  •  ${nextEpisode.title}"
                },
                preferredServerName = preferredServerName,
            )
        hideNextEpisodeOverlay()
        runCatching {
            findNavController().navigate(
                action,
                androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(
                        findNavController().currentDestination?.id ?: return@runCatching,
                        true,
                    )
                    .setLaunchSingleTop(false)
                    .build(),
            )
        }
    }

    private fun decodeBase64Uri(uri: String): String? {
        return try {
            val parts = uri.split(",")
            if (parts.size == 2 && parts[0].contains(";base64")) {
                val base64Data = parts[1]
                val decodedBytes = Base64.getDecoder().decode(base64Data)
                String(decodedBytes, Charsets.UTF_8)
            } else {
                null
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun extractUrlFromPlaylist(playlist: String): String? {
        return try {
            val lines = playlist.lines().map { it.trim() }
            lines.firstOrNull { it.startsWith("http") }
                ?: lines.firstNotNullOfOrNull { line ->
                    val regex = """URI=["'](http[^"']+)["']""".toRegex()
                    regex.find(line)?.groupValues?.get(1)
                }
        } catch (ignored: Exception) {
            null
        }
    }


    private fun displayVideo(video: Video, server: Video.Server) {
        if (isTearingDown || !isAdded || _binding == null) return
        currentVideo = video
        currentServer = server
        stuckDetector.onServerChanged()
        hideStuckOverlay()
        configureStuckDetector()
        resetSmartSkipForContent()
        val offline = server.id.equals(PlayerViewModel.OFFLINE_SERVER_ID, ignoreCase = true) ||
            server.name.equals(PlayerViewModel.OFFLINE_SERVER_NAME, ignoreCase = true)
        updatePlayerHeader()
        com.dskja.betterstreamflix.sync.WatchPartyPlayerHooks.ensureSyncRunning(this, watchPartyBridge)
        com.dskja.betterstreamflix.sync.WatchPartyPlayerHooks.onLocalControlChanged(this, watchPartyBridge)
        com.dskja.betterstreamflix.sync.WatchPartyPlayerHooks.refreshChrome(
            binding.pvPlayer.controller.binding.btnExoWatchParty,
            watchPartyBridge,
        )
        com.dskja.betterstreamflix.sync.CastRoomPlayerHooks.ensureHostHeartbeat(
            this,
            object : com.dskja.betterstreamflix.sync.CastRoomPlayerHooks.PlayerBridge {
                override fun isLiveOrOffline(): Boolean =
                    isLiveTvPlayback() || isOfflinePlayback()

                override fun videoType(): Video.Type = args.videoType

                override fun positionMs(): Long =
                    runCatching { activePlayer().currentPosition }.getOrDefault(0L)

                override fun isPlaying(): Boolean =
                    runCatching { activePlayer().isPlaying }.getOrDefault(false)

                override fun setPlayWhenReady(playing: Boolean) {
                    runCatching {
                        activePlayer().playWhenReady = playing
                        if (playing) activePlayer().play() else activePlayer().pause()
                    }
                }

                override fun currentCastRoomIndex(): Int =
                    com.dskja.betterstreamflix.sync.CastRoomUi.latestPayload()?.nowPlayingIndex ?: 0
            },
        )
        com.dskja.betterstreamflix.sync.CastRoomPlayerHooks.refreshChrome(
            binding.pvPlayer.controller.binding.btnExoCastRoom,
        )
        runCatching {
            com.dskja.betterstreamflix.platform.plugins.PluginManager
                .dispatchServerResolved(server)
            com.dskja.betterstreamflix.platform.plugins.PluginManager
                .dispatchPlaybackStarted(args.videoType, server.name)
        }

        // Offline must stay on in-app Exo + CacheDataSource — external apps cannot read Media3 cache.
        if (!offline &&
            com.dskja.betterstreamflix.platform.playerbackend.PlayerBackendSelector.shouldHandoffToExternal()
        ) {
            currentExternalPlayerTried = true
            val pos = runCatching { player.currentPosition }.getOrDefault(0L)
            com.dskja.betterstreamflix.platform.playerbackend.ExternalMpvBackend.open(
                requireContext(),
                video.source,
                video.headers.orEmpty(),
                positionMs = pos,
                title = resolvePlayerTitle(),
            )
            return
        }

        val extraBuffering = PlayerSettingsView.Settings.ExtraBuffering.isEnabled

        val softwareDecoder = PlayerSettingsView.Settings.SoftwareDecoder.isEnabled
        val vavooTlsHost = resolveVavooTlsHost(video)
        val needsReinit =
            extraBuffering != currentExtraBuffering ||
                softwareDecoder != currentSoftwareDecoder ||
                offline != playingOffline ||
                vavooTlsHost != currentVavooTlsHost
        if (needsReinit) {
            playingOffline = offline
            offlineCacheBlocked = false
            initializePlayer(extraBuffering, softwareDecoder, vavooTlsHost)
            if (offlineCacheBlocked || !::player.isInitialized || playerReleased) {
                if (offline) {
                    showPlayerError(getString(R.string.player_offline_missing))
                }
                return
            }
            player.playlistMetadata = MediaMetadata.Builder()
                .setTitle(resolvePlayerTitle())
                .setMediaServers(servers.map {
                    MediaServer(
                        id = it.id,
                        name = PlayerViewModel.mediaServerLabel(requireContext(), it),
                    )
                })
                .build()
        } else {
            playingOffline = offline
        }

        val currentPosition = player.currentPosition

        lastCastHeaders = PlaybackRequestHeaders.build(
            fallbackUserAgent = NetworkClient.USER_AGENT,
            videoHeaders = video.headers,
            pageUrl = server.src,
            sourceUrl = video.source,
        )

        httpDataSource.setDefaultRequestProperties(lastCastHeaders)

        val mediaMetadata = CastMediaFactory.buildMetadata(
            title = resolvePlayerTitle(),
            subtitle = resolvePlayerSubtitle(),
            videoType = args.videoType,
            serverId = server.id,
        )

        val subtitleConfigs = subtitleConfigurationsForPlayback(
            context = requireContext(),
            videoType = args.videoType,
            serverSubtitles = video.subtitles,
        )
        val localMediaItem = if (offline) {
            // Must use DownloadRequest streamKeys/customCacheKey — plain URI re-fetches
            // the master playlist, misses cache, and hits an expired CDN (HTTP 404).
            com.dskja.betterstreamflix.download.OfflinePlayback.buildOfflineMediaItem(
                context = requireContext(),
                video = video,
                subtitleConfigurations = subtitleConfigs,
                mediaMetadata = mediaMetadata,
            )
        } else {
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(video.source.toUri())
                .setMimeType(PlaybackMime.forPlayback(video.type, video.source))
            if (isLiveTvPlayback()) {
                mediaItemBuilder.setLiveConfiguration(
                    com.dskja.betterstreamflix.iptv.IptvLivePlayback.liveConfigurationForProvider(
                        UserPreferences.currentProvider?.name,
                    ),
                )
            }
            mediaItemBuilder
                .setSubtitleConfigurations(subtitleConfigs)
                .setMediaMetadata(mediaMetadata)
                .build()
        }
        if (isLiveTvPlayback() && !offline) {
            applyLiveControllerChrome(live = true)
        } else {
            applyLiveControllerChrome(live = false)
        }
        player.setMediaItem(localMediaItem)
        smartMuteAdsWatchdog.reset()

        if ((isCasting || CastPlaybackHub.isCasting) && !isOfflinePlayback()) {
            pushMediaToCast(video, server, mediaMetadata, startPosition = currentPosition)
        }

        wirePlayWithChromeButton(binding.pvPlayer.controller.binding)
        if (pendingPlayWithAfterResolve && !isOfflinePlayback()) {
            pendingPlayWithAfterResolve = false
            openCurrentStreamExternally(forceChooser = true)
        }
        playbackListener?.let { runCatching { player.removeListener(it) } }
        val playbackListenerLocal = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                super.onTracksChanged(tracks)
                if (!mayRunPlayerListenerWork()) return
                val marks = SkipIntroChapters.fromTracks(tracks)
                if (marks.isNotEmpty()) {
                    skipIntroChapters = marks
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                if (!mayRunPlayerListenerWork()) return
                bufferEtaController?.onPlaybackStateChanged(playbackState)
                if (playbackState == Player.STATE_READY) {
                    ensureSkipIntroProbe()
                    revealTrailerStreamBridge()
                    hideStuckOverlay()
                }
                val playWhenReady = ::player.isInitialized && !playerReleased && player.playWhenReady
                stuckDetector.onPlaybackStateChanged(playbackState, playWhenReady)
                // Keep Smart Mute Ads ticking while hung in BUFFERING (isPlaying often false).
                if (playbackState == Player.STATE_BUFFERING) {
                    startProgressHandler()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                if (!mayRunPlayerListenerWork()) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    stuckDetector.onSeek()
                    hideStuckOverlay()
                    smartMuteAdsWatchdog.onUserSeek(System.currentTimeMillis())
                }
            }

            override fun onRenderedFirstFrame() {
                super.onRenderedFirstFrame()
                if (!mayRunPlayerListenerWork()) return
                revealTrailerStreamBridge()
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                super.onIsLoadingChanged(isLoading)
                if (!mayRunPlayerListenerWork()) return
                bufferEtaController?.onIsLoadingChanged(isLoading)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                if (!mayRunPlayerListenerWork()) return
                binding.pvPlayer.keepScreenOn = isPlaying || UserPreferences.keepScreenOnWhenPaused
                stuckDetector.onIsPlayingChanged(isPlaying)

                if (isPlaying) {
                    revealTrailerStreamBridge()
                    if (::player.isInitialized && player.hasStarted()) {
                        com.dskja.betterstreamflix.providers.ProviderServerAb
                            .notePlaybackStable(currentServer)
                        // Mid-play SW rebuilds skip SuccessLoadingVideo — surface timeline here.
                        failoverTimeline.recordOk(currentServer, servers)
                        maybeShowFailoverTimeline()
                    }
                    hideStuckOverlay()
                    recordRecentlyWatchedStart()
                    ensureSkipIntroProbe()
                    startProgressHandler()
                } else {
                    // Do not stop the handler while buffering a hung pre-/mid-roll.
                    if (::player.isInitialized &&
                        player.playbackState != Player.STATE_BUFFERING
                    ) {
                        stopProgressHandler()
                    }
                }

                val hasUri = player.currentMediaItem?.localConfiguration?.uri
                    ?.toString()?.isNotEmpty()
                    ?: false

                if (!isPlaying && hasUri) {
                    if (isLiveTvPlayback()) {
                        return
                    }
                    val videoType = args.videoType
                    val started = player.hasStarted()
                    val finished = player.hasFinished()
                    val reallyFinished = player.hasReallyFinished()
                    val position = player.currentPosition
                    val duration = player.duration
                    val appContext = requireContext().applicationContext
                    val provider = UserPreferences.currentProvider
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            persistPausedWatchItem(
                                videoType = videoType,
                                started = started,
                                finished = finished,
                                reallyFinished = reallyFinished,
                                position = position,
                                duration = duration,
                                appContext = appContext,
                                provider = provider,
                            )
                        }
                    }

                    reportTraktProgress(
                        videoType = videoType,
                        positionMs = position,
                        durationMs = duration,
                        isPlaying = false,
                    )
                    if (player.hasReallyFinished() &&
                        player.playbackState == androidx.media3.common.Player.STATE_ENDED
                    ) {
                        if (KidsBedtime.shouldStopAfterEpisode()) {
                            finishForKidsBedtime()
                        } else if (com.dskja.betterstreamflix.sync.CastRoomSession.isHost()) {
                            com.dskja.betterstreamflix.sync.CastRoomPlayerHooks
                                .onLocalPlaybackEnded(this@PlayerMobileFragment)
                        } else {
                            maybeShowEndCardChooser()
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                if (isOfflinePlayback()) suppressBuiltInErrorOverlay()
                Log.e("PlayerMobileFragment", "onPlayerError: ", error)
                if (!com.dskja.betterstreamflix.extractors.ExtractorFailureClassifier
                        .isExpectedStreamNoise(error)
                ) {
                    CrashReporter.logNonFatal("PlayerMobileFragment", "onPlayerError", error)
                }
                if (!isAdded || _binding == null) return

                failoverTimeline.recordServerFailure(currentServer, servers, error)
                when (
                    val action = PlaybackFailover.decide(
                        currentServerIndex = PlaybackFailover.indexOfServer(servers, currentServer),
                        serverCount = servers.size,
                        playbackAlreadyStarted = ::player.isInitialized && player.hasStarted(),
                        softwareDecoderAlreadyEnabled = currentSoftwareDecoder,
                        // Soft mid-play errors: SW rebuild / GiveUp — hard CDN deaths still hop.
                        allowMidPlaybackFailover = PlaybackFailover.isHardSourceFailure(error),
                        externalPlayerAvailable = !isOfflinePlayback() && (
                            com.dskja.betterstreamflix.platform.playerbackend.ExternalMpvBackend.canResolve(requireContext()) ||
                                com.dskja.betterstreamflix.platform.playerbackend.PlayerBackendSelector.shouldHandoffToExternal()
                            ),
                        externalPlayerAlreadyTried = currentExternalPlayerTried,
                        error = error,
                    )
                ) {
                    is PlaybackFailover.Action.TryNextServer -> {
                        suppressBuiltInErrorOverlay()
                        pendingPlayWithAfterResolve = false
                        com.dskja.betterstreamflix.providers.ProviderServerAb
                            .notePlaybackUnstable(currentServer)
                        val next = servers.getOrNull(action.nextIndex)
                        if (next != null) {
                            Log.i(
                                "PlayerMobileFragment",
                                "Playback failed, trying next server: ${next.name}",
                            )
                            showFailoverServerCue(action.nextIndex, servers.size)
                            viewModel.getVideo(next)
                        } else {
                            showStreamFailure(error, serversExhausted = true)
                        }
                    }
                    PlaybackFailover.Action.RetrySoftwareDecoder -> {
                        suppressBuiltInErrorOverlay()
                        pendingPlayWithAfterResolve = false
                        failoverTimeline.recordSoftwareDecoder()
                        // Leave currentSoftwareDecoder stale so needsReinit rebuilds Exo.
                        PlayerSettingsView.Settings.SoftwareDecoder.init(true)
                        PlayerSettingsView.Settings.SoftwareDecoder.selectedValue = true
                        val video = currentVideo
                        val server = currentServer
                        if (video != null && server != null) {
                            showPlayerCue(R.string.player_retry_software_decoder)
                            displayVideo(video, server)
                        }
                    }
                    PlaybackFailover.Action.TryExternalPlayer -> {
                        pendingPlayWithAfterResolve = false
                        currentExternalPlayerTried = true
                        failoverTimeline.recordExternalPlayer()
                        val video = currentVideo
                        if (video != null) {
                            showPlayerCue(R.string.player_retry_external_player)
                            val pos = runCatching { player.currentPosition }.getOrDefault(0L)
                            com.dskja.betterstreamflix.platform.playerbackend.ExternalMpvBackend.open(
                                requireContext(),
                                video.source,
                                video.headers.orEmpty(),
                                positionMs = pos,
                                title = resolvePlayerTitle(),
                            )
                        }
                    }
                    PlaybackFailover.Action.GiveUp -> {
                        pendingPlayWithAfterResolve = false
                        if (isOfflinePlayback()) {
                            showPlayerError(getString(R.string.player_offline_missing))
                        } else {
                            showStreamFailure(
                                error,
                                serversExhausted = true,
                                fallbackMessage = error.message ?: error.errorCodeName,
                            )
                        }
                    }
                }
            }
        }
        playbackListener = playbackListenerLocal
        player.addListener(playbackListenerLocal)

        if (currentPosition == 0L) {
            if (isLiveTvPlayback()) {
                // Live streams should start at the live edge, not a saved VOD position.
                player.seekToDefaultPosition()
            } else {
            val videoType = args.videoType
            val provider = UserPreferences.currentProvider
            val appContext = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val watchItem: WatchItem? = when (videoType) {
                    is Video.Type.Movie -> {
                        var movie = if (provider != null) {
                            UserDataCache.read(appContext, provider)?.continueWatchingMovies
                                ?.find { it.id == videoType.id }?.toMovie()
                        } else null
                        movie ?: database?.movieDao()?.getById(videoType.id)
                    }
                    is Video.Type.Episode -> {
                        var episode = if (provider != null) {
                            UserDataCache.read(appContext, provider)?.continueWatchingEpisodes
                                ?.find { it.id == videoType.id }?.toEpisode()
                        } else null
                        episode ?: database?.episodeDao()?.getById(videoType.id)
                    }
                }
                val savedPosition = watchItem?.watchHistory?.lastPlaybackPositionMillis
                withContext(Dispatchers.Main) {
                    if (!isAdded || playerReleased || isTearingDown || playbackSoftStopped || !::player.isInitialized) {
                        return@withContext
                    }
                    if (savedPosition == null) return@withContext
                    if (!PlaybackProgress.shouldApplyResume(player.currentPosition)) return@withContext
                    val target = PlaybackProgress.resumeTargetMs(savedPosition, player.duration)
                    runCatching { player.seekTo(target) }
                }
            }
            }
        } else {
            player.seekTo(currentPosition)
        }

        if (isTearingDown || playerReleased || !isAdded || _binding == null) return
        player.prepare()
        playLocalIfForeground()
    }

    private fun maybeResumeLocalPlayback() {
        if (isTearingDown || playbackSoftStopped || playerReleased || !::player.isInitialized) return
        if (isCasting || CastPlaybackHub.isCasting) {
            runCatching {
                player.volume = 0f
                player.playWhenReady = false
            }
            return
        }
        if (skipNextAutoResume) {
            skipNextAutoResume = false
            return
        }
        // IDLE means nothing is prepared yet — play() would look like a dead "Playing" state.
        if (!player.isPlaying && player.playbackState != androidx.media3.common.Player.STATE_IDLE) {
            runCatching {
                player.volume = 1f
                player.play()
            }
        }
    }

    private fun playLocalIfForeground() {
        if (isTearingDown || playbackSoftStopped || playerReleased || !::player.isInitialized) return
        if (isCasting || CastPlaybackHub.isCasting) {
            runCatching {
                player.volume = 0f
                player.playWhenReady = false
                player.pause()
            }
            return
        }
        runCatching {
            player.volume = 1f
            player.play()
        }
    }

    private fun installExtraSubtitle(
        configuration: MediaItem.SubtitleConfiguration,
        resumePositionMs: Long,
    ) {
        if (isTearingDown || playerReleased || !::player.isInitialized) return
        val current = player.currentMediaItem ?: return
        runCatching {
            player.setMediaItem(
                PlaybackMediaItems.addSubtitle(current, configuration),
                resumePositionMs.coerceAtLeast(0L),
            )
            player.prepare()
        }
        playLocalIfForeground()
    }

    private fun pauseLocalForHandoff() {
        skipNextAutoResume = true
        if (!::player.isInitialized || playerReleased) return
        runCatching {
            player.playWhenReady = false
            player.pause()
        }
    }

    private fun enterPIPMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isTearingDown || !isAdded || _binding == null || playerReleased) return
        val host = activity ?: return
        if (host.isFinishing || host.isDestroyed) return
        if (!host.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            binding.settings.hide()
            restoreControllerTimeoutAfterSettings()
            binding.pvPlayer.useController = false
            val params = PictureInPictureParams.Builder()
            if (::player.isInitialized && !playerReleased) {
                val format = player.videoFormat
                val w = format?.width ?: 0
                val h = format?.height ?: 0
                if (w > 0 && h > 0) {
                    params.setAspectRatio(Rational(w, h))
                }
            }
            host.enterPictureInPictureMode(params.build())
        }.onFailure {
            Log.w("Player", "enterPIPMode skipped: ${it.message}")
        }
    }

    private fun playerPlaybackOrientation(): Int {
        // Tablets: full sensor. Phones: landscape by default; optional portrait via Settings.
        return if (resources.configuration.smallestScreenWidthDp >= 600) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else if (UserPreferences.allowPhonePortraitPlayback) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun playerExitOrientation(): Int {
        // Tablets: sensor-portrait so browse is not left unspecified after landscape playback.
        return if (resources.configuration.smallestScreenWidthDp >= 600) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun setControlsLocked(locked: Boolean) {
        val b = _binding ?: return
        controlsLocked = locked
        if (::gestureHelper.isInitialized) {
            gestureHelper.controlsLocked = locked
        }
        val controller = b.pvPlayer.controller.binding
        if (locked) {
            controller.gControlsLock.isGone = true
            controller.btnExoUnlock.isVisible = true
            controller.btnExoUnlock.contentDescription = getString(R.string.exp_cd_unlock)
            b.pvPlayer.controllerShowTimeoutMs = 0
            b.pvPlayer.showController()
        } else {
            controller.gControlsLock.isVisible = true
            controller.btnExoUnlock.isGone = true
            if (!settingsKeepingController) {
                b.pvPlayer.controllerShowTimeoutMs = defaultControllerShowTimeoutMs
            }
        }
    }

    private fun keepControllerVisibleForSettings() {
        val b = _binding ?: return
        if (!settingsKeepingController) {
            savedControllerShowTimeoutMs = b.pvPlayer.controllerShowTimeoutMs
            settingsKeepingController = true
        }
        b.pvPlayer.controllerShowTimeoutMs = 0
        b.pvPlayer.showController()
    }

    private fun restoreControllerTimeoutAfterSettings() {
        val b = _binding ?: return
        if (!settingsKeepingController) return
        settingsKeepingController = false
        if (!controlsLocked) {
            b.pvPlayer.controllerShowTimeoutMs =
                savedControllerShowTimeoutMs.coerceAtLeast(defaultControllerShowTimeoutMs)
        }
    }

    private fun applyPlayerSafeAreaInsets() {
        val b = _binding ?: return
        val controllerRoot = b.pvPlayer.controller.binding.root
        ViewCompat.setOnApplyWindowInsetsListener(controllerRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.settings) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(controllerRoot)
        ViewCompat.requestApplyInsets(b.settings)
    }


    private fun ExoPlayer.hasStarted(): Boolean =
        PlaybackProgress.hasStarted(currentPosition, duration)

    private suspend fun persistPausedWatchItem(
        videoType: Video.Type,
        started: Boolean,
        finished: Boolean,
        reallyFinished: Boolean,
        position: Long,
        duration: Long,
        appContext: android.content.Context,
        provider: com.dskja.betterstreamflix.providers.Provider?,
    ) {
        val watchItem: WatchItem? = when (videoType) {
            is Video.Type.Movie -> database?.movieDao()?.getById(videoType.id)
            is Video.Type.Episode -> database?.episodeDao()?.getById(videoType.id)
        }
        when {
            started && !finished -> {
                watchItem?.isWatched = false
                watchItem?.watchedDate = null
                val storedDuration = PlaybackProgress.durationToStore(
                    duration,
                    watchItem?.watchHistory?.durationMillis,
                )
                watchItem?.watchHistory = WatchItem.WatchHistory(
                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                    lastPlaybackPositionMillis = position,
                    durationMillis = storedDuration,
                )
            }
            finished -> {
                watchItem?.isWatched = true
                watchItem?.watchedDate = java.util.Calendar.getInstance()
                watchItem?.watchHistory = null
            }
        }
        val resolvedProvider = provider ?: return
        when (videoType) {
                is Video.Type.Movie -> {
                    (watchItem as? Movie)?.let {
                        database?.movieDao()?.update(it)
                        UserDataCache.syncMovieToCache(appContext, resolvedProvider, it)
                        if (finished) {
                            SmartDownloadsManager.onMovieFinished(
                                appContext,
                                videoType,
                                positionMs = position,
                                durationMs = duration,
                                playbackEnded = reallyFinished,
                            )
                        }
                    }
                }
            is Video.Type.Episode -> {
                (watchItem as? Episode)?.let { episode ->
                        if (finished) {
                            com.dskja.betterstreamflix.support.SupportPromptPolicy
                                .recordEpisodeWatched(videoType.id)
                            com.dskja.betterstreamflix.download.SmartDownloadsManager.onEpisodeFinished(
                                appContext,
                                videoType,
                                positionMs = position,
                                durationMs = duration,
                                playbackEnded = reallyFinished,
                            )
                        database?.episodeDao()?.resetProgressionFromEpisode(videoType.id)
                        UserDataCache.removeEpisodeFromContinueWatching(
                            appContext,
                            resolvedProvider,
                            episode.id,
                        )
                        queueNextEpisodeForContinueWatching(resolvedProvider)
                    }
                    database?.episodeDao()?.update(episode)
                    if (!finished) {
                        UserDataCache.syncEpisodeToCache(appContext, resolvedProvider, episode)
                    }
                    episode.tvShow?.let { show ->
                        database?.tvShowDao()?.getById(show.id)
                    }?.let { tvShow ->
                        val isStillWatching = database?.episodeDao()?.hasAnyWatchHistoryForTvShow(tvShow.id)
                        val updatedTvShow = tvShow.copy().apply {
                            merge(tvShow)
                            isWatching = !reallyFinished || isStillWatching == true
                        }
                        database?.tvShowDao()?.update(updatedTvShow)
                        com.dskja.betterstreamflix.sync.CloudSyncHooks.tvShow(
                            appContext,
                            resolvedProvider,
                            updatedTvShow,
                        )
                    }
                }
            }
        }
    }

    private fun recordRecentlyWatchedStart() {
        if (!isAdded) return
        val videoType = currentVideoTypeForUi()
        val playedAtMillis = System.currentTimeMillis()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { persistRecentlyWatched(videoType, playedAtMillis) }
        }
    }

    private fun persistRecentlyWatched(videoType: Video.Type, playedAtMillis: Long) {
        when (videoType) {
            is Video.Type.Movie -> {
                if (database?.movieDao()?.markRecentlyWatched(videoType.id, playedAtMillis) == 0) {
                    database?.movieDao()?.insert(
                        Movie(
                            id = videoType.id,
                            title = videoType.title,
                            released = videoType.releaseDate,
                            poster = videoType.poster,
                            imdbId = videoType.imdbId,
                        ).apply {
                            lastPlayedAtMillis = playedAtMillis
                        }
                    )
                }
            }

            is Video.Type.Episode -> {
                val storedTvShow = database?.tvShowDao()?.getById(videoType.tvShow.id)
                    ?: TvShow(
                        id = videoType.tvShow.id,
                        title = videoType.tvShow.title,
                        released = videoType.tvShow.releaseDate,
                        poster = videoType.tvShow.poster,
                        banner = videoType.tvShow.banner,
                        imdbId = videoType.tvShow.imdbId,
                    ).apply {
                        lastPlayedAtMillis = playedAtMillis
                        lastPlayedEpisodeId = videoType.id
                        database?.tvShowDao()?.insert(this)
                    }

                if (database?.episodeDao()?.getById(videoType.id) == null) {
                    database?.episodeDao()?.insert(
                        Episode(
                            id = videoType.id,
                            number = videoType.number,
                            title = videoType.title,
                            poster = videoType.poster,
                            overview = videoType.overview,
                            tvShow = storedTvShow,
                            season = Season(
                                number = videoType.season.number,
                                title = videoType.season.title.orEmpty(),
                            ),
                        )
                    )
                }
                database?.tvShowDao()?.markRecentlyWatched(
                    id = videoType.tvShow.id,
                    episodeId = videoType.id,
                    playedAtMillis = playedAtMillis,
                )
            }
        }
    }

    private fun ExoPlayer.hasFinished(): Boolean =
        PlaybackProgress.hasFinished(currentPosition, duration)

    private fun ExoPlayer.hasReallyFinished(): Boolean =
        PlaybackProgress.hasReallyFinished(
            currentPosition,
            duration,
            UserPreferences.autoplayBuffer,
        )

    private fun currentVideoTypeForUi(): Video.Type = when (val type = args.videoType) {
        is Video.Type.Episode -> EpisodeManager.getCurrentEpisode()
            ?.takeIf { currentEpisode ->
                // Same show: prefer EpisodeManager (Cast queue advance syncs index to E₂+).
                currentEpisode.id == type.id ||
                    currentEpisode.tvShow.id == type.tvShow.id
            }
            ?: type
        is Video.Type.Movie -> type
    }


    private fun isLiveTvPlayback(): Boolean =
        UserPreferences.currentProvider is IptvProvider

    private fun isOfflinePlayback(): Boolean =
        currentServer?.id == PlayerViewModel.OFFLINE_SERVER_ID ||
            currentServer?.name.equals(PlayerViewModel.OFFLINE_SERVER_NAME, ignoreCase = true) ||
            args.preferredServerName.equals(PlayerViewModel.OFFLINE_SERVER_NAME, ignoreCase = true)

    /**
     * Live/IPTV must not show VOD-style position/duration clocks.
     * Media3 reports the HLS sliding window (~30s) as duration, which looks broken.
     * Offline chrome (pill + cast/external guards) is applied after live chrome so it is not wiped.
     */
    private fun applyLiveControllerChrome(live: Boolean) {
        val showLive = live && !isOfflinePlayback()
        val controller = binding.pvPlayer.controller.binding
        controller.exoProgress.isVisible = !showLive
        controller.exoRew.isVisible = !showLive
        controller.exoFfwd.isVisible = !showLive
        controller.exoPosition.isVisible = !showLive
        controller.tvTimeSeparator.isVisible = !showLive
        controller.exoDuration.isVisible = !showLive
        controller.tvLiveIndicator.isVisible = showLive
        runCatching {
            controller.mediaRouteButton.isVisible = !showLive && UserPreferences.castEnabled && !isOfflinePlayback()
        }
        if (showLive) {
            val wasLive = controller.tvLiveIndicator.getTag(R.id.exp_enter_animated_tag) == true
            controller.tvLiveIndicator.text = getString(R.string.player_live_badge)
            controller.tvLiveIndicator.isClickable = true
            controller.tvLiveIndicator.isFocusable = true
            controller.tvLiveIndicator.setOnClickListener {
                ExpMotion.hapticTap(it)
                showLiveChannelGuideSheet()
            }
            if (!wasLive) {
                controller.tvLiveIndicator.setTag(R.id.exp_enter_animated_tag, true)
                if (ExperimentalMobileDesign.enabled()) {
                    ExpMotion.popIn(controller.tvLiveIndicator)
                }
            }
            if (ExperimentalMobileDesign.enabled()) {
                runCatching {
                    controller.tvLiveIndicator.startAnimation(
                        android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.live_badge_pulse),
                    )
                }
                controller.tvLiveIndicator.setBackgroundResource(
                    ExperimentalMobileDesign.liveIndicatorBackground(),
                )
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                    controller.tvLiveIndicator.applyExpPress()
                }
                controller.btnGoLive.setBackgroundResource(
                    ExperimentalMobileDesign.primaryButtonBackground(),
                )
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                    controller.btnGoLive.applyExpPress()
                }
            } else {
                runCatching {
                    controller.tvLiveIndicator.startAnimation(
                        android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.live_badge_pulse),
                    )
                }
            }
            controller.btnGoLive.setOnClickListener {
                ExpMotion.hapticTap(it)
                if (::player.isInitialized) {
                    com.dskja.betterstreamflix.iptv.IptvLivePlayback.seekToLiveEdge(player)
                    controller.btnGoLive.isVisible = false
                }
            }
            refreshLiveChannelMeta()
            setupLiveChannelZapButtons()
            ensureLiveChannelGuide()
            startLiveEdgeWatcher()
            applyOfflineCastExternalGuards(offline = false)
        } else {
            controller.tvLiveIndicator.clearAnimation()
            controller.tvLiveIndicator.setTag(R.id.exp_enter_animated_tag, null)
            controller.tvLiveIndicator.setOnClickListener(null)
            controller.btnGoLive.isVisible = false
            controller.tvLiveChannelMeta.isVisible = false
            stopLiveEdgeWatcher()
            applyOfflineControllerChrome()
        }
    }

    /** Offline chrome + cast/external disable — must run after [applyLiveControllerChrome] VOD branch. */
    private fun applyOfflineControllerChrome() {
        val controller = binding.pvPlayer.controller.binding
        val offline = isOfflinePlayback()
        applyOfflineCastExternalGuards(offline)
        // The LIVE pill is colorPrimary (#E50914) and shares the time-row start
        // with exo_position. Hide it for every offline item, including when the
        // active provider is live TV.
        if (offline || !isLiveTvPlayback()) {
            controller.tvLiveIndicator.clearAnimation()
            controller.tvLiveIndicator.setOnClickListener(null)
            controller.tvLiveIndicator.isVisible = false
            controller.tvLiveIndicator.isClickable = false
            controller.btnGoLive.isVisible = false
            controller.tvLiveChannelMeta.isVisible = false
        }
        if (offline) {
            suppressBuiltInErrorOverlay()
            updatePlayerHeader()
        }
    }

    private fun suppressBuiltInErrorOverlay() {
        runCatching {
            binding.pvPlayer.setErrorMessageProvider { android.util.Pair(0, "") }
            binding.pvPlayer.findViewById<android.view.View>(
                androidx.media3.ui.R.id.exo_error_message,
            )?.visibility = android.view.View.GONE
        }
    }

    private fun applyOfflineCastExternalGuards(offline: Boolean) {
        val controller = binding.pvPlayer.controller.binding
        runCatching {
            // IPTV live Cast is enabled when wired via CastMediaFactory liveConfiguration.
            controller.mediaRouteButton.isVisible =
                !offline && UserPreferences.castEnabled
            controller.mediaRouteButton.isEnabled = !offline
        }
        controller.btnExoExternalPlayer.isEnabled = !offline
        controller.btnExoExternalPlayer.alpha = if (offline) 0.4f else 1f
        if (offline) {
            controller.btnExoExternalPlayer.setOnClickListener {
                Toast.makeText(
                    requireContext(),
                    R.string.player_offline_external_unavailable,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            controller.btnExoExternalPlayer.setOnLongClickListener(null)
        } else {
            wirePlayWithChromeButton(controller)
        }
        updateCastQueueChrome()
    }

    private var liveEdgeWatcher: Runnable? = null

    private fun startLiveEdgeWatcher() {
        stopLiveEdgeWatcher()
        val handler = binding.pvPlayer.handler ?: return
        val tick = object : Runnable {
                override fun run() {
                if (isTearingDown || !isAdded || _binding == null || playerReleased ||
                    !::player.isInitialized || !isLiveTvPlayback()
                ) {
                    return
                }
                val behind = com.dskja.betterstreamflix.iptv.IptvLivePlayback.isBehindLiveEdge(player)
                val goLive = binding.pvPlayer.controller.binding.btnGoLive
                val wasVisible = goLive.isVisible
                goLive.isVisible = behind
                if (behind && !wasVisible && ExperimentalMobileDesign.enabled()) {
                    with(com.dskja.betterstreamflix.utils.ExpPressEffects) { goLive.applyExpPress() }
                    ExpMotion.popIn(goLive)
                    runCatching {
                        goLive.startAnimation(
                            android.view.animation.AnimationUtils.loadAnimation(
                                requireContext(),
                                R.anim.live_badge_pulse,
                            ),
                        )
                    }
                } else if (!behind) {
                    goLive.clearAnimation()
                }
                if (!isTearingDown && isAdded && _binding != null) {
                    handler.postDelayed(this, 1_500L)
                }
            }
        }
        liveEdgeWatcher = tick
        handler.post(tick)
    }

    private fun stopLiveEdgeWatcher() {
        liveEdgeWatcher?.let { tick ->
            _binding?.pvPlayer?.handler?.removeCallbacks(tick)
                ?: android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(tick)
        }
        liveEdgeWatcher = null
    }

    private fun refreshLiveChannelMeta() {
        val controller = binding.pvPlayer.controller.binding
        val idx = com.dskja.betterstreamflix.iptv.IptvLiveSession.currentIndex()
        val channel = com.dskja.betterstreamflix.iptv.IptvLiveSession.current()
            ?: run {
                val id = when (val t = args.videoType) {
                    is Video.Type.Episode -> t.tvShow.id
                    is Video.Type.Movie -> t.id
                }
                com.dskja.betterstreamflix.iptv.IptvLiveSession.Channel(
                    id = id,
                    name = resolvePlayerTitle(),
                )
            }
        val wasVisible = controller.tvLiveChannelMeta.isVisible
        val chLabel = if (idx >= 0) {
            getString(R.string.player_live_channel_meta, idx + 1, channel.name)
        } else {
            channel.name
        }
        val program = com.dskja.betterstreamflix.iptv.IptvProgramGuide.formatMetaLine(channel)
        controller.tvLiveChannelMeta.text = if (program.isNullOrBlank()) {
            chLabel
        } else {
            getString(R.string.player_live_channel_meta_program, chLabel, program)
        }
        controller.tvLiveChannelMeta.isVisible = true
        controller.tvLiveChannelMeta.isClickable = true
        controller.tvLiveChannelMeta.isFocusable = true
        controller.tvLiveChannelMeta.setOnClickListener {
            ExpMotion.hapticTap(it)
            showLiveChannelGuideSheet()
        }
        if (ExperimentalMobileDesign.enabled()) {
            controller.tvLiveChannelMeta.setBackgroundResource(
                ExperimentalMobileDesign.metaPillBackground(),
            )
            if (!wasVisible) ExpMotion.popIn(controller.tvLiveChannelMeta)
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                controller.tvLiveChannelMeta.applyExpPress()
            }
        }
    }

    private fun showLiveChannelGuideSheet() {
        if (!isLiveTvPlayback()) return
        val channels = com.dskja.betterstreamflix.iptv.IptvLiveSession.snapshot()
        if (channels.isEmpty()) {
            if (ExperimentalMobileDesign.enabled()) {
                showLiveChannelGuideEmptyExp()
            } else {
                Toast.makeText(requireContext(), R.string.player_live_no_more_channels, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val currentId = com.dskja.betterstreamflix.iptv.IptvLiveSession.current()?.id
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val useExp = ExperimentalMobileDesign.enabled()
        val root = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            if (useExp) {
                setBackgroundResource(ExperimentalMobileDesign.bottomSheetBackground())
            } else {
                setBackgroundColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorSurface,
                    ),
                )
            }
            setPadding(20.dp(requireContext()), 16.dp(requireContext()), 20.dp(requireContext()), 28.dp(requireContext()))
        }
        if (useExp) ExperimentalMobileDesign.applyReducedGlass(root)
        if (useExp) ExpMotion.enterScreen(root)
        val title = android.widget.TextView(requireContext()).apply {
            text = getString(R.string.player_live_channel_guide)
            if (useExp) setTextAppearance(R.style.TextAppearance_Lumina_Title)
            else setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setPadding(0, 0, 0, 8.dp(requireContext()))
        }
        root.addView(title)
        if (useExp) {
            val rule = View(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(40.dp(requireContext()), 3.dp(requireContext()))
                setBackgroundResource(R.drawable.bg_cinema_accent_rule)
            }
            root.addView(rule)
            ExpMotion.revealHeader(title)
            ExpMotion.pulseAccentRule(rule)
        }
        val recent = com.dskja.betterstreamflix.iptv.IptvLiveSession.recent(6)
            .filter { it.id != currentId }
        if (recent.isNotEmpty()) {
            val recentLabel = android.widget.TextView(requireContext()).apply {
                text = getString(R.string.player_live_recent_channels)
                setPadding(0, 8.dp(requireContext()), 0, 4.dp(requireContext()))
                alpha = 0.8f
            }
            root.addView(recentLabel)
            recent.forEach { channel ->
                val catalogIdx = channels.indexOfFirst { it.id == channel.id }
                val subtitle = com.dskja.betterstreamflix.iptv.IptvProgramGuide.formatGuideSubtitle(channel)
                val row = android.widget.TextView(requireContext()).apply {
                    text = buildString {
                        val label = if (catalogIdx >= 0) {
                            getString(R.string.player_live_channel_meta, catalogIdx + 1, channel.name)
                        } else {
                            channel.name
                        }
                        append(label)
                        append("  ·  ").append(getString(R.string.player_live_recent_badge))
                        if (!subtitle.isNullOrBlank()) append('\n').append(subtitle)
                    }
                    if (useExp) {
                        setTextAppearance(R.style.TextAppearance_Lumina_Body)
                        setBackgroundResource(ExperimentalMobileDesign.optionItemBackground())
                    }
                    setPadding(14.dp(requireContext()), 12.dp(requireContext()), 14.dp(requireContext()), 12.dp(requireContext()))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        ExpMotion.hapticTap(it)
                        sheet.dismiss()
                        com.dskja.betterstreamflix.iptv.IptvLiveSession.setCurrent(channel.id)
                        showLiveCue(R.string.player_live_zapping)
                        viewModel.playLiveChannel(channel)
                    }
                }
                if (useExp) {
                    with(com.dskja.betterstreamflix.utils.ExpPressEffects) { row.applyExpPress() }
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = 8.dp(requireContext()) }
                root.addView(row, lp)
            }
        }
        val scroll = android.widget.ScrollView(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (320.dp(requireContext())),
            )
        }
        val list = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 12.dp(requireContext()), 0, 0)
        }
        val rowBg = if (useExp) ExperimentalMobileDesign.optionItemBackground() else 0
        var currentRow: View? = null
        channels.forEachIndexed { index, channel ->
            val subtitle = com.dskja.betterstreamflix.iptv.IptvProgramGuide.formatGuideSubtitle(channel)
            val row = android.widget.TextView(requireContext()).apply {
                text = buildString {
                    append(getString(R.string.player_live_channel_meta, index + 1, channel.name))
                    if (!subtitle.isNullOrBlank()) append('\n').append(subtitle)
                }
                if (useExp) setTextAppearance(R.style.TextAppearance_Lumina_Body)
                if (rowBg != 0) setBackgroundResource(rowBg)
                setPadding(14.dp(requireContext()), 12.dp(requireContext()), 14.dp(requireContext()), 12.dp(requireContext()))
                isClickable = true
                isFocusable = true
                if (channel.id == currentId) {
                    isSelected = true
                    if (useExp) {
                        setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                        setTextColor(
                            com.google.android.material.color.MaterialColors.getColor(
                                this, com.google.android.material.R.attr.colorOnPrimary,
                            )
                        )
                    } else {
                        setTextColor(
                            com.google.android.material.color.MaterialColors.getColor(
                                this, androidx.appcompat.R.attr.colorPrimary,
                            )
                        )
                    }
                }
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    sheet.dismiss()
                    if (channel.id == currentId) return@setOnClickListener
                    com.dskja.betterstreamflix.iptv.IptvLiveSession.setCurrent(channel.id)
                    showLiveCue(R.string.player_live_zapping)
                    viewModel.playLiveChannel(channel)
                }
            }
            if (useExp) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { row.applyExpPress() }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 8.dp(requireContext()) }
            list.addView(row, lp)
            if (channel.id == currentId) currentRow = row
            if (useExp && (index < 12 || channel.id == currentId)) {
                row.alpha = 0f
                row.postDelayed({ ExpMotion.popIn(row) }, 28L * index.coerceAtMost(12))
            }
        }
        scroll.addView(list)
        root.addView(scroll)
        sheet.setContentView(root)
        sheet.show()
        currentRow?.let { row ->
            scroll.post {
                scroll.smoothScrollTo(0, row.top.coerceAtLeast(0))
            }
        }
    }

    private fun showLiveChannelGuideEmptyExp() {
        val emptySheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val emptyRoot = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(ExperimentalMobileDesign.bottomSheetBackground())
            setPadding(20.dp(requireContext()), 20.dp(requireContext()), 20.dp(requireContext()), 28.dp(requireContext()))
        }
        ExperimentalMobileDesign.applyReducedGlass(emptyRoot)
        val emptyTitle = android.widget.TextView(requireContext()).apply {
            text = getString(R.string.player_live_channel_guide)
            setTextAppearance(R.style.TextAppearance_Lumina_Title)
            setPadding(0, 12.dp(requireContext()), 0, 0)
        }
        val emptyBody = android.widget.TextView(requireContext()).apply {
            text = getString(R.string.player_live_no_more_channels)
            setTextAppearance(R.style.TextAppearance_Lumina_Body)
            setPadding(0, 12.dp(requireContext()), 0, 16.dp(requireContext()))
        }
        emptyRoot.addView(emptyTitle)
        emptyRoot.addView(emptyBody)
        emptySheet.setContentView(emptyRoot)
        emptySheet.show()
    }

    private fun ensureLiveChannelGuide() {
        val provider = UserPreferences.currentProvider ?: return
        val aroundId = when (val t = args.videoType) {
            is Video.Type.Episode -> t.tvShow.id.ifBlank { t.id }
            is Video.Type.Movie -> t.id
        }
        com.dskja.betterstreamflix.iptv.IptvLiveSession.setCurrent(aroundId)
        lifecycleScope.launch(Dispatchers.IO) {
            com.dskja.betterstreamflix.iptv.IptvLiveSession.ensureLoaded(provider, aroundId)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                refreshLiveChannelMeta()
                setupLiveChannelZapButtons()
            }
        }
    }

    private fun setupLiveChannelZapButtons() {
        if (!isLiveTvPlayback()) return
        val btnPrevious = binding.pvPlayer.controller.binding.btnCustomPrev
        val btnNext = binding.pvPlayer.controller.binding.btnCustomNext
        val hasPrev = com.dskja.betterstreamflix.iptv.IptvLiveSession.hasPrevious()
        val hasNext = com.dskja.betterstreamflix.iptv.IptvLiveSession.hasNext()
        val prevWasGone = btnPrevious.isGone
        val nextWasGone = btnNext.isGone
        if (ExperimentalMobileDesign.enabled()) {
            val pill = ExperimentalMobileDesign.controlsPillBackground()
            if (hasPrev) {
                btnPrevious.animate().cancel()
                btnPrevious.alpha = 1f
                btnPrevious.isVisible = true
                btnPrevious.setBackgroundResource(pill)
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { btnPrevious.applyExpPress() }
                if (prevWasGone) ExpMotion.popIn(btnPrevious)
            } else if (!prevWasGone) {
                ExpMotion.fadeOutAndHide(btnPrevious)
            } else {
                btnPrevious.isGone = true
            }
            if (hasNext) {
                btnNext.animate().cancel()
                btnNext.alpha = 1f
                btnNext.isVisible = true
                btnNext.setBackgroundResource(pill)
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { btnNext.applyExpPress() }
                if (nextWasGone) ExpMotion.popIn(btnNext)
            } else if (!nextWasGone) {
                ExpMotion.fadeOutAndHide(btnNext)
            } else {
                btnNext.isGone = true
            }
        } else {
            btnPrevious.isGone = !hasPrev
            btnNext.isGone = !hasNext
        }
        btnPrevious.contentDescription = getString(R.string.player_live_prev_channel)
        btnNext.contentDescription = getString(R.string.player_live_next_channel)
        btnPrevious.setOnClickListener {
            ExpMotion.hapticTap(it)
            val channel = com.dskja.betterstreamflix.iptv.IptvLiveSession.previous()
            if (channel == null) {
                showLiveCue(R.string.player_live_no_more_channels)
                return@setOnClickListener
            }
            showLiveCue(R.string.player_live_zapping)
            viewModel.playLiveChannel(channel)
        }
        btnNext.setOnClickListener {
            ExpMotion.hapticTap(it)
            val channel = com.dskja.betterstreamflix.iptv.IptvLiveSession.next()
            if (channel == null) {
                showLiveCue(R.string.player_live_no_more_channels)
                return@setOnClickListener
            }
            showLiveCue(R.string.player_live_zapping)
            viewModel.playLiveChannel(channel)
        }
    }

    private fun showLiveCue(@androidx.annotation.StringRes messageRes: Int) {
        showPlayerCue(getString(messageRes))
    }

    /** Light in-player cue under Exp (failover / live zap / zoom); Toast otherwise. */
    private fun showPlayerCue(@androidx.annotation.StringRes messageRes: Int) {
        showPlayerCue(getString(messageRes))
    }

    private fun showPlayerCue(message: CharSequence, holdMs: Long = 1_200L) {
        // Failover timeline always uses an in-player pill so the story is readable.
        val forcePill = holdMs >= FailoverTimelineUi.SUCCESS_CUE_MS
        if (!ExperimentalMobileDesign.enabled() && !forcePill) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            return
        }
        val host = binding.root as? android.view.ViewGroup ?: run {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            return
        }
        host.findViewWithTag<android.widget.TextView>("exp_live_cue")?.let { existing ->
            host.removeView(existing)
        }
        val density = resources.displayMetrics.density
        val cue = android.widget.TextView(requireContext()).apply {
            tag = "exp_live_cue"
            text = message
            gravity = android.view.Gravity.CENTER
            setTextAppearance(R.style.TextAppearance_Lumina_Caption)
            setBackgroundResource(
                if (ExperimentalMobileDesign.enabled()) {
                    ExperimentalMobileDesign.metaPillBackground()
                } else {
                    R.drawable.bg_player_cue_tv
                },
            )
            val padH = (16 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            elevation = 8f * density
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurface,
                ),
            )
            contentDescription = message
        }
        val lp = if (host is androidx.constraintlayout.widget.ConstraintLayout) {
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = (72 * density).toInt()
            }
        } else {
            android.view.ViewGroup.MarginLayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (72 * density).toInt()
                leftMargin = (48 * density).toInt()
                rightMargin = (48 * density).toInt()
            }
        }
        host.addView(cue, lp)
        ExpMotion.popIn(cue)
        cue.postDelayed({
            ExpMotion.fadeOutAndHide(cue)
            cue.postDelayed({ runCatching { host.removeView(cue) } }, 280L)
        }, holdMs)
    }

    private fun revealTrailerStreamBridge() {
        val bridge = _binding?.ivTrailerStreamBridge ?: return
        TrailerStreamCrossfade.revealStream(bridge)
    }

    private fun resolvePlayerTitle(videoType: Video.Type = currentVideoTypeForUi()): String {
        return when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title.ifBlank { args.title }
        }
    }

    private fun resolvePlayerSubtitle(videoType: Video.Type = currentVideoTypeForUi()): String {
        if (isOfflinePlayback()) {
            val base = subtitleBase(videoType)
            val offlineLabel = getString(R.string.downloads_play_offline)
            return when {
                base.isBlank() -> offlineLabel
                base.contains(offlineLabel, ignoreCase = true) -> base
                else -> "$base · $offlineLabel"
            }
        }
        if (isLiveTvPlayback()) {
            return getString(R.string.player_live_badge)
        }
        return subtitleBase(videoType)
    }

    private fun subtitleBase(videoType: Video.Type): String = when (videoType) {
        is Video.Type.Movie -> args.subtitle
        is Video.Type.Episode -> {
            val episodeTitle = videoType.title?.takeUnless { it.isBlank() } ?: args.subtitle
            "S${videoType.season.number} E${videoType.number}  •  $episodeTitle"
        }
    }

    private fun updatePlayerHeader(videoType: Video.Type = currentVideoTypeForUi()) {
        val controller = binding.pvPlayer.controllerOrNull()?.binding ?: return
        val title = resolvePlayerTitle(videoType)
        val subtitle = resolvePlayerSubtitle(videoType)
        val titleChanged = controller.tvExoTitle.text?.toString() != title
        val subtitleChanged = controller.tvExoSubtitle.text?.toString() != subtitle
        controller.tvExoTitle.text = title
        controller.tvExoSubtitle.text = subtitle
        bindPlayerTitleLogo(controller.root, controller.ivExoLogo, controller.tvExoTitle, videoType)
        refreshEpisodeNoteChip(videoType)
        // Offline status lives in the subtitle (not the LIVE red pill on the time row).
        if (ExperimentalMobileDesign.enabled() && (titleChanged || subtitleChanged)) {
            // Soft crossfade on episode/channel swaps; full reveal only first time.
            val first = controller.tvExoTitle.getTag(R.id.exp_enter_animated_tag) != true
            if (first) {
                controller.tvExoTitle.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(controller.tvExoTitle, controller.tvExoSubtitle)
            } else {
                controller.tvExoTitle.animate().cancel()
                controller.tvExoSubtitle.animate().cancel()
                controller.tvExoTitle.alpha = 0.55f
                controller.tvExoSubtitle.alpha = 0.55f
                controller.tvExoTitle.animate().alpha(1f).setDuration(180L).start()
                controller.tvExoSubtitle.animate().alpha(1f).setDuration(180L).start()
            }
        }
    }

    private fun refreshEpisodeNoteChip(videoType: Video.Type = currentVideoTypeForUi()) {
        val chip = _binding?.tvEpisodeNoteChip ?: return
        val episodeType = videoType as? Video.Type.Episode
        if (episodeType == null || isLiveTvPlayback()) {
            chip.isGone = true
            chip.setOnClickListener(null)
            return
        }
        val note = EpisodeNotesStore.get(requireContext(), episodeType.id)
        chip.isVisible = true
        chip.text = if (note.isNullOrBlank()) {
            getString(R.string.episode_note_title)
        } else {
            getString(R.string.player_episode_note_label, EpisodeNotesStore.preview(note))
        }
        chip.setOnClickListener {
            ExpMotion.hapticTap(it)
            EpisodeNotesUi.showEditor(requireContext(), episodeFromVideoType(episodeType)) {
                refreshEpisodeNoteChip(episodeType)
            }
        }
    }

    private fun episodeFromVideoType(type: Video.Type.Episode): Episode =
        Episode(
            id = type.id,
            number = type.number,
            title = type.title,
            poster = type.poster,
            overview = type.overview,
            tvShow = TvShow(
                id = type.tvShow.id,
                title = type.tvShow.title,
                poster = type.tvShow.poster,
                banner = type.tvShow.banner,
            ),
            season = Season(
                number = type.season.number,
                title = type.season.title,
            ),
        )

    private fun bindPlayerTitleLogo(
        anchor: android.view.View,
        logoView: android.widget.ImageView,
        titleView: android.widget.TextView,
        videoType: Video.Type,
    ) {
        val title = resolvePlayerTitle(videoType)
        // Live TV channel names are not TMDb titles — keep plain text, skip resolve.
        if (isLiveTvPlayback()) {
            com.dskja.betterstreamflix.logo.TitleLogoSurface.bindCachedOnly(
                imageView = logoView,
                titleView = titleView,
                logoUrl = null,
                title = title,
            )
            anchor.setTag(R.id.player_logo_resolved_url_tag, null)
            return
        }
        val cachedUrl = (anchor.getTag(R.id.player_logo_resolved_url_tag) as? String)
            ?.takeIf {
                anchor.getTag(R.id.title_logo_expected_title_tag) == title && it.isNotBlank()
            }
        val onResolved: (String, com.dskja.betterstreamflix.logo.LogoSource) -> Unit = { url, _ ->
            anchor.setTag(R.id.player_logo_resolved_url_tag, url)
        }
        when (videoType) {
            is Video.Type.Movie -> com.dskja.betterstreamflix.logo.TitleLogoSurface.resolveAndBind(
                anchor = anchor,
                imageView = logoView,
                titleView = titleView,
                title = title,
                year = videoType.releaseDate.take(4).toIntOrNull(),
                isTv = false,
                imdbId = videoType.imdbId,
                existingLogo = cachedUrl,
                language = com.dskja.betterstreamflix.utils.UserPreferences.currentProvider?.language,
                onResolved = onResolved,
            )
            is Video.Type.Episode -> com.dskja.betterstreamflix.logo.TitleLogoSurface.resolveAndBind(
                anchor = anchor,
                imageView = logoView,
                titleView = titleView,
                title = title,
                year = videoType.tvShow.releaseDate?.take(4)?.toIntOrNull(),
                isTv = true,
                imdbId = videoType.tvShow.imdbId,
                existingLogo = cachedUrl,
                language = com.dskja.betterstreamflix.utils.UserPreferences.currentProvider?.language,
                onResolved = onResolved,
            )
        }
    }

    private fun queueNextEpisodeForContinueWatching(provider: com.dskja.betterstreamflix.providers.Provider) {
        val nextEpisode = EpisodeManager.peekNextEpisode() ?: return
        val episodeDao = database?.episodeDao() ?: return
        // Do not stamp WatchHistory/engagement on the unwatched successor — that forces it
        // into Continue Watching and blocks EpisodeDao.getNextEpisodesToWatch (NOT EXISTS
        // lastEngagementTimeUtcMillis). Keep metadata only so Next Episodes can own binge.
        val existing = episodeDao.getById(nextEpisode.id)
        if (existing != null) {
            val hist = existing.watchHistory
            val fakeQueueEngagement = hist != null &&
                hist.lastPlaybackPositionMillis == 0L &&
                hist.durationMillis == 0L
            if (fakeQueueEngagement || existing.isWatched) {
                existing.isWatched = false
                existing.watchedDate = null
                if (fakeQueueEngagement) existing.watchHistory = null
                episodeDao.save(existing)
                UserDataCache.syncEpisodeToCache(requireContext(), provider, existing)
            }
            return
        }
        val persistedNextEpisode = Episode(
            id = nextEpisode.id,
            number = nextEpisode.number,
            title = nextEpisode.title,
            poster = nextEpisode.poster,
            overview = nextEpisode.overview,
            tvShow = database?.tvShowDao()?.getById(nextEpisode.tvShow.id) ?: TvShow(
                id = nextEpisode.tvShow.id,
                title = nextEpisode.tvShow.title,
                poster = nextEpisode.tvShow.poster,
                banner = nextEpisode.tvShow.banner,
            ),
            season = Season(
                number = nextEpisode.season.number,
                title = nextEpisode.season.title,
            ),
        ).apply {
            isWatched = false
            watchedDate = null
        }
        episodeDao.save(persistedNextEpisode)
        UserDataCache.syncEpisodeToCache(requireContext(), provider, persistedNextEpisode)
    }
    private fun subtitleOffsetKey(): String = when (val t = args.videoType) {
        is Video.Type.Movie -> "movie:${t.id}"
        is Video.Type.Episode -> "episode:${t.id}"
    }

    private fun startProgressHandler() {
        stopProgressHandler()
        if (isTearingDown || playerReleased || !::player.isInitialized) return
        progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        progressRunnable = Runnable {
            if (isTearingDown || !isAdded || _binding == null) return@Runnable
            if (playerReleased || !::player.isInitialized) return@Runnable
            val active = runCatching { activePlayer() }.getOrNull() ?: return@Runnable
            tickSmartMuteAds(active)
            if (active.isPlaying) {
                if (!isLiveTvPlayback()) {
                    val pos = active.currentPosition
                    val dur = active.duration
                    skipIntroProbe?.onPlaybackPosition(pos)
                    updateSmartSkipChrome(pos, dur)
                    updateNextEpisodeOverlay()
                    reportTraktProgress(
                        videoType = args.videoType,
                        positionMs = pos,
                        durationMs = dur,
                        isPlaying = true,
                    )
                } else {
                    activeSkipOpportunity = null
                    showSkipIntroButton(false)
                    hideNextEpisodeOverlay()
                }
            }
            if (isAdded && _binding != null) {
                progressHandler.postDelayed(progressRunnable, 1000)
            }
        }
        progressHandler.post(progressRunnable)
    }

    /** Stuck pre-/mid-roll rescue — local ExoPlayer only, never Cast/live/offline. */
    private fun tickSmartMuteAds(active: androidx.media3.common.Player) {
        if (isCasting || CastPlaybackHub.isCasting) return
        if (active !is ExoPlayer) return
        val isPlayingAd = runCatching {
            active.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) && active.isPlayingAd
        }.getOrDefault(false)
        val action = smartMuteAdsWatchdog.onTick(
            SmartMuteAds.Snapshot(
                nowMs = System.currentTimeMillis(),
                positionMs = active.currentPosition.coerceAtLeast(0L),
                bufferedPositionMs = active.bufferedPosition.coerceAtLeast(0L),
                durationMs = active.duration,
                phase = SmartMuteAds.phaseFromMedia3(active.playbackState),
                isPlaying = active.isPlaying,
                playWhenReady = active.playWhenReady,
                isPlayingAd = isPlayingAd,
                isLive = isLiveTvPlayback(),
                isOffline = isOfflinePlayback(),
            ),
        ) ?: return
        runCatching {
            active.seekTo(action.positionMs)
            active.playWhenReady = true
        }
        showPlayerCue(R.string.player_smart_mute_ads_skipped)
    }

    private fun reportTraktProgress(
        videoType: Video.Type,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        com.dskja.betterstreamflix.platform.player.PlayerPlaybackReporter.report(
            videoType = videoType,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            provider = UserPreferences.currentProvider,
            itemId = when (videoType) {
                is Video.Type.Movie -> videoType.id
                is Video.Type.Episode -> videoType.id
            },
        )
    }

    private fun stopProgressHandler() {
        if (::progressHandler.isInitialized && ::progressRunnable.isInitialized) {
            progressHandler.removeCallbacks(progressRunnable)
        }
    }

    private fun updateNextEpisodeOverlay() {
        val currentEpisode = currentVideoTypeForUi() as? Video.Type.Episode ?: run {
            hideNextEpisodeOverlay()
            return
        }
        val active = runCatching { activePlayer() }.getOrNull() ?: player
        val duration = active.duration
        if (!com.dskja.betterstreamflix.download.DownloadWatchPolicy.isKnownTime(duration) ||
            duration < 60_000L
        ) {
            hideNextEpisodeOverlay()
            return
        }
        val remainingMs = (duration - active.currentPosition).coerceAtLeast(0L)

        if (nextEpisodeOverlayDismissed) {
            hideNextEpisodeOverlay()
            return
        }

        if (remainingMs <= NEXT_EPISODE_PREFETCH_THRESHOLD_MS) {
            ensureNextEpisodePrepared(currentEpisode)
            if (isCasting || CastPlaybackHub.isCasting) {
                scheduleCastNextEpisodeQueue()
            }
        }

        val nextEpisode = EpisodeManager.peekNextEpisode()
        val overlayThresholdMs = maxOf(
            NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS,
            UserPreferences.autoplayBuffer * 1000L
        )
        if (nextEpisode == null || remainingMs == 0L || remainingMs > overlayThresholdMs) {
            hideNextEpisodeOverlay()
            return
        }

        showNextEpisodeOverlay(nextEpisode, remainingMs)
    }

    private fun ensureNextEpisodePrepared(currentEpisode: Video.Type.Episode) {
        if (EpisodeManager.peekNextEpisode() != null) return
        if (nextEpisodePrefetchTargetId == currentEpisode.id && nextEpisodePrefetchJob?.isActive == true) {
            return
        }

        nextEpisodePrefetchTargetId = currentEpisode.id
        nextEpisodePrefetchJob?.cancel()
        nextEpisodePrefetchJob = lifecycleScope.launch(Dispatchers.IO) {
            val loaded = database?.let { EpisodeManager.ensureNextEpisodeAvailable(currentEpisode, it) }
            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                setupEpisodeNavigationButtons()
                if (loaded == true && player.isPlaying) {
                    updateNextEpisodeOverlay()
                }
            }
        }
    }

    private fun showNextEpisodeOverlay(nextEpisode: Video.Type.Episode, remainingMs: Long) {
        binding.tvNextEpisodeMeta.text = getString(
            R.string.tv_show_item_season_number_episode_number,
            nextEpisode.season.number,
            nextEpisode.number
        )
        binding.tvNextEpisodeTitle.text = nextEpisode.title
            ?: getString(R.string.episode_number, nextEpisode.number)
        binding.tvNextEpisodeCountdown.text = if (UserPreferences.autoplay) {
            getString(
                R.string.player_next_episode_autoplay_in,
                ((remainingMs + 999L) / 1000L).toInt()
            )
        } else {
            getString(R.string.player_next_episode_ready)
        }

        Glide.with(this)
            .load(nextEpisode.poster ?: nextEpisode.tvShow.poster)
            .placeholder(R.drawable.glide_fallback_cover)
            .error(R.drawable.glide_fallback_cover)
            .fallback(R.drawable.glide_fallback_cover)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivNextEpisodePoster)

        if (binding.layoutNextEpisodeOverlay.isGone) {
            if (ExperimentalMobileDesign.enabled()) {
                binding.layoutNextEpisodeOverlay.setBackgroundResource(
                    ExperimentalMobileDesign.glassCardBackground(),
                )
                binding.tvNextEpisodeMeta.setBackgroundResource(
                    ExperimentalMobileDesign.metaPillBackground(),
                )
                binding.tvNextEpisodeCountdown.setBackgroundResource(
                    ExperimentalMobileDesign.metaPillBackground(),
                )
                binding.layoutNextEpisodeOverlay.isVisible = true
                ExpMotion.revealHeader(
                    binding.tvNextEpisodeLabel,
                    binding.root.findViewById(R.id.v_next_episode_rule),
                    binding.tvNextEpisodeMeta,
                    binding.tvNextEpisodeTitle,
                    binding.tvNextEpisodeCountdown,
                )
                ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_next_episode_rule))
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                    binding.btnNextEpisodeAction.applyExpPress()
                    binding.btnNextEpisodeDismiss.applyExpPress()
                }
                binding.btnNextEpisodeAction.setBackgroundResource(
                    ExperimentalMobileDesign.primaryButtonBackground(),
                )
                binding.btnNextEpisodeDismiss.setBackgroundResource(
                    ExperimentalMobileDesign.chipBackground(),
                )
                listOf(
                    binding.ivNextEpisodePoster,
                    binding.btnNextEpisodeAction,
                    binding.btnNextEpisodeDismiss,
                ).forEachIndexed { index, view ->
                    view.alpha = 0f
                    view.postDelayed({ ExpMotion.popIn(view) }, 28L * index)
                }
            } else {
                val fadeIn = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in)
                binding.layoutNextEpisodeOverlay.startAnimation(fadeIn)
                binding.layoutNextEpisodeOverlay.isVisible = true
            }
        }
    }

    private fun hideNextEpisodeOverlay() {
        val b = _binding ?: return
        if (b.layoutNextEpisodeOverlay.isVisible) {
            if (ExperimentalMobileDesign.enabled()) {
                ExpMotion.fadeOutAndHide(b.layoutNextEpisodeOverlay)
            } else {
                val ctx = context ?: return
                val fadeOut = android.view.animation.AnimationUtils.loadAnimation(ctx, R.anim.fade_out)
                b.layoutNextEpisodeOverlay.startAnimation(fadeOut)
                b.layoutNextEpisodeOverlay.isGone = true
            }
        }
    }

    private fun wireEndCardChooserButtons() {
        val card = _binding?.layoutEndCardChooser ?: return
        card.btnEndCardNext.setOnClickListener {
            ExpMotion.hapticTap(it)
            endCardCountdownCancelled = true
            endCardCountdownJob?.cancel()
            hideEndCardChooser()
            playNextEpisodeAcrossSeasons()
        }
        card.btnEndCardSimilar.setOnClickListener {
            ExpMotion.hapticTap(it)
            navigateEndCardToDetail(openSimilar = true)
        }
        card.btnEndCardBack.setOnClickListener {
            ExpMotion.hapticTap(it)
            navigateEndCardToDetail(openSimilar = false)
        }
    }

    private fun maybeShowEndCardChooser() {
        if (!isAdded || _binding == null || isTearingDown) return
        if (!EndCardPolicy.shouldShowEndCard(
                reallyFinished = true,
                stateEnded = true,
                isLive = isLiveTvPlayback(),
                isCasting = isCasting || CastPlaybackHub.isCasting,
                resolveInFlight = viewModel.isVideoResolveInFlight(),
            )
        ) {
            // Cast / live: keep legacy autoplay hop when enabled.
            if (!isLiveTvPlayback() &&
                (isCasting || CastPlaybackHub.isCasting) &&
                UserPreferences.autoplay &&
                !viewModel.isVideoResolveInFlight()
            ) {
                playNextEpisodeAcrossSeasons(autoplay = true)
            }
            return
        }
        if (endCardVisible) return
        // Prefetch next so the Next button can stay enabled.
        (currentVideoTypeForUi() as? Video.Type.Episode)?.let { ensureNextEpisodePrepared(it) }
        showEndCardChooser()
    }

    private fun showEndCardChooser() {
        val b = _binding ?: return
        val card = b.layoutEndCardChooser
        val root = card.root
        hideNextEpisodeOverlay()
        val episode = currentVideoTypeForUi() as? Video.Type.Episode
        val movie = currentVideoTypeForUi() as? Video.Type.Movie
        val hasNext = episode != null && EpisodeManager.peekNextEpisode() != null
        card.tvEndCardTitle.setText(R.string.player_end_card_title)
        card.tvEndCardSubtitle.text = when {
            episode != null -> {
                val meta = getString(
                    R.string.tv_show_item_season_number_episode_number,
                    episode.season.number,
                    episode.number,
                )
                val title = episode.title ?: getString(R.string.episode_number, episode.number)
                "$meta · $title"
            }
            movie != null -> movie.title
            else -> ""
        }
        card.btnEndCardNext.isVisible = hasNext
        card.btnEndCardNext.text = getString(R.string.player_end_card_next)
        card.btnEndCardSimilar.setText(R.string.player_end_card_similar)
        card.btnEndCardBack.setText(
            if (episode != null) R.string.player_end_card_back_to_series
            else R.string.player_end_card_back_to_title,
        )
        endCardCountdownCancelled = false
        endCardCountdownJob?.cancel()
        val shouldCountdown = EndCardPolicy.shouldAutoplayAfterCountdown(
            autoplayEnabled = UserPreferences.autoplay,
            hasNextEpisode = hasNext,
            countdownCancelled = false,
            bedtimeBlocks = KidsBedtime.shouldBlockAutoplay(),
        )
        if (shouldCountdown) {
            card.tvEndCardCountdown.isVisible = true
            endCardCountdownJob = viewLifecycleOwner.lifecycleScope.launch {
                for (sec in EndCardPolicy.AUTOPLAY_COUNTDOWN_SEC downTo 1) {
                    if (endCardCountdownCancelled || !isAdded || _binding == null) return@launch
                    card.tvEndCardCountdown.text = getString(
                        R.string.player_end_card_autoplay_in,
                        EndCardPolicy.countdownLabelSec(sec),
                    )
                    delay(1_000L)
                }
                if (!endCardCountdownCancelled && isAdded && _binding != null && endCardVisible) {
                    hideEndCardChooser()
                    playNextEpisodeAcrossSeasons(autoplay = true)
                }
            }
        } else {
            card.tvEndCardCountdown.isVisible = false
        }
        bindThanksAfterBingePanel(card, episode)
        if (root.isGone) {
            val fadeIn = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in)
            root.startAnimation(fadeIn)
            root.isVisible = true
        }
        endCardVisible = true
        // Hide chrome so the chooser owns the screen.
        runCatching { b.pvPlayer.hideController() }
        (if (hasNext) card.btnEndCardNext else card.btnEndCardSimilar).requestFocus()
    }

    private fun bindThanksAfterBingePanel(
        card: com.dskja.betterstreamflix.databinding.LayoutPlayerEndCardBinding,
        episode: Video.Type.Episode?,
    ) {
        ThanksAfterBingeBinder.bind(
            panel = ThanksAfterBingeBinder.Panel(
                root = card.endCardThanksPanel,
                title = card.tvEndCardThanksTitle,
                body = card.tvEndCardThanksBody,
                cta = card.btnEndCardThanksCta,
                dismiss = card.btnEndCardThanksDismiss,
            ),
            videoType = episode,
            onOpenSupport = {
                endCardCountdownCancelled = true
                endCardCountdownJob?.cancel()
                hideEndCardChooser()
                if (!isAdded) return@bind
                forceStopPlayback()
                runCatching {
                    findNavController().navigate(
                        R.id.support,
                        null,
                        NavOptions.Builder().setPopUpTo(R.id.player, true).build(),
                    )
                }
            },
        )
    }

    private fun hideEndCardChooser() {
        endCardCountdownJob?.cancel()
        endCardCountdownJob = null
        endCardCountdownCancelled = true
        endCardVisible = false
        val card = _binding?.layoutEndCardChooser
        ThanksAfterBingeBinder.hide(
            card?.let {
                ThanksAfterBingeBinder.Panel(
                    root = it.endCardThanksPanel,
                    title = it.tvEndCardThanksTitle,
                    body = it.tvEndCardThanksBody,
                    cta = it.btnEndCardThanksCta,
                    dismiss = it.btnEndCardThanksDismiss,
                )
            },
        )
        val root = card?.root ?: return
        if (root.isVisible) {
            root.isGone = true
        }
    }

    private fun navigateEndCardToDetail(openSimilar: Boolean) {
        endCardCountdownCancelled = true
        endCardCountdownJob?.cancel()
        hideEndCardChooser()
        if (!isAdded) return
        forceStopPlayback()
        val openTab = if (openSimilar) "similar" else null
        val options = NavOptions.Builder()
            .setPopUpTo(R.id.player, true)
            .build()
        when (val type = currentVideoTypeForUi()) {
            is Video.Type.Episode -> {
                runCatching {
                    findNavController().navigate(
                        R.id.action_global_tv_show,
                        bundleOf(
                            "id" to type.tvShow.id,
                            "poster" to type.tvShow.poster,
                            "banner" to type.tvShow.banner,
                            "openTab" to openTab,
                        ),
                        options,
                    )
                }.onFailure { safeNavigateUp() }
            }
            is Video.Type.Movie -> {
                runCatching {
                    findNavController().navigate(
                        R.id.action_global_movie,
                        bundleOf(
                            "id" to type.id,
                            "openTab" to openTab,
                        ),
                        options,
                    )
                }.onFailure { safeNavigateUp() }
            }
        }
    }

    private fun showFailoverServerCue(nextIndex: Int, serverCount: Int) {
        showPlayerCue(
            getString(
                R.string.player_failover_server_n_of_m,
                PlaybackFailover.serverOrdinal(nextIndex),
                serverCount,
            ),
        )
    }

    private fun configureStuckDetector() {
        val enabled = !isLiveTvPlayback() && !isOfflinePlayback() && servers.size >= 2
        stuckDetector.setEnabled(enabled)
        stuckDetector.setResolveInFlight(viewModel.isVideoResolveInFlight())
        if (enabled) startStuckWatch() else stopStuckWatch()
    }

    private fun startStuckWatch() {
        if (stuckRunnable != null) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        stuckHandler = handler
        val tick = object : Runnable {
            override fun run() {
                if (!mayRunPlayerListenerWork()) {
                    stuckHandler?.postDelayed(this, StuckBufferDetector.POLL_INTERVAL_MS)
                    return
                }
                stuckDetector.setResolveInFlight(viewModel.isVideoResolveInFlight())
                if (::player.isInitialized && !playerReleased) {
                    stuckDetector.onPlaybackStateChanged(player.playbackState, player.playWhenReady)
                    if (player.isPlaying) {
                        stuckDetector.onIsPlayingChanged(true)
                    }
                }
                if (stuckDetector.pollShouldOffer(servers.size)) {
                    stuckDetector.markOffered()
                    showStuckBufferOffer()
                }
                stuckHandler?.postDelayed(this, StuckBufferDetector.POLL_INTERVAL_MS)
            }
        }
        stuckRunnable = tick
        handler.postDelayed(tick, StuckBufferDetector.POLL_INTERVAL_MS)
    }

    private fun stopStuckWatch() {
        stuckRunnable?.let { stuckHandler?.removeCallbacks(it) }
        stuckRunnable = null
        stuckHandler = null
    }

    private fun hideStuckOverlay() {
        if (!stuckDetector.isOverlayVisible()) return
        _binding?.root?.findViewById<View>(R.id.ll_player_error)?.isGone = true
        stuckDetector.dismissOverlay()
    }

    private fun showStuckBufferOffer() {
        val root = _binding?.root ?: return
        if (isTearingDown || !isAdded || servers.size < 2) return
        val overlay = root.findViewById<View>(R.id.ll_player_error) ?: return
        stuckDetector.setOverlayVisible(true)
        root.findViewById<android.widget.TextView>(R.id.tv_player_error_title)
            ?.setText(R.string.player_stuck_buffering_title)
        root.findViewById<android.widget.TextView>(R.id.tv_player_error_message)
            ?.setText(R.string.player_stuck_buffering_message)
        FailoverTimelineUi.clearErrorTimeline(root)
        val actionBtn = root.findViewById<android.widget.TextView>(R.id.btn_player_error_action)
        actionBtn?.isVisible = true
        actionBtn?.setText(R.string.player_stuck_try_another_server)
        if (ExperimentalMobileDesign.enabled()) {
            actionBtn?.let {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { it.applyExpPress() }
            }
        }
        actionBtn?.setOnClickListener {
            ExpMotion.hapticTap(it)
            tryStuckServerHop()
        }
        root.findViewById<View>(R.id.btn_player_error_close)?.let { close ->
            if (close is android.widget.TextView) {
                close.setText(R.string.player_stuck_pick_server)
            }
            if (ExperimentalMobileDesign.enabled()) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { close.applyExpPress() }
            }
            close.setOnClickListener {
                ExpMotion.hapticTap(it)
                hideStuckOverlay()
                openServerPickerFromStuck()
            }
        }
        if (ExperimentalMobileDesign.enabled()) {
            overlay.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
            ExpMotion.fadeInAndShow(overlay)
        } else {
            overlay.isVisible = true
        }
    }

    /** @return true when a next hoster hop was started. */
    private fun tryStuckServerHop(): Boolean {
        if (viewModel.isVideoResolveInFlight()) return false
        val action = PlaybackFailover.decide(
            currentServerIndex = PlaybackFailover.indexOfServer(servers, currentServer),
            serverCount = servers.size,
            playbackAlreadyStarted = ::player.isInitialized && player.hasStarted(),
            softwareDecoderAlreadyEnabled = currentSoftwareDecoder,
            allowMidPlaybackFailover = true,
        )
        val nextIndex = (action as? PlaybackFailover.Action.TryNextServer)?.nextIndex
            ?: return false
        val next = servers.getOrNull(nextIndex) ?: return false
        hideStuckOverlay()
        com.dskja.betterstreamflix.providers.ProviderServerAb.notePlaybackUnstable(currentServer)
        stuckDetector.onServerChanged()
        Log.i("PlayerMobileFragment", "Stuck buffering — trying next server: ${next.name}")
        showFailoverServerCue(nextIndex, servers.size)
        viewModel.getVideo(next)
        return true
    }

    private fun openServerPickerFromStuck() {
        if (isTearingDown || !isAdded || _binding == null || servers.isEmpty()) return
        keepControllerVisibleForSettings()
        binding.settings.showServers()
    }

    private fun maybeShowFailoverTimeline() {
        if (!failoverTimeline.consumeSuccessShow()) return
        val line = FailoverTimelineUi.formatLine(requireContext(), failoverTimeline.snapshot())
            ?: return
        showPlayerCue(line, holdMs = FailoverTimelineUi.SUCCESS_CUE_MS)
    }

    private fun showStreamFailure(
        error: Throwable?,
        serversExhausted: Boolean = false,
        fallbackMessage: String? = null,
    ) {
        val presentation = StreamFailureReason.classify(
            error = error,
            provider = UserPreferences.currentProvider,
            serversExhausted = serversExhausted,
        )
        val message = StreamFailureReason.message(requireContext(), presentation, fallbackMessage)
        showPlayerError(
            message = message,
            titleRes = presentation.titleRes,
            action = presentation.action,
            actionLabelRes = presentation.actionLabelRes,
        )
    }

    private fun showPlayerError(
        message: String,
        titleRes: Int = R.string.exp_player_error_title,
        action: StreamFailureReason.Action = StreamFailureReason.Action.NONE,
        actionLabelRes: Int? = null,
    ) {
        val root = _binding?.root ?: return
        val overlay = root.findViewById<View>(R.id.ll_player_error)
        if (overlay == null) {
            notifyPlayer(message)
            // Keep the user on the player chrome path when possible — toast only.
            return
        }
        if (isOfflinePlayback()) suppressBuiltInErrorOverlay()
        root.findViewById<android.widget.TextView>(R.id.tv_player_error_title)?.setText(titleRes)
        FailoverTimelineUi.bindErrorTimeline(root, failoverTimeline.snapshot())
        root.findViewById<android.widget.TextView>(R.id.tv_player_error_message)?.text = message
        val actionBtn = root.findViewById<android.widget.TextView>(R.id.btn_player_error_action)
        if (action != StreamFailureReason.Action.NONE && actionLabelRes != null) {
            actionBtn?.isVisible = true
            actionBtn?.setText(actionLabelRes)
            if (ExperimentalMobileDesign.enabled()) {
                actionBtn?.let {
                    with(com.dskja.betterstreamflix.utils.ExpPressEffects) { it.applyExpPress() }
                }
            }
            actionBtn?.setOnClickListener {
                ExpMotion.hapticTap(it)
                overlay.isGone = true
                when (action) {
                    StreamFailureReason.Action.SOLVE_CAPTCHA -> launchSolveCaptcha()
                    StreamFailureReason.Action.UPDATE_DOMAIN -> launchUpdateDomain()
                    StreamFailureReason.Action.OPEN_SETTINGS -> launchVpnSettings()
                    StreamFailureReason.Action.NONE -> Unit
                }
            }
        } else {
            actionBtn?.isGone = true
            actionBtn?.setOnClickListener(null)
        }
        root.findViewById<View>(R.id.btn_player_error_close)?.let { close ->
            if (ExperimentalMobileDesign.enabled()) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { close.applyExpPress() }
            }
            close.setOnClickListener {
                ExpMotion.hapticTap(it)
                val openServers = {
                    if (!isTearingDown && isAdded && _binding != null && servers.isNotEmpty()) {
                        keepControllerVisibleForSettings()
                        binding.settings.showServers()
                    }
                }
                if (ExperimentalMobileDesign.enabled()) {
                    ExpMotion.fadeOutAndHide(overlay)
                    overlay.postDelayed({ openServers() }, 220L)
                } else {
                    overlay.isGone = true
                    openServers()
                }
            }
        }
        if (ExperimentalMobileDesign.enabled()) {
            overlay.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
            ExpMotion.fadeInAndShow(overlay)
            ExpMotion.revealHeader(
                root.findViewById(R.id.tv_player_error_title),
                root.findViewById(R.id.tv_player_error_message),
            )
            root.findViewById<View>(R.id.btn_player_error_close)?.let { close ->
                close.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                ExpMotion.popIn(close)
            }
            actionBtn?.let {
                it.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                ExpMotion.popIn(it)
            }
        } else {
            overlay.isVisible = true
        }
    }

    private fun launchSolveCaptcha() {
        if (!StreamFailureReason.supportsCaptchaSolve(UserPreferences.currentProvider) &&
            buildSerienStreamBypassUrl(servers).isNullOrBlank()
        ) {
            Toast.makeText(
                requireContext(),
                R.string.player_bypass_retry_needed_mobile,
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val bypassUrl = buildSerienStreamBypassUrl(servers)
        if (!bypassUrl.isNullOrBlank()) {
            waitingForBypass = true
            bypassDone = false
            SerienStreamBypassHelper.applyStoredSessionCookies(bypassUrl)
            SerienStreamAuthManager.seedRuntimeCookies()
            bypassWebViewLauncher.launch(
                Intent(requireContext(), BypassWebViewActivity::class.java)
                    .putExtra(BypassWebViewActivity.EXTRA_URL, bypassUrl),
            )
            return
        }
        Toast.makeText(
            requireContext(),
            R.string.player_bypass_retry_needed_mobile,
            Toast.LENGTH_LONG,
        ).show()
        viewModel.reloadServersAfterBypass()
    }

    private fun launchVpnSettings() {
        val intents = listOf(
            Intent("android.net.vpn.SETTINGS"),
            Intent(android.provider.Settings.ACTION_VPN_SETTINGS),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(requireContext().packageManager) != null) {
                runCatching { startActivity(intent) }
                return
            }
        }
        Toast.makeText(
            requireContext(),
            R.string.player_stream_fail_geo_message,
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun launchUpdateDomain() {
        val provider = StreamFailureReason.domainUpdateTarget(UserPreferences.currentProvider)
            ?: return
        lifecycleScope.launch {
            val outcome = ProviderDomainUpdater.update(provider)
            val msg = when (outcome) {
                is ProviderDomainUpdater.Outcome.Updated ->
                    getString(R.string.provider_update_domain_updated, outcome.newUrl)
                ProviderDomainUpdater.Outcome.Unchanged ->
                    getString(R.string.provider_update_domain_unchanged)
                is ProviderDomainUpdater.Outcome.Failed ->
                    getString(R.string.provider_update_domain_failed, outcome.message)
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            if (outcome is ProviderDomainUpdater.Outcome.Updated) {
                viewModel.reloadServersAfterBypass()
            }
        }
    }

    private fun wirePlayWithChromeButton(controller: ContentExoControllerMobileBinding) {
        com.dskja.betterstreamflix.sync.WatchPartyPlayerHooks.wireChromeButton(
            this,
            controller.btnExoWatchParty,
            watchPartyBridge,
        )
        com.dskja.betterstreamflix.sync.CastRoomPlayerHooks.wireChromeButton(
            this,
            controller.btnExoCastRoom,
        )
        controller.btnExoExternalPlayer.setOnClickListener {
            ExpMotion.hapticTap(it)
            openCurrentStreamExternally(forceChooser = false)
        }
        controller.btnExoExternalPlayer.setOnLongClickListener {
            ExpMotion.hapticTap(it)
            openCurrentStreamWithDownloader()
            true
        }
        val downloadHint = getString(R.string.player_download_with_long_press)
        controller.btnExoExternalPlayer.contentDescription =
            getString(R.string.exp_cd_external_player) + ". " + downloadHint
        androidx.appcompat.widget.TooltipCompat.setTooltipText(
            controller.btnExoExternalPlayer,
            downloadHint,
        )
        // One-shot toast so ADM/1DM long-press is discoverable without Settings.
        if (!UserPreferences.downloadWithLongPressHintShown) {
            UserPreferences.downloadWithLongPressHintShown = true
            Toast.makeText(requireContext(), downloadHint, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateCastQueueChrome() {
        val binding = _binding ?: return
        val badge = binding.pvPlayer.controller.binding.tvCastQueue
        val casting = isCasting || CastPlaybackHub.isCasting
        val count = CastPlaybackHub.queuedCount()
        if (!casting || count <= 0) {
            badge.isGone = true
            badge.setOnClickListener(null)
            badge.isClickable = false
            return
        }
        val nextTitle = CastPlaybackHub.peekNextQueued()
            ?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
        badge.text = if (nextTitle != null) {
            getString(R.string.player_cast_queue_next, nextTitle)
        } else {
            getString(R.string.player_cast_queue_badge, count)
        }
        badge.contentDescription = getString(R.string.player_cast_queue_clear_cd)
        badge.isClickable = true
        badge.isFocusable = true
        badge.setOnClickListener {
            ExpMotion.hapticTap(it)
            CastPlaybackHub.clearQueue()
            updateCastQueueChrome()
        }
        badge.isVisible = true
    }

    private fun contentIdForSmartSkip(): String = when (val t = args.videoType) {
        is Video.Type.Movie -> "movie:${t.id}"
        is Video.Type.Episode -> "episode:${t.id}"
    }

    private fun resetSmartSkipForContent() {
        val id = contentIdForSmartSkip()
        if (id == skipIntroContentId) return
        skipIntroContentId = id
        skipIntroConsumed = false
        skipIntroChapters = emptyList()
        activeSkipOpportunity = null
        skipIntroProbe?.release()
        skipIntroProbe = null
        showSkipIntroButton(false)
    }

    private fun ensureSkipIntroProbe() {
        if (skipIntroProbe != null || skipIntroConsumed || isLiveTvPlayback()) return
        if (!::player.isInitialized || playerReleased) return
        val sessionId = runCatching { player.audioSessionId }.getOrDefault(C.AUDIO_SESSION_ID_UNSET)
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return
        skipIntroProbe = SkipIntroSilenceProbe.tryCreate(sessionId)
    }

    private fun releaseSkipIntroProbe() {
        skipIntroProbe?.release()
        skipIntroProbe = null
        activeSkipOpportunity = null
    }

    private fun updateSmartSkipChrome(positionMs: Long, durationMs: Long) {
        val episode = args.videoType as? Video.Type.Episode
        val opportunity = SmartSkipIntro.evaluate(
            positionMs = positionMs,
            durationMs = durationMs,
            isEpisode = episode != null,
            episodeNumber = episode?.number,
            chapters = skipIntroChapters,
            silenceGaps = skipIntroProbe?.gaps().orEmpty(),
            alreadyConsumed = skipIntroConsumed,
        )
        activeSkipOpportunity = opportunity
        val btn = _binding?.pvPlayer?.controller?.binding?.btnSkipIntro
        if (opportunity != null && btn != null) {
            val label = when (opportunity.kind) {
                SmartSkipIntro.Kind.INTRO -> getString(R.string.player_skip_intro)
                SmartSkipIntro.Kind.RECAP -> getString(R.string.player_skip_recap)
            }
            if (btn.text?.toString() != label) {
                btn.text = label
            }
        }
        showSkipIntroButton(opportunity != null)
    }

    private fun showSkipIntroButton(show: Boolean) {
        val b = _binding ?: return
        val btnSkipIntro = b.pvPlayer.controller.binding.btnSkipIntro
        if (show && btnSkipIntro.isGone) {
            btnSkipIntro.isVisible = true
            if (ExperimentalMobileDesign.enabled()) {
                btnSkipIntro.setBackgroundResource(ExperimentalMobileDesign.controlsPillBackground())
                ExpMotion.popIn(btnSkipIntro)
            } else {
                val ctx = context ?: return
                val fadeIn = android.view.animation.AnimationUtils.loadAnimation(ctx, R.anim.fade_in)
                btnSkipIntro.startAnimation(fadeIn)
            }
        } else if (!show && btnSkipIntro.isVisible) {
            if (ExperimentalMobileDesign.enabled()) {
                ExpMotion.fadeOutAndHide(btnSkipIntro)
            } else {
                val ctx = context ?: return
                val fadeOut = android.view.animation.AnimationUtils.loadAnimation(ctx, R.anim.fade_out)
                btnSkipIntro.startAnimation(fadeOut)
                btnSkipIntro.isGone = true
            }
        }
    }



    private var currentExtraBuffering = false
    private var currentSoftwareDecoder = false
    private var currentVavooTlsHost: String? = null

    private fun resolveVavooTlsHost(video: Video): String? {
        val provider = UserPreferences.currentProvider
        if (provider !is com.dskja.betterstreamflix.providers.VavooProvider) return null
        return runCatching { java.net.URI(video.source).host }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    private var currentExternalPlayerTried = false
    private var pendingPlayWithAfterResolve = false


    private fun seriesIdForAudioPref(): String? =
        (args.videoType as? Video.Type.Episode)?.tvShow?.id?.trim()?.takeIf { it.isNotEmpty() }

    private fun buildPlayer(extraBuffering: Boolean): ExoPlayer {
        val seriesId = seriesIdForAudioPref()
        val preferredAudio = seriesId?.let {
            com.dskja.betterstreamflix.player.SeriesAudioPreference.get(requireContext(), it)
        }
        return PlayerBuilderFactory.build(
            context = requireContext(),
            dataSourceFactory = dataSourceFactory,
            options = PlayerBuilderFactory.Options(
                extraBuffering = extraBuffering && !isLiveTvPlayback(),
                softwareDecoder = currentSoftwareDecoder,
                softwareDecoderForced =
                    PlayerSettingsView.Settings.SoftwareDecoder.selectedValue == true,
                seekIncrementsMs = if (isLiveTvPlayback()) null else 10_000L,
                preferStereoAudio = false,
                liveOptimized = isLiveTvPlayback(),
                preferredAudioLanguage = preferredAudio,
            ),
        )
    }

    private fun initializePlayer(
        extraBuffering: Boolean,
        softwareDecoder: Boolean = currentSoftwareDecoder,
        vavooTlsHost: String? = currentVavooTlsHost,
    ) {
        // Never rebuild after Back/teardown started — clearing isTearingDown here
        // used to resurrect Exo mid-navigateUp (zombie audio / surface race).
        if (isTearingDown || !isAdded || _binding == null) return
        releasePlayer(ReleaseMode.HARD_REPLACE)
        if (isTearingDown || !isAdded || _binding == null) return
        // Keep playerReleased=true until a new ExoPlayer is assigned (see policy).
        playbackSoftStopped = false
        playerViewDetached = false
        mediaSessionReleased = true
        currentExtraBuffering = extraBuffering
        currentSoftwareDecoder = softwareDecoder
        currentVavooTlsHost = vavooTlsHost
        PlaybackLifecycleGuard.register(playbackStopHandle)

        var tokenLogged = false
        val okHttpBuilder = PlaybackHttp.newStreamingClient().newBuilder()
        if (!vavooTlsHost.isNullOrBlank()) {
            com.dskja.betterstreamflix.utils.VavooTls.relaxForHost(okHttpBuilder, vavooTlsHost)
        }
        val okHttpClient = okHttpBuilder
            .addInterceptor { chain ->
                var request = chain.request()
                
                if (currentVideo?.maintainToken == true) {
                    val latestQuery = TokenManager.latestQuery
                    if (latestQuery != null) {
                        val origHttpUrl = request.url
                        val updatedHttpUrl = origHttpUrl.newBuilder().query(latestQuery).build()
                        request = request.newBuilder().url(updatedHttpUrl).build()
                        if (!tokenLogged) {
                            android.util.Log.d("TokenManager", "[MOBILE-INTERCEPTOR] Token successfully injected (applied to all segments)")
                            tokenLogged = true
                        }
                    } else {
                        android.util.Log.w("TokenManager", "[MOBILE-INTERCEPTOR] maintainToken=true but latestQuery is null! URL: ${request.url.host}")
                    }
                }
                
                chain.proceed(request)
            }
            .build()
        httpDataSource = OkHttpDataSource.Factory(okHttpClient)
            .setTransferListener(
                com.dskja.betterstreamflix.net.ThroughputTransferListener.asPlayback(),
            )

        dataSourceFactory = if (playingOffline) {
            val offlineFactory = runCatching {
                com.dskja.betterstreamflix.download.StreamflixDownloadManager
                    .playbackCacheDataSourceFactory(requireContext())
            }.getOrElse {
                Log.w("Player", "Offline cache not ready: ${it.message}")
                offlineCacheBlocked = true
                Toast.makeText(
                    requireContext(),
                    R.string.settings_download_storage_unavailable,
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            // Always wrap so sidecar subtitle file:// paths bypass FLAG_BLOCK_ON_CACHE.
            DefaultDataSource.Factory(requireContext(), offlineFactory)
        } else {
            DefaultDataSource.Factory(requireContext(), httpDataSource)
        }

        if (isTearingDown || !isAdded || _binding == null) return
        player = buildPlayer(extraBuffering).also { built ->
                mediaSession = MediaSession.Builder(requireContext(), built)
                    .build()
                mediaSessionReleased = false
            }
        playerReleased = false
        playbackSoftStopped = false
        playerViewDetached = false
        PlaybackLifecycleGuard.register(playbackStopHandle)

        SubtitleOffset.restore(requireContext(), subtitleOffsetKey())

        bufferEtaController?.release()
        bufferEtaController = com.dskja.betterstreamflix.player.BufferEtaController(
            label = binding.tvPlayerBufferEta,
            playerProvider = { if (::player.isInitialized && !playerReleased) player else null },
        )

        binding.settings.seriesId = seriesIdForAudioPref()
        if (isCasting) {
            binding.pvPlayer.player = castPlayer
            // Settings stay bound to the local ExoPlayer (track selection APIs).
            binding.settings.player = player
        } else {
            binding.pvPlayer.player = player
            binding.settings.player = player
        }
        binding.settings.subtitleView = binding.pvPlayer.subtitleView
        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
    }

    private fun setupCastControls() {
        val routeButton = runCatching {
            binding.pvPlayer.controller.binding.mediaRouteButton
        }.getOrNull() ?: return

        if (!UserPreferences.castEnabled || isOfflinePlayback()) {
            routeButton.isGone = true
            return
        }

        runCatching {
            CastPlaybackHub.ensureCastContext(requireContext())
            CastButtonFactory.setUpMediaRouteButton(requireContext(), routeButton)
            routeButton.isVisible = true

            castPlayer = CastPlaybackHub.obtainPlayer(requireContext())
            CastPlaybackHub.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() {
                    if (view == null) return
                    switchPlaybackToCast()
                }

                override fun onCastSessionUnavailable() {
                    if (view == null || isTearingDown) {
                        CastPlaybackHub.markCasting(false)
                        isCasting = false
                        if (::player.isInitialized && !playerReleased) {
                            runCatching {
                                player.playWhenReady = false
                                player.pause()
                            }
                        }
                        return
                    }
                    switchPlaybackToLocal()
                }
            })
            castEndedListener?.let { previous ->
                runCatching { castPlayer?.removeListener(previous) }
            }
            // Hub owns Cast END → queue advance. Fragments only react when the queue
            // is exhausted so local autoplay cannot race removeAt (CAST-P1-1).
            castQueueExhaustedListener?.let { CastPlaybackHub.removeQueueExhaustedListener(it) }
            val onQueueExhausted: () -> Unit = {
                // EpisodeManager already advanced on each queue claim — autoplay continues to E₃+.
                if (isAdded && view != null && !isTearingDown && UserPreferences.autoplay &&
                    !viewModel.isVideoResolveInFlight()
                ) {
                    playNextEpisodeAcrossSeasons(autoplay = true)
                }
                updateCastQueueChrome()
            }
            castQueueExhaustedListener = onQueueExhausted
            CastPlaybackHub.addQueueExhaustedListener(onQueueExhausted)
            castQueueChangedListener?.let { CastPlaybackHub.removeQueueChangedListener(it) }
            val onQueueChanged: (Int) -> Unit = {
                if (isAdded && view != null && !isTearingDown) {
                    updateCastQueueChrome()
                }
            }
            castQueueChangedListener = onQueueChanged
            CastPlaybackHub.addQueueChangedListener(onQueueChanged)
            castQueueAdvancedListener?.let { CastPlaybackHub.removeQueueAdvancedListener(it) }
            val onQueueAdvanced: () -> Unit = {
                if (isAdded && view != null && !isTearingDown) {
                    updatePlayerHeader()
                    setupEpisodeNavigationButtons()
                    scheduleCastNextEpisodeQueue()
                    updateCastQueueChrome()
                }
            }
            castQueueAdvancedListener = onQueueAdvanced
            CastPlaybackHub.addQueueAdvancedListener(onQueueAdvanced)
            castEndedListener = null

            // Re-attach if a session is already active (e.g. returning from browse).
            if (CastPlaybackHub.isCasting || castPlayer?.isCastSessionAvailable == true) {
                switchPlaybackToCast()
            }
            updateCastQueueChrome()
        }.onFailure {
            Log.w("PlayerCast", "Cast unavailable: ${it.message}")
            routeButton.isGone = true
            castPlayer = null
        }
    }

    private fun pushMediaToCast(
        video: Video,
        server: Video.Server,
        metadata: MediaMetadata = CastMediaFactory.buildMetadata(
            title = resolvePlayerTitle(),
            subtitle = resolvePlayerSubtitle(),
            videoType = args.videoType,
            serverId = server.id,
        ),
        startPosition: Long = player.currentPosition,
        playWhenReady: Boolean = true,
    ) {
        if (isOfflinePlayback() ||
            server.id == PlayerViewModel.OFFLINE_SERVER_ID ||
            server.name.equals(PlayerViewModel.OFFLINE_SERVER_NAME, ignoreCase = true)
        ) {
            return
        }
        val cp = castPlayer ?: CastPlaybackHub.obtainPlayer(requireContext()) ?: return
        castPlayer = cp

        val extracted = if (video.source.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
            decodeBase64Uri(video.source)?.let { extractUrlFromPlaylist(it) }
        } else {
            null
        }

        if (video.source.startsWith("data:", ignoreCase = true) && extracted.isNullOrBlank()) {
            showCastInfo(R.string.player_cast_unsupported_stream)
            return
        }

        val castItem = CastMediaFactory.mediaItemForCast(
            source = video.source,
            mimeType = video.type,
            headers = lastCastHeaders,
            metadata = metadata,
            subtitleConfigurations = subtitleConfigurationsForPlayback(
                context = requireContext(),
                videoType = args.videoType,
                serverSubtitles = video.subtitles,
            ),
            extractedFallback = extracted,
            live = isLiveTvPlayback(),
        )
        if (!CastPlaybackHub.lastWrapUsedProxy && isAdded) {
            showCastInfo(R.string.player_cast_proxy_unavailable)
        }
        cp.setMediaItem(castItem, startPosition)
        cp.prepare()
        cp.playWhenReady = playWhenReady
        scheduleCastNextEpisodeQueue()
    }

    private fun scheduleCastNextEpisodeQueue() {
        if (!isCasting && !CastPlaybackHub.isCasting) return
        if (args.videoType !is Video.Type.Episode) return
        if (CastPlaybackHub.queuedCount() > 0) return
        if (castNextQueueJob?.isActive == true) return
        castNextQueueJob = lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val prepared = withContext(Dispatchers.IO) {
                CastQueueCoordinator.prepareNextEpisodeQueue(
                    context = appContext,
                    provider = UserPreferences.currentProvider,
                    currentVideoType = args.videoType,
                    // null → subtitleConfigurationsForPlayback(next episode + server subs)
                    subtitleConfigurations = null,
                    // Do not override next-episode CDN headers with the previous item's.
                    headers = emptyMap(),
                )
            }
            if (prepared != null && isAdded) {
                CastQueueCoordinator.enqueueExclusive(prepared.mediaItem, prepared.episode)
                updateCastQueueChrome()
            } else if (
                isAdded &&
                EpisodeManager.peekNextEpisode() != null &&
                (UserPreferences.autoplay || UserPreferences.castQueueNextEpisode) &&
                CastQueueCoordinator.lastFailureMessage != null
            ) {
                showCastInfo(R.string.player_cast_queue_failed)
            }
        }
    }


    private fun notifyPlayer(message: CharSequence, titleRes: Int = R.string.player_settings_title) {
        ExpDialogChrome.notify(
            requireContext(),
            message,
            titleRes,
        )
    }

    private fun notifyPlayer(messageRes: Int, titleRes: Int = R.string.player_settings_title) {
        notifyPlayer(getString(messageRes), titleRes)
    }

    private fun showCastInfo(messageRes: Int) {
        val message = getString(messageRes)
        if (ExperimentalMobileDesign.enabled()) {
            ExpDialogChrome.showInfo(
                requireContext(),
                R.string.player_cast_content_description,
                message,
            ) { ctx -> MaterialAlertDialogBuilder(ExpDialogChrome.themedContext(ctx)) }
        } else {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun switchPlaybackToCast() {
        val cp = castPlayer ?: CastPlaybackHub.obtainPlayer(requireContext()) ?: return
        castPlayer = cp
        if (!::player.isInitialized || playerReleased) return
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        player.volume = 0f
        player.playWhenReady = false
        runCatching { player.pause() }

        val video = currentVideo
        val server = currentServer
        if (video != null && server != null) {
            pushMediaToCast(video, server, startPosition = position, playWhenReady = playWhenReady)
        } else {
            val mediaItem = player.currentMediaItem
            if (mediaItem != null) {
                val headers = lastCastHeaders
                val uri = mediaItem.localConfiguration?.uri?.toString().orEmpty()
                val castUri = CastPlaybackHub.wrapForCast(uri, headers)
                val castItem = mediaItem.buildUpon()
                    .setUri(castUri.toUri())
                    .setMediaMetadata(
                        CastMediaFactory.buildMetadata(
                            title = resolvePlayerTitle(),
                            subtitle = resolvePlayerSubtitle(),
                            videoType = args.videoType,
                            serverId = currentServer?.id,
                        )
                    )
                    .build()
                cp.setMediaItem(castItem, position)
                cp.prepare()
                cp.playWhenReady = playWhenReady
            }
        }

        if (_binding != null) {
            binding.pvPlayer.player = cp
        }
        isCasting = true
        CastPlaybackHub.markCasting(true)
        if (UserPreferences.castKeepScreenAwake) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun switchPlaybackToLocal() {
        val cp = castPlayer ?: CastPlaybackHub.playerOrNull()
        if (!::player.isInitialized || playerReleased) {
            CastPlaybackHub.markCasting(false)
            isCasting = false
            return
        }
        val position = cp?.currentPosition ?: player.currentPosition
        val playWhenReady = cp?.playWhenReady ?: true
        runCatching { cp?.stop() }
        if (player.currentMediaItem != null) {
            player.volume = 1f
            player.seekTo(position)
            player.playWhenReady = playWhenReady
        }
        if (_binding != null) {
            binding.pvPlayer.player = player
            binding.settings.player = player
        }
        isCasting = false
        CastPlaybackHub.markCasting(false)
        activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private enum class ReleaseMode {
        /** Back / nav away — silence audio, detach views; defer ExoPlayer.release(). */
        SOFT_LEAVE,
        /** Swap server/episode on the same screen — must release old Exo instance. */
        HARD_REPLACE,
        /** onDestroyView — surface cleared, safe to release ExoPlayer. */
        HARD_DESTROY,
    }

    private fun detachPlayerViews() {
        val b = _binding ?: return
        runCatching {
            b.pvPlayer.player = null
            b.settings.player = null
            b.settings.subtitleView = null
        }
        runCatching {
            com.bumptech.glide.Glide.with(b.root).clear(b.ivNextEpisodePoster)
        }
        runCatching { TrailerStreamCrossfade.revealStream(b.ivTrailerStreamBridge, durationMs = 0L) }
        playerViewDetached = true
    }

    private fun releasePlayer(mode: ReleaseMode) {
        stopProgressHandler()
        stopStuckWatch()
        stuckDetector.reset()
        stopLiveEdgeWatcher()
        PlaybackLifecycleGuard.unregister(playbackStopHandle)
        bufferEtaController?.release()
        bufferEtaController = null

        if (_binding != null) {
            detachPlayerViews()
        }

        // Release MediaSession BEFORE touching ExoPlayer — keeps audio focus from zombie playback.
        if (::mediaSession.isInitialized && !mediaSessionReleased) {
            runCatching { mediaSession.release() }
            mediaSessionReleased = true
        }

        if (::player.isInitialized && !playerReleased) {
            playbackListener?.let { runCatching { player.removeListener(it) } }
            playbackListener = null
            // Clean Exit: mute then pause. stop/clear/release race OEM surface teardown on Back.
            com.dskja.betterstreamflix.player.PlayerCleanExit.silenceExo(player)
            playbackSoftStopped = true

            val hardRelease = when (mode) {
                ReleaseMode.HARD_DESTROY -> PlayerTeardownPolicy.allowHardRelease(
                    viewAttached = _binding != null,
                    playerViewDetached = playerViewDetached,
                    destroying = true,
                )
                ReleaseMode.HARD_REPLACE -> PlayerTeardownPolicy.allowHardRelease(
                    viewAttached = _binding != null,
                    playerViewDetached = playerViewDetached,
                    destroying = false,
                    replacing = true,
                )
                ReleaseMode.SOFT_LEAVE -> PlayerTeardownPolicy.allowHardRelease(
                    viewAttached = _binding != null,
                    playerViewDetached = playerViewDetached,
                    destroying = false,
                )
            }
            if (hardRelease) {
                runCatching {
                    player.stop()
                    player.clearMediaItems()
                }
                val toRelease = player
                playerReleased = true
                val doRelease = Runnable {
                    runCatching { toRelease.release() }
                }
                if (PlayerTeardownPolicy.shouldDeferExoRelease(hardRelease = true)) {
                    val anchor = _binding?.root
                    if (anchor != null) {
                        anchor.post(doRelease)
                    } else {
                        android.os.Handler(android.os.Looper.getMainLooper()).post(doRelease)
                    }
                } else {
                    doRelease.run()
                }
            }
        }
    }

    private fun isSerienStreamBypassUrl(url: String): Boolean {
        return SerienStreamBypassHelper.isGermanStreamGateHost(url)
    }


    private fun openCurrentStreamExternally(forceChooser: Boolean = false) {
        if (isOfflinePlayback()) {
            Toast.makeText(
                requireContext(),
                R.string.player_offline_external_unavailable,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val video = currentVideo
        if (video == null || video.source.isBlank()) {
            Toast.makeText(
                requireContext(),
                R.string.player_external_player_error_video,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        isIgnoringPip = true
        pauseLocalForHandoff()
        val videoTitle = when {
            isLiveTvPlayback() -> resolvePlayerTitle()
            else -> when (val type = args.videoType) {
                is Video.Type.Movie -> type.title
                is Video.Type.Episode -> "${type.tvShow.title} • S${type.season.number} E${type.number}"
            }
        }
        val position = if (::player.isInitialized && !playerReleased) player.currentPosition else 0L
        com.dskja.betterstreamflix.platform.playerbackend.ExternalStreamHandoff.launch(
            requireActivity(),
            com.dskja.betterstreamflix.platform.playerbackend.ExternalStreamHandoff.Request(
                sourceUrl = video.source,
                headers = video.headers.orEmpty(),
                title = videoTitle,
                positionMs = position,
                mimeType = video.type,
            ),
            forceChooser = forceChooser,
        )
    }

    private fun openCurrentStreamWithDownloader() {
        if (isOfflinePlayback()) {
            Toast.makeText(
                requireContext(),
                R.string.player_offline_external_unavailable,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val video = currentVideo
        if (video == null || video.source.isBlank()) {
            com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(
                requireContext(),
                R.string.external_download_invalid_url,
                R.string.external_download_with_title,
            )
            return
        }
        val fileName = when (val type = args.videoType) {
            is Video.Type.Movie -> type.title
            is Video.Type.Episode -> "${type.tvShow.title}.S${type.season.number}E${type.number}"
        }
        pauseLocalForHandoff()
        val result = com.dskja.betterstreamflix.download.ExternalDownloadHandoff.launch(
            requireActivity(),
            com.dskja.betterstreamflix.download.ExternalDownloadHandoff.Request(
                url = video.source,
                headers = video.headers.orEmpty(),
                fileName = fileName,
                mimeType = video.type,
                title = fileName,
            ),
            forceChooser = false,
        )
        if (result is com.dskja.betterstreamflix.download.ExternalDownloadHandoff.HandoffResult.Started) {
            com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(
                requireContext(),
                R.string.external_download_started,
            )
        }
    }

    private fun buildSerienStreamBypassUrl(serverList: List<Video.Server> = servers): String? {
        return SerienStreamBypassHelper.buildEpisodeBypassUrl(args.videoType, serverList)
    }

    private fun applyBypassCookies(url: String, cookieHeader: String) {
        SerienStreamBypassHelper.applyCookies(url, cookieHeader)
    }
}
