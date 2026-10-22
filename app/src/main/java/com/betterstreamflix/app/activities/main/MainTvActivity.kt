package com.betterstreamflix.app.activities.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.bumptech.glide.Glide
import com.tanasi.navigation.widget.setupWithNavController
import com.betterstreamflix.app.BuildConfig
import com.betterstreamflix.app.R
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.databinding.ActivityMainTvBinding
import com.betterstreamflix.app.databinding.ContentHeaderMenuMainTvBinding
import com.betterstreamflix.app.fragments.player.PlayerTvFragment
import com.betterstreamflix.app.live.ChannelPinStripController
import com.betterstreamflix.app.player.PlaybackLifecycleGuard
import com.betterstreamflix.app.player.PlayerCleanExit
import com.betterstreamflix.app.ui.TrailerPlaybackController
import com.betterstreamflix.app.ui.UpdateAppTvDialog
import com.betterstreamflix.app.providers.IptvProvider
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.providers.AnimeOnlineNinjaProvider
import com.betterstreamflix.app.providers.Cine24hProvider
import com.betterstreamflix.app.providers.FilmyOnlineCcProvider
import com.betterstreamflix.app.providers.FrenchAnimeProvider
import com.betterstreamflix.app.providers.GuardaSerieProvider
import com.betterstreamflix.app.providers.RidomoviesProvider
import com.betterstreamflix.app.providers.SoloLatinoProvider
import com.betterstreamflix.app.providers.ZaluknijProvider
import com.betterstreamflix.app.cast.CastPlaybackHub
import com.betterstreamflix.app.utils.AccessibilityPreferences
import com.betterstreamflix.app.utils.ThemeManager
import com.betterstreamflix.app.utils.UserPreferences
import com.betterstreamflix.app.utils.getCurrentFragment
import kotlinx.coroutines.launch

class MainTvActivity : FragmentActivity() {

    private var _binding: ActivityMainTvBinding? = null
    private val binding get() = _binding!!

    private var channelPinStripController: ChannelPinStripController? = null

    private val viewModel by viewModels<MainViewModel>()

    private lateinit var updateAppDialog: UpdateAppTvDialog

    private var lastNavHeaderLogoUrl: Any? = null
    private var castSessionStateListener: ((Boolean) -> Unit)? = null
    /** Tracks prior Nav destination so Clean Exit can restore focus after player. */
    private var lastNavDestinationId: Int? = null

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_settings"
        const val EXTRA_SETTINGS_SCREEN = "settings_screen"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        // LD-034: density hook — language + fontScale only. Compact Leanback is not
        // applied via densityDpi (LD-027 superseded by LeanbackDensityPolicy + theme overlay).
        val withLanguageAndText = AccessibilityPreferences.wrapAfterLanguage(newBase)
        super.attachBaseContext(AccessibilityPreferences.wrapLeanbackDensity(withLanguageAndText))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Il setup delle preferenze è già avvenuto in BetterStreamflixApp
        setTheme(ThemeManager.tvThemeRes(UserPreferences.selectedTheme))
        // LD-027 / LD-034: resolve Compact/Comfort early and always apply Compact overlay
        // when Compact (stroke + documented dimen contract). Shelf/Ambient heights stay
        // runtime via LeanbackDensityPolicy — layout @dimen refs are not theme-item remappable.
        val leanbackDensity = com.betterstreamflix.app.ui.LeanbackDensityPolicy.mode()
        if (leanbackDensity == com.betterstreamflix.app.ui.LeanbackDensityPolicy.Mode.COMPACT) {
            theme.applyStyle(R.style.AppTheme_Tv_LeanbackCompactOverlay, true)
        }
        AccessibilityPreferences.applyHighContrastOverlay(this, mobile = false)

        super.onCreate(savedInstanceState)

        // Finish a provider switch after CLEAR_TASK restart (Room reset deferred).
        UserPreferences.consumePendingProviderSwitch()
        UserPreferences.clearInvalidCurrentProvider()

        if (UserPreferences.castEnabled) {
            CastPlaybackHub.ensureCastContext(this)
        }
        
        // Defer heavy provider native/WebView setup — eager Cronet/WebView on Fire Stick
        // 4K (1st gen / Fire OS 6) was killing the process right after the splash overlay.
        bindingRootAndChrome()
        deferProviderInits()
        setupCastStatusStrip()
        binding.navMain.onRequestContentFocus = { requestBrowseContentFocus() }

        binding.ivSplashOverlay.animate()
            .alpha(0f)
            .setDuration(800)
            .setStartDelay(400)
            .withEndAction {
                val splash = _binding?.ivSplashOverlay ?: return@withEndAction
                val focused = window?.currentFocus
                splash.visibility = View.GONE
                // SS-B10-09: defer content focus while PIN gate is up.
                if (com.betterstreamflix.app.profiles.ProfileUnlockGate.requiresGateForActive()) {
                    return@withEndAction
                }
                if (focused == null || focused == splash || focused == _binding?.navMainFragment) {
                    val host = supportFragmentManager
                        .findFragmentById(binding.navMainFragment.id) as? androidx.navigation.fragment.NavHostFragment
                    val home = host?.childFragmentManager?.primaryNavigationFragment
                        as? com.betterstreamflix.app.fragments.home.HomeTvFragment
                    if (home != null) {
                        home.requestContentFocus()
                    } else {
                        _binding?.navMain?.requestFocus()
                    }
                }
            }

        val navHostFragment = this.supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as? NavHostFragment
        if (navHostFragment == null) {
            android.util.Log.e("MainTvActivity", "NavHostFragment missing — finishing")
            finish()
            return
        }
        val navController = navHostFragment.navController
        com.betterstreamflix.app.utils.SentryBootstrap.trackNavigation(navController)

        adjustLayoutDelta(null, null)

        if (BuildConfig.APP_LAYOUT == "mobile" || (BuildConfig.APP_LAYOUT != "tv" && !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))) {
            finish()
            startActivity(Intent(this, MainMobileActivity::class.java))
            return
        }

        if (savedInstanceState == null) {
            val openSettings = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true
            if (!openSettings) {
                UserPreferences.currentProvider?.let {
                    navController.navigate(R.id.home)
                }
            }
        }
        binding.root.post { showProfileUnlockGateIfNeeded(navHostFragment) }

        binding.navMain.setupWithNavController(navController)
        updateNavigationVisibility()

        channelPinStripController = ChannelPinStripController(this, binding.channelPinStrip).also {
            it.attach()
            it.onDestinationChanged(navController.currentDestination?.id)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.navMainFragment.isFocusedByDefault = true
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val leavingPlayer = lastNavDestinationId == R.id.player && destination.id != R.id.player
            if (destination.id == R.id.player &&
                com.betterstreamflix.app.kids.KidsBedtime.shouldBlockEnteringPlayer()
            ) {
                binding.root.post {
                    runCatching {
                        if (navController.currentDestination?.id == R.id.player) {
                            navController.popBackStack()
                        }
                    }
                    com.betterstreamflix.app.kids.KidsBedtimeUi.blockPlayerEntry(this@MainTvActivity)
                }
            }
            if (destination.id != R.id.player) {
                PlaybackLifecycleGuard.stopActivePlayback()
                if (leavingPlayer) {
                    PlayerCleanExit.noteLeavingPlayer(
                        returningToHome = destination.id == R.id.home,
                    )
                    runCatching { TrailerPlaybackController.silenceAllActive() }
                }
            }
            channelPinStripController?.onDestinationChanged(destination.id)
            binding.navMain.headerView?.apply {
                val header = ContentHeaderMenuMainTvBinding.bind(this)

                val logoLoad = UserPreferences.currentProvider?.logo?.takeIf { it.isNotEmpty() }
                    ?: R.drawable.ic_provider_default_logo
                if (logoLoad != lastNavHeaderLogoUrl) {
                    lastNavHeaderLogoUrl = logoLoad
                    Glide.with(context)
                        .load(logoLoad)
                        .error(R.drawable.ic_provider_default_logo)
                        .into(header.ivNavigationHeaderIcon)
                }
                header.tvNavigationHeaderTitle.text = UserPreferences.currentProvider?.name
                header.tvNavigationHeaderSubtitle.text = getString(R.string.main_menu_change_provider)
                val palette = navPalette()
                header.tvNavigationHeaderTitle.setTextColor(palette.tvHeaderPrimary)
                header.tvNavigationHeaderSubtitle.setTextColor(palette.tvHeaderSecondary)
                setBackgroundColor(palette.tvNavBackground)

                setOnOpenListener {
                    header.tvNavigationHeaderTitle.visibility = View.VISIBLE
                    header.tvNavigationHeaderSubtitle.visibility = View.VISIBLE
                }
                setOnCloseListener {
                    header.tvNavigationHeaderTitle.visibility = View.GONE
                    header.tvNavigationHeaderSubtitle.visibility = View.GONE
                }

                setOnClickListener {
                    // Navigazione manuale per evitare dipendenza da Safe Args Directions non generate
                    navController.navigate(R.id.providers)
                }
            }

            when (destination.id) {
                R.id.search, R.id.home, R.id.live_hub, R.id.movies, R.id.tv_shows, R.id.favorites, R.id.downloads, R.id.settings -> {
                    binding.navMain.visibility = View.VISIBLE
                    updateNavigationVisibility()
                    if (leavingPlayer) {
                        // Clean Exit: land DPAD on exact Home tile (or content for other tabs).
                        binding.root.post {
                            if (destination.id == R.id.home) {
                                focusHomeAfterCleanExit()
                            } else {
                                requestBrowseContentFocus()
                            }
                        }
                    }
                }
                else -> {
                    val navHadFocus = binding.navMain.hasFocus()
                    binding.navMain.visibility = View.GONE
                    if (navHadFocus || leavingPlayer) {
                        // Destination-specific handoff — never leave focus on the NavHost container.
                        binding.navMainFragment.post {
                            handoffFocusToDestination(destination.id)
                        }
                    }
                }
            }
            refreshCastStatusStrip(playerVisible = destination.id == R.id.player)
            lastNavDestinationId = destination.id
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        updateAppDialog = UpdateAppTvDialog(this@MainTvActivity, state.newReleases).also {
                            it.setOnUpdateClickListener { _ ->
                                if (!it.isLoading) viewModel.downloadUpdate(this@MainTvActivity, state.asset)
                            }
                            it.show()
                        }
                    }
                    MainViewModel.State.DownloadingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainTvActivity, state.apk)
                        if (::updateAppDialog.isInitialized) updateAppDialog.hide()
                    }
                    MainViewModel.State.InstallingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        com.betterstreamflix.app.utils.ExpDialogChrome.notify(
                            this@MainTvActivity,
                            state.error.message ?: "Update failed",
                            R.string.update_title,
                        )
                    }
                    else -> {}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (navController.currentDestination?.id) {
                    R.id.home -> {
                        val host = navHostFragment.childFragmentManager.primaryNavigationFragment
                            as? com.betterstreamflix.app.fragments.home.HomeTvFragment
                        if (host?.isAmbientActive() == true) {
                            host.onAmbientUserActivity()
                            return
                        }
                        if (binding.navMain.hasFocus()) {
                            finish()
                        } else {
                            val onFeatured = host?.isFeaturedChromeFocused() == true
                            if (!onFeatured && host?.requestContentFocus() == true) {
                                // first Back → Featured Watch
                            } else {
                                binding.navMain.requestFocus()
                            }
                        }
                    }
                    R.id.settings -> {
                        if (!navController.navigateUp()) {
                            navigateToProviderHome(navController)
                        }
                        binding.root.post { focusHomeContentOrNav() }
                    }
                    R.id.search, R.id.live_hub, R.id.movies, R.id.tv_shows, R.id.favorites, R.id.downloads -> {
                        navigateToProviderHome(navController)
                        binding.root.post { focusHomeContentOrNav() }
                    }
                    R.id.support -> {
                        // FA-038: same focus restore as Settings / browse tabs.
                        if (!navController.navigateUp()) {
                            navigateToProviderHome(navController)
                        }
                        binding.root.post { focusHomeContentOrNav() }
                    }
                    else -> {
                        val playerFrag = getCurrentFragment() as? PlayerTvFragment
                        val handled = playerFrag?.onBackPressed() ?: false
                        if (handled) return
                        if (playerFrag != null) {
                            playerFrag.forceStopPlayback()
                        } else {
                            PlaybackLifecycleGuard.stopActivePlayback()
                        }
                        if (!navController.navigateUp()) finish()
                    }
                }
            }
        })

        com.betterstreamflix.app.onboarding.OnboardingController.maybeShow(this, isTv = true)
        com.betterstreamflix.app.support.SupportStartupController.schedule(this, isTv = true)

        handleOpenDownloadsIntent(intent)
        handleOpenLiveIntent(intent)
        handleOpenFavoritesIntent(intent)
        handleOpenSettingsIntent(intent)
        handleWidgetDeepLink(intent)
        handleSharedWatchlistIntent(intent)
        handleWatchPartyIntent(intent)
        handleDownloadPackIntent(intent)
        handleProfileTransferIntent(intent)
        handleCastRoomIntent(intent)
        handleSessionHandoffIntent(intent)
        handleCastHandoffIntent(intent)
        handleWatchDeepLink(intent)
        com.betterstreamflix.app.tvlauncher.TvLauncherSync.start(this)
        registerCastRoomHostConsumer()
        com.betterstreamflix.app.remotefind.RemoteFindPadBridge.attach(this)
        com.betterstreamflix.app.remotefind.RemoteFindNavBridge.attach(this)
        com.betterstreamflix.app.remotefind.RemoteFindHost.restoreIfNeeded(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenDownloadsIntent(intent)
        handleOpenLiveIntent(intent)
        handleOpenFavoritesIntent(intent)
        handleOpenSettingsIntent(intent)
        handleWidgetDeepLink(intent)
        handleSharedWatchlistIntent(intent)
        handleWatchPartyIntent(intent)
        handleDownloadPackIntent(intent)
        handleProfileTransferIntent(intent)
        handleCastRoomIntent(intent)
        handleSessionHandoffIntent(intent)
        handleCastHandoffIntent(intent)
        handleWatchDeepLink(intent)
    }

    /** Notification / deep-link tap → Downloads tab (parity with [MainMobileActivity]). */
    private fun handleOpenDownloadsIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_downloads", false) != true) return
        // Only honor when targeted at this app (SEC-DL-05).
        val targetedUs = intent.component?.packageName == packageName ||
            intent.`package` == packageName ||
            callingPackage == null ||
            callingPackage == packageName
        if (!targetedUs) {
            android.util.Log.w("MainTv", "Ignoring open_downloads from foreign caller=$callingPackage")
            return
        }
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                ?: return
        val navController = navHost.navController
        if (navController.currentDestination?.id != R.id.downloads) {
            runCatching { navController.navigate(R.id.downloads) }
        }
    }

    /** Notification / deep-link tap → Live hub (parity with [MainMobileActivity]). */
    private fun handleOpenLiveIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_live", false) != true) return
        // SS-B10-04: same targeting check as open_downloads + Kids Fortress gate.
        val targetedUs = intent.component?.packageName == packageName ||
            intent.`package` == packageName ||
            callingPackage == null ||
            callingPackage == packageName
        if (!targetedUs) {
            android.util.Log.w("MainTv", "Ignoring open_live from foreign caller=$callingPackage")
            return
        }
        if (!com.betterstreamflix.app.kids.KidsFortress.liveHubAllowed()) {
            android.util.Log.w("MainTv", "Ignoring open_live — Kids Fortress blocks Live Hub")
            return
        }
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                ?: return
        val navController = navHost.navController
        // Kids Fortress: redirect before Live Hub navigation (KF-009).
        if (!com.betterstreamflix.app.kids.KidsFortress.liveHubAllowed()) {
            if (navController.currentDestination?.id != R.id.home) {
                runCatching { navController.navigate(R.id.home) }
            }
            return
        }
        if (navController.currentDestination?.id != R.id.live_hub) {
            runCatching { navController.navigate(R.id.live_hub) }
        }
    }

    /** Notification tap → Favorites / My List (watchlist episode alerts). */
    private fun handleOpenFavoritesIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_favorites", false) != true) return
        val targetedUs = intent.component?.packageName == packageName ||
            intent.`package` == packageName ||
            callingPackage == null ||
            callingPackage == packageName
        if (!targetedUs) {
            android.util.Log.w("MainTv", "Ignoring open_favorites from foreign caller=$callingPackage")
            return
        }
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                ?: return
        val navController = navHost.navController
        if (navController.currentDestination?.id != R.id.favorites) {
            runCatching { navController.navigate(R.id.favorites) }
        }
    }

    /** Appearance / density restart lands back on Settings (nested screen via deep link). */
    private fun handleOpenSettingsIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) != true) return
        val screen = intent.getStringExtra(EXTRA_SETTINGS_SCREEN)
        if (!screen.isNullOrBlank()) {
            com.betterstreamflix.app.fragments.settings.SettingsDeepLink.pendingScreenKey = screen
        }
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                ?: return
        val navController = navHost.navController
        if (navController.currentDestination?.id != R.id.settings) {
            runCatching { navController.navigate(R.id.settings) }
        }
        // Consume so rotation / onNewIntent does not re-navigate forever.
        intent.removeExtra(EXTRA_OPEN_SETTINGS)
        intent.removeExtra(EXTRA_SETTINGS_SCREEN)
    }

    /** Widget / `betterstreamflix://live` deep links (parity with Mobile). */
    private fun handleWidgetDeepLink(intent: Intent?) {
        val target = com.betterstreamflix.app.widgets.WidgetDeepLinks.parse(intent?.data)
            ?: return
        com.betterstreamflix.app.widgets.WidgetDeepLinkRouter.handle(this, target)
    }

    private fun handleSharedWatchlistIntent(intent: Intent?) {
        val token = com.betterstreamflix.app.sync.SharedWatchlistLinks.parseToken(intent?.data)
            ?: return
        com.betterstreamflix.app.sync.SharedWatchlistUi.handleIncomingToken(this, token)
    }

    private fun handleWatchPartyIntent(intent: Intent?) {
        val token = com.betterstreamflix.app.sync.WatchPartyLinks.parseToken(intent?.data)
            ?: return
        com.betterstreamflix.app.sync.WatchPartyUi.handleIncomingToken(this, token)
    }

    private fun handleDownloadPackIntent(intent: Intent?) {
        val payload = com.betterstreamflix.app.download.DownloadPackLinks.parse(intent?.data)
            ?: return
        com.betterstreamflix.app.download.DownloadPackUi.handleIncoming(this, payload)
    }

    private fun handleProfileTransferIntent(intent: Intent?) {
        val payload = com.betterstreamflix.app.profiletransfer.ProfileTransferLinks.parse(intent?.data)
            ?: return
        com.betterstreamflix.app.profiletransfer.ProfileTransferUi.handleIncoming(this, payload)
    }

    private fun handleCastRoomIntent(intent: Intent?) {
        val token = com.betterstreamflix.app.sync.CastRoomLinks.parseToken(intent?.data)
            ?: return
        com.betterstreamflix.app.sync.CastRoomUi.handleIncomingToken(this, token)
    }

    private fun handleSessionHandoffIntent(intent: Intent?) {
        val raw = intent?.data?.toString() ?: return
        if (com.betterstreamflix.app.sync.SessionHandoffLinks.parseCode(raw) == null &&
            com.betterstreamflix.app.sync.SessionHandoffLinks.parseToken(raw) == null
        ) {
            return
        }
        com.betterstreamflix.app.sync.SessionHandoffUi.handleIncoming(this, raw)
    }

    private fun handleCastHandoffIntent(intent: Intent?) {
        val code = com.betterstreamflix.app.sync.CastHandoffLinks.parseShortCode(intent?.data)
            ?: return
        com.betterstreamflix.app.sync.CastHandoffUi.handleIncomingCode(this, code)
    }

    private fun registerCastRoomHostConsumer() {
        com.betterstreamflix.app.sync.CastRoomUi.registerHostConsumer(
            object : com.betterstreamflix.app.sync.CastRoomUi.HostConsumer {
                override fun onCastRoomPlayItem(
                    item: com.betterstreamflix.app.sync.CastRoomItemPayload,
                    preferredServerName: String?,
                ) {
                    com.betterstreamflix.app.sync.CastRoomUi.navigateHostToItem(this@MainTvActivity, item)
                }

                override fun onCastRoomSetPlaying(playing: Boolean) {
                    val navHost =
                        supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                    val player = navHost?.childFragmentManager?.primaryNavigationFragment
                        as? com.betterstreamflix.app.fragments.player.PlayerTvFragment
                    player?.castRoomApplyRemotePlaying(playing)
                }

                override fun onCastRoomSeek(positionMs: Long) {
                    runOnUiThread {
                        val navHost =
                            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                        val player = navHost?.childFragmentManager?.primaryNavigationFragment
                            as? com.betterstreamflix.app.fragments.player.PlayerTvFragment
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
                            this@MainTvActivity,
                            R.string.cast_room_ended,
                        )
                    }
                }
            },
        )
    }

    /** Phone keyboard → open Search on the TV and run the query. */
    private fun applySidekickKeyboardQuery(text: String) {
        val query = com.betterstreamflix.app.sync.TvSidekick.sanitizeKeyboard(text) ?: return
        val navController =
            (supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment)
                ?.navController
        runCatching { navController?.navigate(R.id.search) }
        binding.root.post {
            val frag = getCurrentFragment()
                as? com.betterstreamflix.app.fragments.search.SearchTvFragment
            if (frag != null) {
                frag.applySidekickQuery(query)
            } else {
                binding.root.postDelayed({
                    (getCurrentFragment() as? com.betterstreamflix.app.fragments.search.SearchTvFragment)
                        ?.applySidekickQuery(query)
                }, 350L)
            }
            com.betterstreamflix.app.utils.ExpDialogChrome.notify(
                this,
                getString(R.string.tv_sidekick_keyboard_applied, query),
            )
        }
    }

    /** Watch Next / Preview Channel card → resume or open details. */
    private fun handleWatchDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.host.equals("home", ignoreCase = true)) return
        // Watch-Party / pack / Cast Room links share the activity; skip Watch Next routing.
        if (com.betterstreamflix.app.sync.WatchPartyLinks.parseToken(data) != null) return
        if (com.betterstreamflix.app.download.DownloadPackLinks.parse(data) != null) return
        if (com.betterstreamflix.app.profiletransfer.ProfileTransferLinks.parse(data) != null) return
        if (com.betterstreamflix.app.sync.CastRoomLinks.parseToken(data) != null) return
        if (com.betterstreamflix.app.sync.SessionHandoffLinks.parseCode(data.toString()) != null) return
        if (com.betterstreamflix.app.sync.SessionHandoffLinks.parseToken(data.toString()) != null) return
        if (com.betterstreamflix.app.sync.CastHandoffLinks.parseShortCode(data) != null) return
        com.betterstreamflix.app.tvlauncher.TvWatchDeepLinkRouter.handle(this, data)
    }

    private fun bindingRootAndChrome() {
        _binding = ActivityMainTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeNavigationChrome()
    }

    private fun deferProviderInits() {
        // Post so the splash/first frame can paint before native libs load.
        window.decorView.post {
            runCatching { AnimeOnlineNinjaProvider.init(this) }
            runCatching { Cine24hProvider.init(this) }
            runCatching { FilmyOnlineCcProvider.init(this) }
            runCatching { FrenchAnimeProvider.init(this) }
            runCatching { ZaluknijProvider.init(this) }
            runCatching { GuardaSerieProvider.init(this) }
            runCatching { SoloLatinoProvider.init(this) }
            runCatching { RidomoviesProvider.init(this) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkUpdate()
        com.betterstreamflix.app.sync.SharedWatchlistUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.WatchPartyUi.tryFlushPending(this)
        com.betterstreamflix.app.download.DownloadPackUi.tryFlushPending(this)
        com.betterstreamflix.app.profiletransfer.ProfileTransferUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.CastRoomUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.CastHandoffUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.SessionHandoffUi.tryFlushPending(this)
        com.betterstreamflix.app.tvlauncher.TvLauncherSync.requestPublish(this)
        val destId = (supportFragmentManager.findFragmentById(binding.navMainFragment.id)
            as? NavHostFragment)?.navController?.currentDestination?.id
        refreshCastStatusStrip(playerVisible = destId == R.id.player)
    }

    override fun onStart() {
        super.onStart()
        com.betterstreamflix.app.profiles.ProfileUnlockGate.markForeground()
        runCatching {
            com.betterstreamflix.app.profiles.ProfileManager.sweepExpiredGuests(this)
        }
        com.betterstreamflix.app.profiles.GuestForegroundSweep.onStart(this)
        val navHostFragment = supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as? NavHostFragment
        if (navHostFragment != null) {
            binding.root.post { showProfileUnlockGateIfNeeded(navHostFragment) }
        }
        binding.root.post {
            com.betterstreamflix.app.kids.KidsBedtimeUi.maybeShowLock(this@MainTvActivity)
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

    override fun onDestroy() {
        com.betterstreamflix.app.remotefind.RemoteFindPadBridge.detach(this)
        com.betterstreamflix.app.remotefind.RemoteFindNavBridge.detach(this)
        if (isFinishing) {
            com.betterstreamflix.app.remotefind.RemoteFindHost.stop()
        } else {
            // Keep session across recreate; sockets restart in onCreate via restoreIfNeeded.
            com.betterstreamflix.app.remotefind.RemoteFindHost.pauseForRecreation()
        }
        com.betterstreamflix.app.sync.CastRoomUi.registerHostConsumer(null)
        castSessionStateListener?.let { CastPlaybackHub.removeSessionStateListener(it) }
        castSessionStateListener = null
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        val host = currentHomeFragment()
        // Non-key paths (touch / mouse) reset idle without exiting active Ambient.
        if (host != null && !host.isAmbientActive()) {
            host.onAmbientIdleClockReset()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val host = currentHomeFragment()
        if (host != null) {
            if (host.onAmbientKeyEvent(event)) return true
            // Any key while Home is visible resets Ambient idle (when inactive).
            if (event.action == KeyEvent.ACTION_DOWN && !host.isAmbientActive()) {
                host.onAmbientUserActivity()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Profile unlock, bedtime lock, update dialog — block Ambient enter.
     * Home loading is checked in [com.betterstreamflix.app.fragments.home.HomeTvFragment].
     */
    fun isAmbientModalBlocking(): Boolean {
        if (::updateAppDialog.isInitialized && updateAppDialog.isShowing) return true
        if (com.betterstreamflix.app.kids.KidsBedtimeUi.isLockDialogShowing()) return true
        if (com.betterstreamflix.app.profiles.ProfileUnlockGate.requiresGateForActive()) {
            return true
        }
        val host = (supportFragmentManager.findFragmentById(R.id.nav_main_fragment)
            as? NavHostFragment)
            ?.childFragmentManager
            ?.primaryNavigationFragment
        if (host?.childFragmentManager?.findFragmentByTag(
                com.betterstreamflix.app.profiles.ProfilePickerDialog.TAG,
            ) != null
        ) {
            return true
        }
        return false
    }

    private fun currentHomeFragment():
        com.betterstreamflix.app.fragments.home.HomeTvFragment? =
        (supportFragmentManager.findFragmentById(R.id.nav_main_fragment)
            as? NavHostFragment)
            ?.childFragmentManager
            ?.primaryNavigationFragment
            as? com.betterstreamflix.app.fragments.home.HomeTvFragment

    private fun showProfileUnlockGateIfNeeded(navHostFragment: NavHostFragment) {
        if (isFinishing || isDestroyed) return
        if (!com.betterstreamflix.app.profiles.ProfileUnlockGate.requiresGateForActive()) {
            com.betterstreamflix.app.sync.WatchPartyUi.tryFlushPending(this)
            com.betterstreamflix.app.download.DownloadPackUi.tryFlushPending(this)
            com.betterstreamflix.app.profiletransfer.ProfileTransferUi.tryFlushPending(this)
            com.betterstreamflix.app.sync.CastRoomUi.tryFlushPending(this)
            com.betterstreamflix.app.sync.CastHandoffUi.tryFlushPending(this)
            com.betterstreamflix.app.sync.SessionHandoffUi.tryFlushPending(this)
            return
        }
        val host = navHostFragment.childFragmentManager.primaryNavigationFragment
            ?: navHostFragment
        com.betterstreamflix.app.profiles.ProfileUnlockGate.maybeShow(
            activity = this,
            hostFragmentProvider = { host },
            onUnlocked = {
                com.betterstreamflix.app.sync.WatchPartyUi.tryFlushPending(this)
                com.betterstreamflix.app.download.DownloadPackUi.tryFlushPending(this)
                com.betterstreamflix.app.profiletransfer.ProfileTransferUi.tryFlushPending(this)
                com.betterstreamflix.app.sync.CastHandoffUi.tryFlushPending(this)
        com.betterstreamflix.app.sync.SessionHandoffUi.tryFlushPending(this)
                com.betterstreamflix.app.sync.CastRoomUi.tryFlushPending(this)
                com.betterstreamflix.app.sync.SessionHandoffUi.tryFlushPending(this)
            },
        )
    }

    private fun navPalette(): ThemeManager.Palette =
        ThemeManager.palette(UserPreferences.selectedTheme)

    private fun applyThemeNavigationChrome() {
        val palette = navPalette()
        applySystemBarColors()
        binding.navMain.setBackgroundColor(palette.tvNavBackground)
        binding.navMain.headerView?.let { headerView ->
            headerView.setBackgroundColor(palette.tvNavBackground)
            val header = ContentHeaderMenuMainTvBinding.bind(headerView)
            header.tvNavigationHeaderTitle.setTextColor(palette.tvHeaderPrimary)
            header.tvNavigationHeaderSubtitle.setTextColor(palette.tvHeaderSecondary)
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarColors() {
        val systemBar = navPalette().systemBar
        window.statusBarColor = systemBar
        window.navigationBarColor = systemBar
    }
    
    private fun updateNavigationVisibility() {
        val provider = UserPreferences.currentProvider ?: return
        val supportsMovies = Provider.supportsMovies(provider)
        val supportsTvShows = Provider.supportsTvShows(provider)
        // Age-gate + Kids Fortress (upstream Batch 10 + SS-B10-03 parity).
        val liveHubVisible = !com.betterstreamflix.app.utils.LiveHubCatalog.isAgeGated() &&
            com.betterstreamflix.app.kids.KidsFortress.liveHubAllowed() &&
            com.betterstreamflix.app.utils.LiveHubCatalog.isLiveProvider(provider)
        binding.navMain.menu.findItem(R.id.movies)?.isVisible = supportsMovies
        val tvShowsItem = binding.navMain.menu.findItem(R.id.tv_shows)
        tvShowsItem?.isVisible = supportsTvShows
        tvShowsItem?.title = if (provider is IptvProvider) {
            getString(R.string.main_menu_all_channels)
        } else {
            getString(R.string.main_menu_tv_shows)
        }
        // Parity with Mobile: route Downloads/Live Hub through KidsFortress (KF-013/014).
        binding.navMain.menu.findItem(R.id.downloads)?.isVisible =
            com.betterstreamflix.app.kids.KidsFortress.downloadsNavVisible()
        binding.navMain.menu.findItem(R.id.live_hub)?.isVisible = liveHubVisible

        val navController = (supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment)
            ?.navController ?: return
        when (navController.currentDestination?.id) {
            R.id.movies -> if (!supportsMovies) navController.navigate(R.id.tv_shows)
            R.id.tv_shows -> if (!supportsTvShows) navController.navigate(R.id.home)
            R.id.live_hub -> if (!liveHubVisible) navController.navigate(R.id.home)
        }
    }

    fun adjustLayoutDelta(deltaX: Int?, deltaY: Int?) {
        val uDeltaX = deltaX ?: UserPreferences.paddingX
        val uDeltaY = deltaY ?: UserPreferences.paddingY
        // Overscan padding must not clip focused Leanback zoom / shelf edges.
        // Light extra inset so focus zoom on edge tiles is less likely to clip.
        val focusPad = (4 * resources.displayMetrics.density).toInt()
        binding.root.clipToPadding = false
        binding.root.clipChildren = false
        binding.navMainFragment.clipToPadding = false
        binding.navMainFragment.clipChildren = false
        binding.root.setPadding(
            uDeltaX + focusPad,
            uDeltaY + focusPad,
            uDeltaX + focusPad,
            uDeltaY + focusPad,
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

    private fun focusHomeContentOrNav() {
        val host = supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as? NavHostFragment
        val home = host?.childFragmentManager?.primaryNavigationFragment
            as? com.betterstreamflix.app.fragments.home.HomeTvFragment
        if (home?.requestContentFocus() != true) {
            binding.navMain.requestFocus()
        }
    }

    /** Clean Exit from player → exact remembered Home tile, else Featured / first shelf. */
    private fun focusHomeAfterCleanExit() {
        val host = supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as? NavHostFragment
        val home = host?.childFragmentManager?.primaryNavigationFragment
            as? com.betterstreamflix.app.fragments.home.HomeTvFragment
        if (home?.restoreCleanExitFocus() == true) return
        if (home?.requestContentFocus() == true) return
        binding.navMain.requestFocus()
    }

    /** Side-nav DPAD-RIGHT → content for the current browse destination. */
    private fun requestBrowseContentFocus(): Boolean {
        val frag = getCurrentFragment() ?: return false
        return when (frag) {
            is com.betterstreamflix.app.fragments.home.HomeTvFragment ->
                frag.requestContentFocus()
            is com.betterstreamflix.app.fragments.live.LiveHubTvFragment ->
                frag.requestContentFocus()
            is com.betterstreamflix.app.fragments.movie.MovieTvFragment ->
                frag.requestContentFocus()
            is com.betterstreamflix.app.fragments.tv_show.TvShowTvFragment ->
                frag.requestContentFocus()
            is com.betterstreamflix.app.fragments.season.SeasonTvFragment ->
                frag.requestContentFocus()
            is com.betterstreamflix.app.fragments.player.PlayerTvFragment -> {
                frag.requestPlayerFocus()
                true
            }
            is com.betterstreamflix.app.fragments.search.SearchTvFragment -> {
                // FA-027: first poster, not Leanback grid container.
                val grid = frag.view?.findViewById<View>(R.id.vgv_search)
                grid != null && com.betterstreamflix.app.ui.HomeProfileChip.focusFirstPoster(grid)
            }
            is com.betterstreamflix.app.fragments.movies.MoviesTvFragment -> {
                val grid = frag.view?.findViewById<View>(R.id.vgv_movies)
                grid != null && com.betterstreamflix.app.ui.HomeProfileChip.focusFirstPoster(grid)
            }
            is com.betterstreamflix.app.fragments.tv_shows.TvShowsTvFragment -> {
                val grid = frag.view?.findViewById<View>(R.id.vgv_tv_shows)
                grid != null && com.betterstreamflix.app.ui.HomeProfileChip.focusFirstPoster(grid)
            }
            is com.betterstreamflix.app.fragments.favorites.FavoritesTvFragment -> {
                val grid = frag.view?.findViewById<View>(R.id.rv_favorites)
                grid != null && com.betterstreamflix.app.ui.HomeProfileChip.focusFirstPoster(grid)
            }
            is com.betterstreamflix.app.fragments.downloads.DownloadsTvFragment -> {
                // Offline Studio: restore Clean Exit token; Leanback Edge: first poster, not list root.
                frag.restoreCleanExitFocus() ||
                    run {
                        val grid = frag.view?.findViewById<View>(R.id.rv_downloads)
                        grid != null &&
                            com.betterstreamflix.app.ui.HomeProfileChip.focusFirstPoster(grid)
                    } ||
                    frag.view?.requestFocus() == true
            }
            else -> frag.view?.requestFocus() == true
        }
    }

    private fun handoffFocusToDestination(destinationId: Int) {
        when (destinationId) {
            R.id.player ->
                (getCurrentFragment() as? PlayerTvFragment)?.requestPlayerFocus()
            R.id.movie ->
                (getCurrentFragment() as? com.betterstreamflix.app.fragments.movie.MovieTvFragment)
                    ?.requestContentFocus()
            R.id.tv_show ->
                (getCurrentFragment() as? com.betterstreamflix.app.fragments.tv_show.TvShowTvFragment)
                    ?.requestContentFocus()
            R.id.season ->
                (getCurrentFragment() as? com.betterstreamflix.app.fragments.season.SeasonTvFragment)
                    ?.requestContentFocus()
            else -> requestBrowseContentFocus()
        }
    }

    private fun setupCastStatusStrip() {
        val stop = binding.btnCastStatusStop
        stop.setOnClickListener {
            CastPlaybackHub.stopCasting(this)
            refreshCastStatusStrip(playerVisible = false)
        }
        val listener: (Boolean) -> Unit = {
            val destId = (supportFragmentManager.findFragmentById(binding.navMainFragment.id)
                as? NavHostFragment)?.navController?.currentDestination?.id
            refreshCastStatusStrip(playerVisible = destId == R.id.player)
        }
        castSessionStateListener = listener
        CastPlaybackHub.addSessionStateListener(listener)
        refreshCastStatusStrip(playerVisible = false)
    }

    private fun refreshCastStatusStrip(playerVisible: Boolean) {
        val strip = _binding?.llCastStatusStrip ?: return
        val casting = CastPlaybackHub.isCasting
        // Fire/GMS-safe: if Cast never started, strip stays gone.
        val show = casting && !playerVisible
        strip.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val title = CastPlaybackHub.lastSubtitle?.takeIf { it.isNotBlank() }
            ?: CastPlaybackHub.lastTitle?.takeIf { it.isNotBlank() }
            ?: getString(R.string.player_cast_status_casting)
        binding.tvCastStatusTitle.text = title
    }
}
