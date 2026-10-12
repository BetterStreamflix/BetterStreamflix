package com.dskja.betterstreamflix.activities.main

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
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.databinding.ActivityMainTvBinding
import com.dskja.betterstreamflix.databinding.ContentHeaderMenuMainTvBinding
import com.dskja.betterstreamflix.fragments.player.PlayerTvFragment
import com.dskja.betterstreamflix.live.ChannelPinStripController
import com.dskja.betterstreamflix.player.PlaybackLifecycleGuard
import com.dskja.betterstreamflix.player.PlayerCleanExit
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
import com.dskja.betterstreamflix.ui.UpdateAppTvDialog
import com.dskja.betterstreamflix.providers.IptvProvider
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.providers.AnimeOnlineNinjaProvider
import com.dskja.betterstreamflix.providers.Cine24hProvider
import com.dskja.betterstreamflix.providers.FilmyOnlineCcProvider
import com.dskja.betterstreamflix.providers.FrenchAnimeProvider
import com.dskja.betterstreamflix.providers.GuardaSerieProvider
import com.dskja.betterstreamflix.providers.RidomoviesProvider
import com.dskja.betterstreamflix.providers.SoloLatinoProvider
import com.dskja.betterstreamflix.providers.ZaluknijProvider
import com.dskja.betterstreamflix.cast.CastPlaybackHub
import com.dskja.betterstreamflix.utils.AccessibilityPreferences
import com.dskja.betterstreamflix.utils.ThemeManager
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.getCurrentFragment
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
        val leanbackDensity = com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.mode()
        if (leanbackDensity == com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.Mode.COMPACT) {
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
                if (com.dskja.betterstreamflix.profiles.ProfileUnlockGate.requiresGateForActive()) {
                    return@withEndAction
                }
                if (focused == null || focused == splash || focused == _binding?.navMainFragment) {
                    val host = supportFragmentManager
                        .findFragmentById(binding.navMainFragment.id) as? androidx.navigation.fragment.NavHostFragment
                    val home = host?.childFragmentManager?.primaryNavigationFragment
                        as? com.dskja.betterstreamflix.fragments.home.HomeTvFragment
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
        com.dskja.betterstreamflix.utils.SentryBootstrap.trackNavigation(navController)

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
                com.dskja.betterstreamflix.kids.KidsBedtime.shouldBlockEnteringPlayer()
            ) {
                binding.root.post {
                    runCatching {
                        if (navController.currentDestination?.id == R.id.player) {
                            navController.popBackStack()
                        }
                    }
                    com.dskja.betterstreamflix.kids.KidsBedtimeUi.blockPlayerEntry(this@MainTvActivity)
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
                val palette = ThemeManager.palette(UserPreferences.selectedTheme)
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
                        com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(
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
                            as? com.dskja.betterstreamflix.fragments.home.HomeTvFragment
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

        com.dskja.betterstreamflix.onboarding.OnboardingController.maybeShow(this, isTv = true)
        com.dskja.betterstreamflix.support.SupportStartupController.schedule(this, isTv = true)

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
        com.dskja.betterstreamflix.tvlauncher.TvLauncherSync.start(this)
        registerCastRoomHostConsumer()
        com.dskja.betterstreamflix.remotefind.RemoteFindPadBridge.attach(this)
        com.dskja.betterstreamflix.remotefind.RemoteFindNavBridge.attach(this)
        com.dskja.betterstreamflix.remotefind.RemoteFindHost.restoreIfNeeded(this)
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
        if (!com.dskja.betterstreamflix.kids.KidsFortress.liveHubAllowed()) {
            android.util.Log.w("MainTv", "Ignoring open_live — Kids Fortress blocks Live Hub")
            return
        }
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                ?: return
        val navController = navHost.navController
        // Kids Fortress: redirect before Live Hub navigation (KF-009).
        if (!com.dskja.betterstreamflix.kids.KidsFortress.liveHubAllowed()) {
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
            com.dskja.betterstreamflix.fragments.settings.SettingsDeepLink.pendingScreenKey = screen
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
        val target = com.dskja.betterstreamflix.widgets.WidgetDeepLinks.parse(intent?.data)
            ?: return
        com.dskja.betterstreamflix.widgets.WidgetDeepLinkRouter.handle(this, target)
    }

    private fun handleSharedWatchlistIntent(intent: Intent?) {
        val token = com.dskja.betterstreamflix.sync.SharedWatchlistLinks.parseToken(intent?.data)
            ?: return
        com.dskja.betterstreamflix.sync.SharedWatchlistUi.handleIncomingToken(this, token)
    }

    private fun handleWatchPartyIntent(intent: Intent?) {
        val token = com.dskja.betterstreamflix.sync.WatchPartyLinks.parseToken(intent?.data)
            ?: return
        com.dskja.betterstreamflix.sync.WatchPartyUi.handleIncomingToken(this, token)
    }

    private fun handleDownloadPackIntent(intent: Intent?) {
        val payload = com.dskja.betterstreamflix.download.DownloadPackLinks.parse(intent?.data)
            ?: return
        com.dskja.betterstreamflix.download.DownloadPackUi.handleIncoming(this, payload)
    }

    private fun handleProfileTransferIntent(intent: Intent?) {
        val payload = com.dskja.betterstreamflix.profiletransfer.ProfileTransferLinks.parse(intent?.data)
            ?: return
        com.dskja.betterstreamflix.profiletransfer.ProfileTransferUi.handleIncoming(this, payload)
    }

    private fun handleCastRoomIntent(intent: Intent?) {
        val token = com.dskja.betterstreamflix.sync.CastRoomLinks.parseToken(intent?.data)
            ?: return
        com.dskja.betterstreamflix.sync.CastRoomUi.handleIncomingToken(this, token)
    }

    private fun handleSessionHandoffIntent(intent: Intent?) {
        val raw = intent?.data?.toString() ?: return
        if (com.dskja.betterstreamflix.sync.SessionHandoffLinks.parseCode(raw) == null &&
            com.dskja.betterstreamflix.sync.SessionHandoffLinks.parseToken(raw) == null
        ) {
            return
        }
        com.dskja.betterstreamflix.sync.SessionHandoffUi.handleIncoming(this, raw)
    }

    private fun handleCastHandoffIntent(intent: Intent?) {
        val code = com.dskja.betterstreamflix.sync.CastHandoffLinks.parseShortCode(intent?.data)
            ?: return
        com.dskja.betterstreamflix.sync.CastHandoffUi.handleIncomingCode(this, code)
    }

    private fun registerCastRoomHostConsumer() {
        com.dskja.betterstreamflix.sync.CastRoomUi.registerHostConsumer(
            object : com.dskja.betterstreamflix.sync.CastRoomUi.HostConsumer {
                override fun onCastRoomPlayItem(
                    item: com.dskja.betterstreamflix.sync.CastRoomItemPayload,
                    preferredServerName: String?,
                ) {
                    com.dskja.betterstreamflix.sync.CastRoomUi.navigateHostToItem(this@MainTvActivity, item)
                }

                override fun onCastRoomSetPlaying(playing: Boolean) {
                    val navHost =
                        supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                    val player = navHost?.childFragmentManager?.primaryNavigationFragment
                        as? com.dskja.betterstreamflix.fragments.player.PlayerTvFragment
                    player?.castRoomApplyRemotePlaying(playing)
                }

                override fun onCastRoomSeek(positionMs: Long) {
                    runOnUiThread {
                        val navHost =
                            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
                        val player = navHost?.childFragmentManager?.primaryNavigationFragment
                            as? com.dskja.betterstreamflix.fragments.player.PlayerTvFragment
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
                        com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(
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
        val query = com.dskja.betterstreamflix.sync.TvSidekick.sanitizeKeyboard(text) ?: return
        val navController =
            (supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment)
                ?.navController
        runCatching { navController?.navigate(R.id.search) }
        binding.root.post {
            val frag = getCurrentFragment()
                as? com.dskja.betterstreamflix.fragments.search.SearchTvFragment
            if (frag != null) {
                frag.applySidekickQuery(query)
            } else {
                binding.root.postDelayed({
                    (getCurrentFragment() as? com.dskja.betterstreamflix.fragments.search.SearchTvFragment)
                        ?.applySidekickQuery(query)
                }, 350L)
            }
            com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(
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
        if (com.dskja.betterstreamflix.sync.WatchPartyLinks.parseToken(data) != null) return
        if (com.dskja.betterstreamflix.download.DownloadPackLinks.parse(data) != null) return
        if (com.dskja.betterstreamflix.profiletransfer.ProfileTransferLinks.parse(data) != null) return
        if (com.dskja.betterstreamflix.sync.CastRoomLinks.parseToken(data) != null) return
        if (com.dskja.betterstreamflix.sync.SessionHandoffLinks.parseCode(data.toString()) != null) return
        if (com.dskja.betterstreamflix.sync.SessionHandoffLinks.parseToken(data.toString()) != null) return
        if (com.dskja.betterstreamflix.sync.CastHandoffLinks.parseShortCode(data) != null) return
        com.dskja.betterstreamflix.tvlauncher.TvWatchDeepLinkRouter.handle(this, data)
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
        com.dskja.betterstreamflix.sync.SharedWatchlistUi.tryFlushPending(this)
        com.dskja.betterstreamflix.sync.WatchPartyUi.tryFlushPending(this)
        com.dskja.betterstreamflix.download.DownloadPackUi.tryFlushPending(this)
        com.dskja.betterstreamflix.profiletransfer.ProfileTransferUi.tryFlushPending(this)
        com.dskja.betterstreamflix.sync.CastRoomUi.tryFlushPending(this)
        com.dskja.betterstreamflix.sync.CastHandoffUi.tryFlushPending(this)
        com.dskja.betterstreamflix.sync.SessionHandoffUi.tryFlushPending(this)
        com.dskja.betterstreamflix.tvlauncher.TvLauncherSync.requestPublish(this)
        val destId = (supportFragmentManager.findFragmentById(binding.navMainFragment.id)
            as? NavHostFragment)?.navController?.currentDestination?.id
        refreshCastStatusStrip(playerVisible = destId == R.id.player)
    }

    override fun onStart() {
        super.onStart()
        com.dskja.betterstreamflix.profiles.ProfileUnlockGate.markForeground()
        runCatching {
            com.dskja.betterstreamflix.profiles.ProfileManager.sweepExpiredGuests(this)
        }
        com.dskja.betterstreamflix.profiles.GuestForegroundSweep.onStart(this)
        val navHostFragment = supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as? NavHostFragment
        if (navHostFragment != null) {
            binding.root.post { showProfileUnlockGateIfNeeded(navHostFragment) }
        }
        binding.root.post {
            com.dskja.betterstreamflix.kids.KidsBedtimeUi.maybeShowLock(this@MainTvActivity)
        }
        com.dskja.betterstreamflix.sync.SharedWatchlistUi.tryFlushPending(this)
        com.dskja.betterstreamflix.sync.WatchPartyUi.tryFlushPending(this)
        com.dskja.betterstreamflix.download.DownloadPackUi.tryFlushPending(this)
        com.dskja.betterstreamflix.profiletransfer.ProfileTransferUi.tryFlushPending(this)
        com.dskja.betterstreamflix.sync.CastRoomUi.tryFlushPending(this)
        com.dskja.betterstreamflix.sync.CastHandoffUi.tryFlushPending(this)
        com.dskja.betterstreamflix.sync.SessionHandoffUi.tryFlushPending(this)
    }

    override fun onStop() {
        com.dskja.betterstreamflix.profiles.GuestForegroundSweep.onStop()
        com.dskja.betterstreamflix.profiles.ProfileUnlockGate.markBackgrounded()
        super.onStop()
    }

    override fun onDestroy() {
        com.dskja.betterstreamflix.remotefind.RemoteFindPadBridge.detach(this)
        com.dskja.betterstreamflix.remotefind.RemoteFindNavBridge.detach(this)
        if (isFinishing) {
            com.dskja.betterstreamflix.remotefind.RemoteFindHost.stop()
        } else {
            // Keep session across recreate; sockets restart in onCreate via restoreIfNeeded.
            com.dskja.betterstreamflix.remotefind.RemoteFindHost.pauseForRecreation()
        }
        com.dskja.betterstreamflix.sync.CastRoomUi.registerHostConsumer(null)
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
     * Home loading is checked in [com.dskja.betterstreamflix.fragments.home.HomeTvFragment].
     */
    fun isAmbientModalBlocking(): Boolean {
        if (::updateAppDialog.isInitialized && updateAppDialog.isShowing) return true
        if (com.dskja.betterstreamflix.kids.KidsBedtimeUi.isLockDialogShowing()) return true
        if (com.dskja.betterstreamflix.profiles.ProfileUnlockGate.requiresGateForActive()) {
            return true
        }
        val host = (supportFragmentManager.findFragmentById(R.id.nav_main_fragment)
            as? NavHostFragment)
            ?.childFragmentManager
            ?.primaryNavigationFragment
        if (host?.childFragmentManager?.findFragmentByTag(
                com.dskja.betterstreamflix.profiles.ProfilePickerDialog.TAG,
            ) != null
        ) {
            return true
        }
        return false
    }

    private fun currentHomeFragment():
        com.dskja.betterstreamflix.fragments.home.HomeTvFragment? =
        (supportFragmentManager.findFragmentById(R.id.nav_main_fragment)
            as? NavHostFragment)
            ?.childFragmentManager
            ?.primaryNavigationFragment
            as? com.dskja.betterstreamflix.fragments.home.HomeTvFragment

    private fun showProfileUnlockGateIfNeeded(navHostFragment: NavHostFragment) {
        if (isFinishing || isDestroyed) return
        if (!com.dskja.betterstreamflix.profiles.ProfileUnlockGate.requiresGateForActive()) {
            com.dskja.betterstreamflix.sync.WatchPartyUi.tryFlushPending(this)
            com.dskja.betterstreamflix.download.DownloadPackUi.tryFlushPending(this)
            com.dskja.betterstreamflix.profiletransfer.ProfileTransferUi.tryFlushPending(this)
            com.dskja.betterstreamflix.sync.CastRoomUi.tryFlushPending(this)
            com.dskja.betterstreamflix.sync.CastHandoffUi.tryFlushPending(this)
            com.dskja.betterstreamflix.sync.SessionHandoffUi.tryFlushPending(this)
            return
        }
        val host = navHostFragment.childFragmentManager.primaryNavigationFragment
            ?: navHostFragment
        com.dskja.betterstreamflix.profiles.ProfileUnlockGate.maybeShow(
            activity = this,
            hostFragmentProvider = { host },
            onUnlocked = {
                com.dskja.betterstreamflix.sync.WatchPartyUi.tryFlushPending(this)
                com.dskja.betterstreamflix.download.DownloadPackUi.tryFlushPending(this)
                com.dskja.betterstreamflix.profiletransfer.ProfileTransferUi.tryFlushPending(this)
                com.dskja.betterstreamflix.sync.CastHandoffUi.tryFlushPending(this)
        com.dskja.betterstreamflix.sync.SessionHandoffUi.tryFlushPending(this)
                com.dskja.betterstreamflix.sync.CastRoomUi.tryFlushPending(this)
                com.dskja.betterstreamflix.sync.SessionHandoffUi.tryFlushPending(this)
            },
        )
    }

    private fun applyThemeNavigationChrome() {
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
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
        val systemBar = ThemeManager.palette(UserPreferences.selectedTheme).systemBar
        window.statusBarColor = systemBar
        window.navigationBarColor = systemBar
    }
    
    private fun updateNavigationVisibility() {
        val provider = UserPreferences.currentProvider ?: return
        val supportsMovies = Provider.supportsMovies(provider)
        val supportsTvShows = Provider.supportsTvShows(provider)
        // Age-gate + Kids Fortress (upstream Batch 10 + SS-B10-03 parity).
        val liveHubVisible = !com.dskja.betterstreamflix.utils.LiveHubCatalog.isAgeGated() &&
            com.dskja.betterstreamflix.kids.KidsFortress.liveHubAllowed() &&
            com.dskja.betterstreamflix.utils.LiveHubCatalog.isLiveProvider(provider)
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
            com.dskja.betterstreamflix.kids.KidsFortress.downloadsNavVisible()
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
            as? com.dskja.betterstreamflix.fragments.home.HomeTvFragment
        if (home?.requestContentFocus() != true) {
            binding.navMain.requestFocus()
        }
    }

    /** Clean Exit from player → exact remembered Home tile, else Featured / first shelf. */
    private fun focusHomeAfterCleanExit() {
        val host = supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as? NavHostFragment
        val home = host?.childFragmentManager?.primaryNavigationFragment
            as? com.dskja.betterstreamflix.fragments.home.HomeTvFragment
        if (home?.restoreCleanExitFocus() == true) return
        if (home?.requestContentFocus() == true) return
        binding.navMain.requestFocus()
    }

    /** Side-nav DPAD-RIGHT → content for the current browse destination. */
    private fun requestBrowseContentFocus(): Boolean {
        val frag = getCurrentFragment() ?: return false
        return when (frag) {
            is com.dskja.betterstreamflix.fragments.home.HomeTvFragment ->
                frag.requestContentFocus()
            is com.dskja.betterstreamflix.fragments.live.LiveHubTvFragment ->
                frag.requestContentFocus()
            is com.dskja.betterstreamflix.fragments.movie.MovieTvFragment ->
                frag.requestContentFocus()
            is com.dskja.betterstreamflix.fragments.tv_show.TvShowTvFragment ->
                frag.requestContentFocus()
            is com.dskja.betterstreamflix.fragments.season.SeasonTvFragment ->
                frag.requestContentFocus()
            is com.dskja.betterstreamflix.fragments.player.PlayerTvFragment -> {
                frag.requestPlayerFocus()
                true
            }
            is com.dskja.betterstreamflix.fragments.search.SearchTvFragment -> {
                // FA-027: first poster, not Leanback grid container.
                val grid = frag.view?.findViewById<View>(R.id.vgv_search)
                grid != null && com.dskja.betterstreamflix.ui.HomeProfileChip.focusFirstPoster(grid)
            }
            is com.dskja.betterstreamflix.fragments.movies.MoviesTvFragment -> {
                val grid = frag.view?.findViewById<View>(R.id.vgv_movies)
                grid != null && com.dskja.betterstreamflix.ui.HomeProfileChip.focusFirstPoster(grid)
            }
            is com.dskja.betterstreamflix.fragments.tv_shows.TvShowsTvFragment -> {
                val grid = frag.view?.findViewById<View>(R.id.vgv_tv_shows)
                grid != null && com.dskja.betterstreamflix.ui.HomeProfileChip.focusFirstPoster(grid)
            }
            is com.dskja.betterstreamflix.fragments.favorites.FavoritesTvFragment -> {
                val grid = frag.view?.findViewById<View>(R.id.rv_favorites)
                grid != null && com.dskja.betterstreamflix.ui.HomeProfileChip.focusFirstPoster(grid)
            }
            is com.dskja.betterstreamflix.fragments.downloads.DownloadsTvFragment -> {
                // Offline Studio: restore Clean Exit token; Leanback Edge: first poster, not list root.
                frag.restoreCleanExitFocus() ||
                    run {
                        val grid = frag.view?.findViewById<View>(R.id.rv_downloads)
                        grid != null &&
                            com.dskja.betterstreamflix.ui.HomeProfileChip.focusFirstPoster(grid)
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
                (getCurrentFragment() as? com.dskja.betterstreamflix.fragments.movie.MovieTvFragment)
                    ?.requestContentFocus()
            R.id.tv_show ->
                (getCurrentFragment() as? com.dskja.betterstreamflix.fragments.tv_show.TvShowTvFragment)
                    ?.requestContentFocus()
            R.id.season ->
                (getCurrentFragment() as? com.dskja.betterstreamflix.fragments.season.SeasonTvFragment)
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
