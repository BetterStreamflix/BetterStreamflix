package com.betterstreamflix.app.activities.main

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.setupWithNavController
import com.betterstreamflix.app.BuildConfig
import com.betterstreamflix.app.R
import com.betterstreamflix.app.activities.tools.BypassWebViewActivity
import com.betterstreamflix.app.cast.CastPlaybackHub
import com.betterstreamflix.app.databinding.ActivityMainMobileBinding
import com.betterstreamflix.app.fragments.player.PlayerMobileFragment
import com.betterstreamflix.app.live.ChannelPinStripController
import com.betterstreamflix.app.player.PlaybackLifecycleGuard
import com.betterstreamflix.app.providers.AnimeOnlineNinjaProvider
import com.betterstreamflix.app.providers.Cine24hProvider
import com.betterstreamflix.app.providers.FilmyOnlineCcProvider
import com.betterstreamflix.app.providers.FrenchAnimeProvider
import com.betterstreamflix.app.providers.GuardaSerieProvider
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.providers.IptvProvider
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.providers.RidomoviesProvider
import com.betterstreamflix.app.providers.SoloLatinoProvider
import com.betterstreamflix.app.providers.ZaluknijProvider
import com.betterstreamflix.app.ui.UpdateAppMobileDialog
import com.betterstreamflix.app.utils.AccessibilityPreferences
import com.betterstreamflix.app.utils.BypassWebSocketEndpointHelper
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.ExpPressEffects
import com.betterstreamflix.app.utils.ProviderChangeNotifier
import com.betterstreamflix.app.utils.ThemeManager
import com.betterstreamflix.app.utils.UserPreferences
import com.betterstreamflix.app.utils.getCurrentFragment
import com.betterstreamflix.app.providers.KinoGerProvider
import com.betterstreamflix.app.providers.SerienStreamProvider
import com.google.android.gms.cast.framework.CastContext
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Base64
import kotlin.coroutines.resume

class MainMobileActivity : FragmentActivity() {

    private companion object {
        const val RESOLVER_TIMEOUT_MS = 12_000L

        /** orderInCategory slot between home(10) and movies(30) — see menu XML. */
        const val LIVE_HUB_MENU_ORDER = 20
    }

    private data class ResolverPayload(
        val url: String,
    )

    private var _binding: ActivityMainMobileBinding? = null
    private val binding get() = _binding!!

    private var channelPinStripController: ChannelPinStripController? = null

        /** Tracks prior Nav destination for Clean Exit audio/focus. */
    private var lastNavDestinationId: Int? = null

    private val viewModel by viewModels<MainViewModel>()
    private val resolverWebSocketClient by lazy { OkHttpClient() }
    private val bypassWebViewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val wsUrl = pendingWs
            val token = pendingToken
            val payloadBound = pendingPayloadBound
            val cookies =
                result.data?.getStringExtra(BypassWebViewActivity.EXTRA_COOKIE_HEADER)?.trim()

            clearResolverState()

            if (result.resultCode != Activity.RESULT_OK || wsUrl.isNullOrBlank() || token.isNullOrBlank()) {
                return@registerForActivityResult
            }

            // Never echo cookies to a non-LAN / unbound WebSocket (SEC-DL-01).
            // Require a prior successful payload bind for this exact (ws, token) pair.
            if (!BypassWebSocketEndpointHelper.isAllowedPeerWs(wsUrl) || !payloadBound) {
                Log.w(
                    "ResolverWS",
                    "Refusing cookie send to disallowed/unbound ws peer " +
                        "(allowed=${BypassWebSocketEndpointHelper.isAllowedPeerWs(wsUrl)} bound=$payloadBound)",
                )
                return@registerForActivityResult
            }

            lifecycleScope.launch {
                sendWebSocketDone(wsUrl, token, cookies)
                showPostBypassCloseDialog()
            }
        }

    private val telegramGateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            com.betterstreamflix.app.telegram.TelegramJoinGateController.onGateFinished(this)
        }

    private var pendingWs: String? = null
    private var pendingToken: String? = null
    /** Set only after a successful payload: response for the pending (ws, token) pair. */
    private var pendingPayloadBound: Boolean = false

    private var updateAppDialog: UpdateAppMobileDialog? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AccessibilityPreferences.wrapAfterLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.mobileThemeRes(UserPreferences.selectedTheme))
        AccessibilityPreferences.applyHighContrastOverlay(this, mobile = true)
        super.onCreate(savedInstanceState)
        // Finish a provider switch after CLEAR_TASK restart (Room reset deferred).
        UserPreferences.consumePendingProviderSwitch()
        UserPreferences.clearInvalidCurrentProvider()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarColors()

        _binding = ActivityMainMobileBinding.bind(
            layoutInflater.inflate(
                R.layout.activity_main_mobile,
                null,
                false,
            )
        )
        setContentView(binding.root)
        applyExperimentalNavigationChrome()
        binding.root.findViewById<View>(R.id.bv_main_nav)?.let { nav ->
            nav.setBackgroundResource(R.drawable.bg_meta_pill)
            ExpMotion.enterScreen(binding.root)
            ExpMotion.popIn(nav)
            ExpMotion.popIn(binding.btnMainSearch)
        }
        if (UserPreferences.castEnabled) {
            CastPlaybackHub.ensureCastContext(this)
            CastPlaybackHub.addSessionStateListener(castSessionListener)
            updateCastMiniController(
                (supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment)
                    ?.navController?.currentDestination?.id
            )
        }

        // Defer provider native/WebView setup so splash/first frame can paint first.
        window.decorView.post {
            runCatching { AnimeOnlineNinjaProvider.init(this) }
            runCatching { Cine24hProvider.init(this) }
            runCatching { FilmyOnlineCcProvider.init(this) }
            runCatching { FrenchAnimeProvider.init(this) }
            runCatching { GuardaSerieProvider.init(this) }
            runCatching { SoloLatinoProvider.init(this) }
            runCatching { RidomoviesProvider.init(this) }
            runCatching { ZaluknijProvider.init(this) }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val currentFragment = navHostFragment?.childFragmentManager?.primaryNavigationFragment

            val isPlayer = currentFragment is PlayerMobileFragment
            val isBottomNavVisible = binding.bnvMain.visibility == View.VISIBLE

            val bottomPadding = if (isPlayer || isBottomNavVisible) 0 else insets.bottom
            val topPadding = if (isPlayer) 0 else insets.top

            view.setPadding(insets.left, topPadding, insets.right, bottomPadding)
            windowInsets
        }


        updateImmersiveMode()

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        val navController = navHost?.navController
        if (navController != null) {
            com.betterstreamflix.app.utils.SentryBootstrap.trackNavigation(navController)
        }

        if (BuildConfig.APP_LAYOUT == "tv" ||
            (BuildConfig.APP_LAYOUT != "mobile" &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        ) {
            finish()
            startActivity(Intent(this, MainTvActivity::class.java))
            return
        }

        // First-launch onboarding before Telegram invite / Soft support prompts.
        com.betterstreamflix.app.onboarding.OnboardingController.maybeShow(this, isTv = false)

        // Hard Telegram gate overlay — after TV redirect so TV never sees it.
        // Must run for every non-TV MainMobileActivity (mobile + universal phone).
        // Deferred while onboarding owns the cold-start surface.
        if (savedInstanceState == null) {
            com.betterstreamflix.app.telegram.TelegramJoinGateController.resetLaunchState()
        }
        com.betterstreamflix.app.telegram.TelegramJoinGateController.kickEarly(this)

        if (navHost == null || navController == null) {
            android.util.Log.e("MainMobileActivity", "NavHostFragment missing — aborting setup")
            runCatching {
                io.sentry.Sentry.captureMessage("MainMobileActivity NavHostFragment missing")
            }
            finish()
            return
        }

        if (savedInstanceState == null) {
            UserPreferences.currentProvider?.let {
                navController.navigate(
                    R.id.home,
                    null,
                    navOptions {
                        launchSingleTop = true
                        popUpTo(R.id.providers) {
                            inclusive = true
                        }
                    }
                )
            }
        }
        // PIN/kids gate keys off process unlock, not only cold create — process death
        // can restore a non-null savedInstanceState while unlockedProfileId is cleared.
        binding.root.post { showProfileUnlockGateIfNeeded(navHost) }

        viewModel.checkUpdate()

        binding.bnvMain.setupWithNavController(navController)
        // Downloads tab badge: live count of queued/active downloads.
        lifecycleScope.launch {
            com.betterstreamflix.app.download.OfflineBadgeStore
                .activeKeys(this@MainMobileActivity)
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { keys ->
                    val badge = binding.bnvMain.getOrCreateBadge(R.id.downloads)
                    badge.isVisible = keys.isNotEmpty()
                    if (keys.isNotEmpty()) badge.number = keys.size.coerceAtMost(99)
                }
        }
        with(ExpPressEffects) { binding.btnMainSearch.applyExpPress() }
        binding.bnvMain.setOnItemReselectedListener {
            ExpMotion.hapticTap(binding.bnvMain)
        }
        var lastNavId = navController.currentDestination?.id
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id != lastNavId &&
                binding.bnvMain.menu.findItem(destination.id) != null
            ) {
                ExpMotion.hapticTap(binding.bnvMain)
            }
            lastNavId = destination.id
        }
        binding.btnMainSearch.setOnClickListener {
            ExpMotion.hapticTap(it)
            if (navController.currentDestination?.id != R.id.search) {
                navController.navigate(R.id.search)
            }
        }
        updateNavigationVisibility()
        updateBottomNavigationVisibility(navController.currentDestination?.id)

        channelPinStripController = ChannelPinStripController(this, binding.channelPinStrip).also {
            it.attach()
            it.onDestinationChanged(navController.currentDestination?.id)
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val leavingPlayer = lastNavDestinationId == R.id.player && destination.id != R.id.player
            if (destination.id == R.id.player &&
                com.betterstreamflix.app.kids.KidsBedtime.shouldBlockEnteringPlayer()
            ) {
                // KB-016: pop synchronously to shrink one-frame player flash; PIN sheet posts.
                runCatching {
                    if (navController.currentDestination?.id == R.id.player) {
                        navController.popBackStack()
                    }
                }
                binding.root.post {
                    com.betterstreamflix.app.kids.KidsBedtimeUi.blockPlayerEntry(this@MainMobileActivity)
                }
            }
            if (destination.id != R.id.player) {
                // Clean Exit: bottom nav / deep links can leave the player without Back — kill audio.
                PlaybackLifecycleGuard.stopActivePlayback()
                if (leavingPlayer) {
                    com.betterstreamflix.app.player.PlayerCleanExit.noteLeavingPlayer(
                        returningToHome = destination.id == R.id.home,
                    )
                    runCatching {
                        com.betterstreamflix.app.ui.TrailerPlaybackController.silenceAllActive()
                    }
                }
            }
            updateNavigationVisibility(destination.id)
            updateBottomNavigationVisibility(destination.id)
            channelPinStripController?.onDestinationChanged(destination.id)
            binding.mainContent.post { binding.mainContent.requestApplyInsets() }
            lastNavDestinationId = destination.id
        }

        lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect {
                    updateNavigationVisibility(navController.currentDestination?.id)
                }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        showUpdateDialog(state)
                    }

                    MainViewModel.State.DownloadingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainMobileActivity, state.apk)
                        dismissUpdateDialog()
                    }

                    MainViewModel.State.InstallingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        updateAppDialog?.isLoading = false
                        val message = state.error.message ?: "Update failed"
                        com.betterstreamflix.app.utils.ExpDialogChrome.notify(
                            this@MainMobileActivity,
                            message,
                            R.string.update_title,
                        )
                    }

                    else -> {}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (com.betterstreamflix.app.telegram.TelegramJoinGateController.onBackPressed(this@MainMobileActivity)) {
                    return
                }
                val playerFrag = getCurrentFragment() as? PlayerMobileFragment
                val handled = playerFrag?.onBackPressed() ?: false
                if (handled) return
                // Leaving the player via system Back / gesture — kill audio before navigateUp
                // so Home never keeps a zombie ExoPlayer/MediaSession.
                if (playerFrag != null) {
                    playerFrag.forceStopPlayback()
                } else {
                    PlaybackLifecycleGuard.stopActivePlayback()
                }

                val currentDestinationId = navController.currentDestination?.id

                if (currentDestinationId == R.id.settings) {
                    if (!navController.navigateUp()) {
                        navigateToProviderHome(navController)
                    }
                    return
                }

                if (UserPreferences.currentProvider != null && currentDestinationId == R.id.home) {
                    closeTask()
                    return
                }

                if (UserPreferences.currentProvider != null &&
                    isTopLevelProviderDestination(currentDestinationId)
                ) {
                    navigateToProviderHome(navController)
                    return
                }

                if (!navController.navigateUp()) finish()
            }
        })

        // Share list / Watch Party / widget deep links must run even after process-death restore
        // (savedInstanceState != null) so the URI is not dropped.
        val deepLinkRestore = intent.data?.let { data ->
            com.betterstreamflix.app.sync.SharedWatchlistLinks.parseToken(data) != null ||
                com.betterstreamflix.app.sync.WatchPartyLinks.parseToken(data) != null ||
                com.betterstreamflix.app.download.DownloadPackLinks.parse(data) != null ||
                com.betterstreamflix.app.profiletransfer.ProfileTransferLinks.parse(data) != null ||
                com.betterstreamflix.app.sync.CastRoomLinks.parseToken(data) != null ||
                com.betterstreamflix.app.sync.SessionHandoffLinks.parseCode(data.toString()) != null ||
                com.betterstreamflix.app.sync.SessionHandoffLinks.parseToken(data.toString()) != null ||
                com.betterstreamflix.app.remotefind.RemoteFindLinks.parse(data) != null ||
                com.betterstreamflix.app.sync.CastHandoffLinks.parseShortCode(data) != null ||
                com.betterstreamflix.app.widgets.WidgetDeepLinks.parse(data) != null ||
                com.betterstreamflix.app.tvlauncher.TvWatchDeepLink.parse(data.toString()) != null
        } == true
        if (savedInstanceState == null || deepLinkRestore) {
            handleIntent(intent)
        }

        // Re-ensure after full onCreate setup (overlay already kicked above).
        com.betterstreamflix.app.telegram.TelegramJoinGateController.onColdStart(this)
        com.betterstreamflix.app.telegram.TelegramJoinGateController.onActivityCreated(
            this,
            telegramGateLauncher,
        )
        registerCastRoomHostConsumer()
    }

    /** CC-009: Mobile host needs the same Sidekick consumer as TV. */
    private fun registerCastRoomHostConsumer() {
        com.betterstreamflix.app.sync.CastRoomUi.registerHostConsumer(
            object : com.betterstreamflix.app.sync.CastRoomUi.HostConsumer {
                override fun onCastRoomPlayItem(
                    item: com.betterstreamflix.app.sync.CastRoomItemPayload,
                    preferredServerName: String?,
                ) {
                    com.betterstreamflix.app.sync.CastRoomUi.navigateHostToItem(
                        this@MainMobileActivity,
                        item,
                    )
                }

                override fun onCastRoomSetPlaying(playing: Boolean) {
                    val navHost =
                        supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                    val player = navHost?.childFragmentManager?.primaryNavigationFragment
                        as? PlayerMobileFragment
                    player?.castRoomApplyRemotePlaying(playing)
                }

                override fun onCastRoomSeek(positionMs: Long) {
                    runOnUiThread {
                        val navHost =
                            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                        val player = navHost?.childFragmentManager?.primaryNavigationFragment
                            as? PlayerMobileFragment
                        player?.castRoomApplyRemoteSeek(positionMs)
                    }
                }

                override fun onCastRoomSidekickText(text: String) {
                    runOnUiThread {
                        applySidekickKeyboardQuery(text)
                    }
                }

                override fun onCastRoomEnded() {
                    runOnUiThread {
                        com.betterstreamflix.app.utils.ExpDialogChrome.notify(
                            this@MainMobileActivity,
                            R.string.cast_room_ended,
                        )
                    }
                }
            },
        )
    }

    private fun applySidekickKeyboardQuery(text: String) {
        val query = com.betterstreamflix.app.sync.TvSidekick.sanitizeKeyboard(text) ?: return
        val navController =
            (supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment)
                ?.navController
        runCatching { navController?.navigate(R.id.search) }
        binding.root.post {
            val frag = getCurrentFragment()
                as? com.betterstreamflix.app.fragments.search.SearchMobileFragment
            if (frag != null) {
                frag.applySidekickQuery(query)
            } else {
                binding.root.postDelayed({
                    (getCurrentFragment()
                        as? com.betterstreamflix.app.fragments.search.SearchMobileFragment)
                        ?.applySidekickQuery(query)
                }, 350L)
            }
            com.betterstreamflix.app.utils.ExpDialogChrome.notify(
                this,
                getString(R.string.tv_sidekick_keyboard_applied, query),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        com.betterstreamflix.app.telegram.TelegramJoinGateController.onActivityResumed(
            this,
            telegramGateLauncher,
        )
        com.betterstreamflix.app.sync.SharedWatchlistUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.WatchPartyUi.tryFlushPending(this)
        com.betterstreamflix.app.download.DownloadPackUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.CastRoomUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.CastHandoffUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.SessionHandoffUi.tryFlushPending(this)
    }

    override fun onStart() {
        super.onStart()
        com.betterstreamflix.app.profiles.ProfileUnlockGate.markForeground()
        runCatching {
            com.betterstreamflix.app.profiles.ProfileManager.sweepExpiredGuests(this)
        }
        com.betterstreamflix.app.profiles.GuestForegroundSweep.onStart(this)
        com.betterstreamflix.app.telegram.TelegramJoinGateController.onActivityStarted(
            this,
            telegramGateLauncher,
        )
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        if (navHost != null) {
            binding.root.post { showProfileUnlockGateIfNeeded(navHost) }
        }
        binding.root.post {
            com.betterstreamflix.app.kids.KidsBedtimeUi.maybeShowLock(this@MainMobileActivity)
        }
        com.betterstreamflix.app.sync.SharedWatchlistUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.WatchPartyUi.tryFlushPending(this)
        com.betterstreamflix.app.download.DownloadPackUi.tryFlushPending(this)
        com.betterstreamflix.app.profiletransfer.ProfileTransferUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.CastRoomUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.CastHandoffUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.SessionHandoffUi.tryFlushPending(this)
    }

    override fun onStop() {
        com.betterstreamflix.app.profiles.GuestForegroundSweep.onStop()
        com.betterstreamflix.app.profiles.ProfileUnlockGate.markBackgrounded()
        super.onStop()
    }

    private fun showProfileUnlockGateIfNeeded(navHost: NavHostFragment) {
        if (isFinishing || isDestroyed) return
        if (!com.betterstreamflix.app.profiles.ProfileUnlockGate.requiresGateForActive()) {
            com.betterstreamflix.app.sync.SharedWatchlistUi.tryFlushPending(this)
            com.betterstreamflix.app.sync.WatchPartyUi.tryFlushPending(this)
            com.betterstreamflix.app.download.DownloadPackUi.tryFlushPending(this)
            com.betterstreamflix.app.profiletransfer.ProfileTransferUi.tryFlushPending(this)
            com.betterstreamflix.app.sync.CastRoomUi.tryFlushPending(this)
            com.betterstreamflix.app.sync.CastHandoffUi.tryFlushPending(this)
            com.betterstreamflix.app.sync.SessionHandoffUi.tryFlushPending(this)
            return
        }
        val host = navHost.childFragmentManager.primaryNavigationFragment ?: navHost
        com.betterstreamflix.app.profiles.ProfileUnlockGate.maybeShow(
            activity = this,
            hostFragmentProvider = { host },
            onUnlocked = {
                com.betterstreamflix.app.sync.SharedWatchlistUi.tryFlushPending(this)
                com.betterstreamflix.app.sync.WatchPartyUi.tryFlushPending(this)
                com.betterstreamflix.app.download.DownloadPackUi.tryFlushPending(this)
                com.betterstreamflix.app.profiletransfer.ProfileTransferUi.tryFlushPending(this)
                com.betterstreamflix.app.sync.CastRoomUi.tryFlushPending(this)
                com.betterstreamflix.app.sync.CastHandoffUi.tryFlushPending(this)
                com.betterstreamflix.app.sync.SessionHandoffUi.tryFlushPending(this)
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        com.betterstreamflix.app.sync.CastRoomUi.registerHostConsumer(null)
        CastPlaybackHub.removeSessionStateListener(castSessionListener)
        dismissUpdateDialog()
        // Cancel deferred posts only — clearing launching here races the gate Activity
        // when "Don't keep activities" destroys Main under the child.
        com.betterstreamflix.app.telegram.TelegramJoinGateController.cancelPendingOnly()
        _binding = null
        super.onDestroy()
    }

    private fun clearResolverState() {
        pendingWs = null
        pendingToken = null
        pendingPayloadBound = false
    }

    private fun showUpdateDialog(state: MainViewModel.State.SuccessCheckingUpdate) {
        if (isFinishing || isDestroyed) return

        dismissUpdateDialog()
        updateAppDialog = UpdateAppMobileDialog(this, state.newReleases).also { dialog ->
            dialog.setOnUpdateClickListener {
                if (!dialog.isLoading) {
                    viewModel.downloadUpdate(this@MainMobileActivity, state.asset)
                }
            }
            dialog.show()
        }
    }

    private fun dismissUpdateDialog() {
        updateAppDialog?.takeIf { it.isShowing }?.dismiss()
        updateAppDialog = null
    }

    private fun updateBottomNavigationVisibility(destinationId: Int?) {
        val showBottomNav =
            UserPreferences.currentProvider != null && isTopLevelProviderDestination(destinationId)
        val navVisibility = if (showBottomNav) View.VISIBLE else View.GONE
        binding.bnvMain.visibility = navVisibility
        // Nav container view shares the bottom-nav visibility state.
        binding.root.findViewById<View>(R.id.bv_main_nav)?.let { pill ->
            val wasVisible = pill.visibility == View.VISIBLE
            pill.visibility = navVisibility
            if (showBottomNav && !wasVisible) {
                ExpMotion.popIn(pill)
            }
        }
        // Keep Search FAB on Downloads (and Settings) so Search stays reachable —
        // Downloads has no in-page Search entry otherwise. Hide on Home — bottom
        // nav already has Search, and the FAB collides with the profile pill.
        val showSearch = UserPreferences.currentProvider != null &&
            isTopLevelProviderDestination(destinationId) &&
            destinationId != R.id.search &&
            destinationId != R.id.home
        val searchWasVisible = binding.btnMainSearch.visibility == View.VISIBLE
        if (showSearch) {
            binding.btnMainSearch.visibility = View.VISIBLE
            if (!searchWasVisible) {
                ExpMotion.popIn(binding.btnMainSearch)
            }
        } else if (searchWasVisible) {
            ExpMotion.fadeOutAndHide(binding.btnMainSearch)
        } else {
            binding.btnMainSearch.visibility = View.GONE
        }
        runCatching {
            val mini = binding.root.findViewById<View>(R.id.cast_mini_controller)
            updateCastMiniController(destinationId, mini)
        }
    }

    private fun updateCastMiniController(destinationId: Int?, mini: View? = null) {
        val controller = mini ?: binding.root.findViewById(R.id.cast_mini_controller) ?: return
        val show = UserPreferences.castEnabled &&
            CastPlaybackHub.isCasting &&
            destinationId != R.id.player
        if (show) {
            // Prefer flat cast chrome on OEM forks that crash LayerDrawable
            // linear gradients during draw (BETTERSTREAMFLIX-3J).
            runCatching {
                controller.setBackgroundResource(R.drawable.bg_cinema_glass_card)
            }.onFailure {
                controller.setBackgroundResource(R.drawable.bg_exp_cast_mini_flat)
            }
            val wasVisible = controller.visibility == View.VISIBLE
            controller.visibility = View.VISIBLE
            controller.elevation = 10f * resources.displayMetrics.density
            if (!wasVisible) ExpMotion.popIn(controller)
            if (controller.getTag(R.id.exp_enter_animated_tag) != true) {
                controller.setTag(R.id.exp_enter_animated_tag, true)
                val primary = MaterialColors.getColor(
                    controller, androidx.appcompat.R.attr.colorPrimary,
                )
                with(com.betterstreamflix.app.utils.ExpPressEffects) {
                    fun wire(group: android.view.ViewGroup) {
                        for (i in 0 until group.childCount) {
                            val child = group.getChildAt(i)
                            if (child.isClickable) child.applyExpPress()
                            when (child) {
                                is android.widget.ImageButton,
                                is android.widget.ImageView,
                                -> {
                                    if (child.isClickable || child is android.widget.ImageButton) {
                                        runCatching {
                                            child.setBackgroundResource(
                                                R.drawable.bg_detail_icon_chip,
                                            )
                                        }
                                        (child as android.widget.ImageView).imageTintList =
                                            android.content.res.ColorStateList.valueOf(primary)
                                        ExpMotion.popIn(child)
                                    }
                                }
                                is android.widget.ProgressBar -> {
                                    child.progressTintList =
                                        android.content.res.ColorStateList.valueOf(primary)
                                }
                                is android.widget.TextView -> {
                                    child.setTextColor(
                                        MaterialColors.getColor(
                                            child,
                                            com.google.android.material.R.attr.colorOnSurface,
                                        ),
                                    )
                                }
                            }
                            if (child is android.view.ViewGroup) wire(child)
                        }
                    }
                    if (controller is android.view.ViewGroup) wire(controller)
                }
            }
        } else {
            controller.elevation = 0f
            controller.setTag(R.id.exp_enter_animated_tag, null)
            if (controller.visibility == View.VISIBLE) {
                ExpMotion.fadeOutAndHide(controller)
            } else {
                controller.visibility = View.GONE
            }
        }
    }

    private val castSessionListener: (Boolean) -> Unit = { _ ->
        runOnUiThread {
            if (_binding == null) return@runOnUiThread
            val navHost =
                supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            updateCastMiniController(navHost?.navController?.currentDestination?.id)
        }
    }

    private fun updateNavigationVisibility(currentDestinationId: Int? = null) {
        val provider = UserPreferences.currentProvider ?: return
        val supportsMovies = Provider.supportsMovies(provider)
        val supportsTvShows = Provider.supportsTvShows(provider)
        val liveHubVisible = !com.betterstreamflix.app.utils.LiveHubCatalog.isAgeGated() &&
            com.betterstreamflix.app.kids.KidsFortress.liveHubAllowed() &&
            com.betterstreamflix.app.utils.LiveHubCatalog.isLiveProvider(provider)

        binding.bnvMain.menu.findItem(R.id.movies)?.isVisible = supportsMovies
        binding.bnvMain.menu.findItem(R.id.tv_shows)?.apply {
            isVisible = supportsTvShows
            title = if (provider is IptvProvider) {
                getString(R.string.main_menu_all_channels)
            } else {
                getString(R.string.main_menu_tv_shows)
            }
        }
        // Kids Fortress: kids keep Downloads (isolated DB + folder); Live Hub only for
        // Live/IPTV providers (and never under kids / parental age-gate).
        binding.bnvMain.menu.findItem(R.id.downloads)?.isVisible =
            com.betterstreamflix.app.kids.KidsFortress.downloadsNavVisible()

        // BottomNavigationView rejects >6 items at menu-inflation time, so live_hub
        // is not in the XML — it joins here for Live/IPTV providers, swapping in
        // for an item this provider does not show (usually movies/tv_shows).
        val navMenu = binding.bnvMain.menu
        val liveItem = navMenu.findItem(R.id.live_hub)
        if (liveHubVisible && liveItem == null) {
            val hiddenSlot = intArrayOf(R.id.movies, R.id.tv_shows, R.id.downloads, R.id.favorites)
                .firstOrNull { navMenu.findItem(it)?.isVisible == false }
            hiddenSlot?.let { navMenu.removeItem(it) }
            if (navMenu.size() < 6) {
                navMenu.add(
                    android.view.Menu.NONE,
                    R.id.live_hub,
                    LIVE_HUB_MENU_ORDER,
                    R.string.main_menu_live,
                ).setIcon(R.drawable.ic_menu_live)
            }
        } else if (!liveHubVisible && liveItem != null) {
            navMenu.removeItem(R.id.live_hub)
        }
        navMenu.findItem(R.id.live_hub)?.isVisible = liveHubVisible

        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        val navController = navHost?.navController ?: return
        when {
            currentDestinationId == R.id.movies && !supportsMovies -> {
                navController.navigate(R.id.tv_shows)
            }

            currentDestinationId == R.id.tv_shows && !supportsTvShows -> {
                navController.navigate(R.id.home)
            }

            currentDestinationId == R.id.live_hub && !liveHubVisible -> {
                navController.navigate(R.id.home)
            }
        }
    }

    private fun isTopLevelProviderDestination(destinationId: Int?): Boolean {
        return destinationId in setOf(
            R.id.search,
            R.id.home,
            R.id.live_hub,
            R.id.movies,
            R.id.tv_shows,
            R.id.favorites,
            R.id.downloads,
            R.id.settings,
        )
    }

    private fun navigateToProviderHome(navController: androidx.navigation.NavController) {
        if (!navController.popBackStack(R.id.home, false)) {
            navController.navigate(
                R.id.home,
                null,
                navOptions {
                    launchSingleTop = true
                    popUpTo(R.id.providers) {
                        inclusive = true
                    }
                }
            )
        }
    }

    private fun closeTask() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finishAffinity()
        }
    }

    private suspend fun requestResolverPayload(wsUrl: String, token: String): ResolverPayload? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(RESOLVER_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val request = Request.Builder()
                        .url(wsUrl)
                        .build()

                    val socket =
                        resolverWebSocketClient.newWebSocket(request, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                Log.d("ResolverWS", "Connected -> requesting URL")
                                webSocket.send("resolve:$token")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                when {
                                    text.startsWith("payload:") -> {
                                        val payload = text.substringAfter("payload:").trim()
                                        val parsed = runCatching {
                                            val json = JSONObject(payload)
                                            ResolverPayload(
                                                url = json.optString("url"),
                                            )
                                        }.getOrNull()

                                        if (continuation.isActive) {
                                            continuation.resume(
                                                parsed?.takeUnless {
                                                    it.url.isBlank() || it.url.equals("null", ignoreCase = true)
                                                }
                                            )
                                        }
                                        webSocket.close(1000, null)
                                    }

                                    text.startsWith("url:") -> {
                                        val url = text.substringAfter("url:").trim()
                                        if (continuation.isActive) {
                                            continuation.resume(
                                                url.takeUnless {
                                                    it.isEmpty() || it.equals("null", ignoreCase = true)
                                                }?.let { ResolverPayload(url = it) }
                                            )
                                        }
                                        webSocket.close(1000, null)
                                    }

                                    text.startsWith("error:") -> {
                                        Log.e("ResolverWS", "Resolver returned error: $text")
                                        if (continuation.isActive) {
                                            continuation.resume(null)
                                        }
                                        webSocket.close(1000, null)
                                    }
                                }
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                if (!continuation.isActive) {
                                    Log.d("ResolverWS", "WS resolve cancelled or timed out")
                                    return
                                }
                                Log.e("ResolverWS", "WS resolve failed", t)
                                continuation.resume(null)
                            }
                        })

                    continuation.invokeOnCancellation {
                        socket.cancel()
                    }
                }
            }
        }

    private suspend fun sendWebSocketDone(wsUrl: String, token: String, cookies: String?) {
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(RESOLVER_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val request = Request.Builder()
                        .url(wsUrl)
                        .build()

                    val socket =
                        resolverWebSocketClient.newWebSocket(request, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                Log.d("ResolverWS", "Connected -> sending DONE")
                                val encodedCookies = cookies
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        Base64.getEncoder().encodeToString(
                                            it.toByteArray(Charsets.UTF_8)
                                        )
                                    }
                                val message = if (encodedCookies.isNullOrBlank()) {
                                    "done:$token"
                                } else {
                                    "done:$token:$encodedCookies"
                                }
                                webSocket.send(message)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (text == "ack:$token" && continuation.isActive) {
                                    continuation.resume(Unit)
                                    webSocket.close(1000, null)
                                }
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                if (!continuation.isActive) {
                                    Log.d("ResolverWS", "WS done cancelled or timed out")
                                    return
                                }
                                Log.e("ResolverWS", "WS failed", t)
                                continuation.resume(Unit)
                            }
                        })

                    continuation.invokeOnCancellation {
                        socket.cancel()
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent): Boolean {
        if (intent.getBooleanExtra("open_downloads", false)) {
            // Only honor when the Intent targets this app (notification PendingIntent).
            // External apps cannot set component on our exported Activity via implicit
            // extras alone — still require our package (SEC-DL-05).
            val targetedUs = intent.component?.packageName == packageName ||
                intent.`package` == packageName ||
                callingPackage == null ||
                callingPackage == packageName
            if (!targetedUs) {
                Log.w("MainMobile", "Ignoring open_downloads from foreign caller=$callingPackage")
                return true
            }
            val navHost =
                supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val navController = navHost?.navController
            if (navController != null && navController.currentDestination?.id != R.id.downloads) {
                navController.navigate(R.id.downloads)
            }
            return true
        }
        if (intent.getBooleanExtra("open_live", false)) {
            // Kids Fortress: redirect before Live Hub navigation (KF-009).
            if (!com.betterstreamflix.app.kids.KidsFortress.liveHubAllowed()) {
                val navHost =
                    supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                val navController = navHost?.navController
                if (navController != null && navController.currentDestination?.id != R.id.home) {
                    navController.navigate(R.id.home)
                }
                return true
            }
            val navHost =
                supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val navController = navHost?.navController
            if (navController != null && navController.currentDestination?.id != R.id.live_hub) {
                navController.navigate(R.id.live_hub)
            }
            return true
        }
        if (intent.getBooleanExtra("open_search", false)) {
            val targetedUs = intent.component?.packageName == packageName ||
                intent.`package` == packageName ||
                callingPackage == null ||
                callingPackage == packageName
            if (!targetedUs) {
                Log.w("MainMobile", "Ignoring open_search from foreign caller=$callingPackage")
                return true
            }
            val navHost =
                supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val navController = navHost?.navController
            if (navController != null && navController.currentDestination?.id != R.id.search) {
                navController.navigate(R.id.search)
            }
            return true
        }
        if (intent.getBooleanExtra("open_favorites", false)) {
            val navHost =
                supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val navController = navHost?.navController
            if (navController != null && navController.currentDestination?.id != R.id.favorites) {
                navController.navigate(R.id.favorites)
            }
            return true
        }

        val data = intent.data ?: return false

        if (com.betterstreamflix.app.tvlauncher.TvWatchDeepLinkRouter.handle(this, data)) {
            return true
        }

        val widgetTarget = com.betterstreamflix.app.widgets.WidgetDeepLinks.parse(data)
        if (widgetTarget != null) {
            return com.betterstreamflix.app.widgets.WidgetDeepLinkRouter.handle(this, widgetTarget)
        }

        if ((data.scheme == "streamflix" || data.scheme == "betterstreamflix") &&
            data.host.equals("resolve", ignoreCase = true)
        ) {
            val target = com.betterstreamflix.app.providers.SerienStreamResolveLink.parse(
                data.toString(),
                UserPreferences.bypassWsAdvertisedHost,
            ) ?: return false
            if (!BypassWebSocketEndpointHelper.isAllowedPeerWs(target.ws)) {
                Log.w("ResolverWS", "Rejected resolve deep link with disallowed ws=${target.ws}")
                return true
            }
            Log.d("ResolverWS", "WS: ${target.ws}")
            resolve(target.ws, target.token)
            return true
        }

        val shareToken = com.betterstreamflix.app.sync.SharedWatchlistLinks.parseToken(data)
        if (shareToken != null) {
            com.betterstreamflix.app.sync.SharedWatchlistUi.handleIncomingToken(this, shareToken)
            return true
        }

        val partyToken = com.betterstreamflix.app.sync.WatchPartyLinks.parseToken(data)
        if (partyToken != null) {
            com.betterstreamflix.app.sync.WatchPartyUi.handleIncomingToken(this, partyToken)
            return true
        }

        val packPayload = com.betterstreamflix.app.download.DownloadPackLinks.parse(data)
        if (packPayload != null) {
            com.betterstreamflix.app.download.DownloadPackUi.handleIncoming(this, packPayload)
            return true
        }

        val profilePayload = com.betterstreamflix.app.profiletransfer.ProfileTransferLinks.parse(data)
        if (profilePayload != null) {
            com.betterstreamflix.app.profiletransfer.ProfileTransferUi.handleIncoming(this, profilePayload)
            return true
        }

        val castToken = com.betterstreamflix.app.sync.CastRoomLinks.parseToken(data)
        if (castToken != null) {
            com.betterstreamflix.app.sync.CastRoomUi.handleIncomingToken(this, castToken)
            return true
        }

        val handoffRaw = data.toString()
        if (com.betterstreamflix.app.sync.SessionHandoffLinks.parseCode(handoffRaw) != null ||
            com.betterstreamflix.app.sync.SessionHandoffLinks.parseToken(handoffRaw) != null
        ) {
            com.betterstreamflix.app.sync.SessionHandoffUi.handleIncoming(this, handoffRaw)
            return true
        }

        val remoteFind = com.betterstreamflix.app.remotefind.RemoteFindLinks.parse(data)
        if (remoteFind != null) {
            com.betterstreamflix.app.remotefind.RemoteFindUi.handleIncomingTarget(this, remoteFind)
            return true
        }

        val castHandoffCode = com.betterstreamflix.app.sync.CastHandoffLinks.parseShortCode(data)
        if (castHandoffCode != null) {
            com.betterstreamflix.app.sync.CastHandoffUi.handleIncomingCode(this, castHandoffCode)
            return true
        }

        return false
    }

    private fun resolve(ws: String, token: String) {
        if (!BypassWebSocketEndpointHelper.isAllowedPeerWs(ws)) {
            Log.w("ResolverWS", "Refusing resolve() for disallowed ws peer")
            return
        }
        pendingWs = ws
        pendingToken = token
        pendingPayloadBound = false

        lifecycleScope.launch {
            val payload = requestResolverPayload(ws, token)
            if (payload == null) {
                clearResolverState()
                showResolverConnectionErrorDialog(ws, token)
                return@launch
            }
            // Never load attacker payload URLs in Bypass WebView (SEC-DL-01 / WV-01).
            if (!isAllowedResolverPayloadUrl(payload.url)) {
                Log.w("ResolverWS", "Rejected resolver payload URL: ${payload.url}")
                clearResolverState()
                showResolverConnectionErrorDialog(ws, token)
                return@launch
            }
            // Bind cookie return to this successful resolve pair only.
            if (pendingWs == ws && pendingToken == token) {
                pendingPayloadBound = true
            } else {
                Log.w("ResolverWS", "Pending resolve pair changed; refusing payload bind")
                clearResolverState()
                return@launch
            }

            bypassWebViewLauncher.launch(
                Intent(this@MainMobileActivity, BypassWebViewActivity::class.java)
                    .putExtra(BypassWebViewActivity.EXTRA_URL, payload.url)
            )
        }
    }

    private fun isAllowedResolverPayloadUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (SerienStreamProvider.isSerienStreamHost(url)) return true
        if (KinoGerProvider.isKinoGerHost(url)) return true
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty().lowercase() }
            .getOrDefault("")
        return host == "challenges.cloudflare.com" ||
            host.endsWith(".challenge-platform.com") ||
            host == "check.ddos-guard.net" ||
            host.endsWith(".ddos-guard.net")
    }

    private fun showResolverConnectionErrorDialog(ws: String, token: String) {
        if (isFinishing || isDestroyed) return

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        val glass = com.betterstreamflix.app.utils.ExpDialogChrome.buildGlassMessage(
                this,
                getString(R.string.settings_resolver_connection_error),
            )
        builder.setView(glass.root)
        builder
            .setTitle(R.string.app_name)
            .setPositiveButton(R.string.settings_resolver_retry) { _, _ ->
                resolve(ws, token)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                clearResolverState()
            }
            .setOnCancelListener {
                clearResolverState()
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    com.betterstreamflix.app.utils.ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                }
                dialog.show()
            }
    }

    private fun showPostBypassCloseDialog() {
        if (isFinishing || isDestroyed) return

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        val glass = com.betterstreamflix.app.utils.ExpDialogChrome.buildGlassMessage(
                this,
                getString(R.string.settings_resolver_bypass_done),
            )
        builder.setView(glass.root)
        builder
            .setTitle(R.string.app_name)
            .setPositiveButton(R.string.settings_resolver_close_app) { _, _ ->
                closeTask()
            }
            .setNegativeButton(R.string.settings_resolver_keep_open, null)
            .setOnCancelListener(null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    com.betterstreamflix.app.utils.ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                }
                dialog.show()
            }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        (getCurrentFragment() as? PlayerMobileFragment)?.onUserLeaveHint()
    }

    fun updateImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (UserPreferences.immersiveMode) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private var expNavHidden = false

    /** Slides the floating nav pill off-screen while the user scrolls down. */
    fun setExperimentalNavHidden(hidden: Boolean) {
        val pill = binding.root.findViewById<View>(R.id.bv_main_nav) ?: return
        if (expNavHidden == hidden) return
        expNavHidden = hidden
        val density = resources.displayMetrics.density
        val dy = if (hidden) pill.height + 40f * density else 0f
        val alpha = if (hidden) 0f else 1f
        pill.animate().cancel()
        pill.animate()
            .translationY(dy)
            .alpha(alpha)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        binding.root.findViewById<View>(R.id.cast_mini_controller)?.let { mini ->
            if (mini.visibility != View.VISIBLE) return@let
            mini.animate().cancel()
            mini.animate()
                .translationY(dy)
                .setDuration(220)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun applyExperimentalNavigationChrome() {
        binding.root.findViewById<View>(R.id.bv_main_nav)?.clipToOutline = true
        val surface = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurface, "exp surface",
        )
        val navSurface = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceContainer, "exp nav",
        )
        val primary = MaterialColors.getColor(
            this,
            androidx.appcompat.R.attr.colorPrimary,
            getColor(R.color.m3_primary),
        )
        val onVariant = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            getColor(R.color.m3_on_surface_variant),
        )
        val navColors = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(primary, onVariant),
        )
        binding.bnvMain.itemIconTintList = navColors
        binding.bnvMain.itemTextColor = navColors
        binding.btnMainSearch.clipToOutline = true
        binding.btnMainSearch.imageTintList = ColorStateList.valueOf(primary)
        binding.btnMainSearch.setBackgroundResource(R.drawable.bg_detail_icon_chip)
        binding.btnMainSearch.elevation = 6f * resources.displayMetrics.density
        binding.bnvMain.post {
            val menuView = binding.bnvMain.getChildAt(0) as? android.view.ViewGroup ?: return@post
            with(com.betterstreamflix.app.utils.ExpPressEffects) {
                for (i in 0 until menuView.childCount) {
                    menuView.getChildAt(i)?.applyExpPress()
                }
            }
        }
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = surface
            window.navigationBarColor = navSurface
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun applyThemeNavigationChrome() {
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        val navColors = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(
                palette.mobileNavActive,
                palette.mobileNavInactive,
            )
        )

        binding.bnvMain.setBackgroundColor(palette.mobileNavBackground)
        binding.bnvMain.itemIconTintList = navColors
        binding.bnvMain.itemTextColor = navColors

        applySystemBarColors()

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarColors() {
        val systemBar = ThemeManager.palette(UserPreferences.selectedTheme).systemBar
        window.statusBarColor = systemBar
        window.navigationBarColor = systemBar
    }
}
