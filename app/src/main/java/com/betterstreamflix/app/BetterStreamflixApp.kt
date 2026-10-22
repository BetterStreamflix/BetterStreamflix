package com.betterstreamflix.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import java.security.Security
import org.conscrypt.Conscrypt
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.providers.AniWorldProvider
import com.betterstreamflix.app.providers.KinoGerProvider
import com.betterstreamflix.app.providers.SerienStreamProvider
import com.betterstreamflix.app.providers.SoloLatinoProvider
import com.betterstreamflix.app.sync.CloudSyncManager
import com.betterstreamflix.app.sync.SupabaseProvider
import com.betterstreamflix.app.utils.AccessibilityPreferences
import com.betterstreamflix.app.utils.ArtworkRepairScheduler
import com.betterstreamflix.app.utils.CacheUtils
import com.betterstreamflix.app.utils.CrashReporter
import com.betterstreamflix.app.utils.DeviceCapabilities
import com.betterstreamflix.app.utils.DnsResolver
import com.betterstreamflix.app.utils.IsrgRootTrustProvider
import com.betterstreamflix.app.providers.ProviderSmoke
import com.betterstreamflix.app.utils.SentryBootstrap
import com.betterstreamflix.app.utils.TMDb3
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BetterStreamflixApp : Application() {
    companion object {
        lateinit var instance: BetterStreamflixApp
            private set

        @Volatile
        var currentActivity: Activity? = null
            private set
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AccessibilityPreferences.wrapAfterLanguage(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Sentry first so early crashes (and CrashReporter's chained handler) are covered.
        runCatching { SentryBootstrap.init(this) }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivity === activity) {
                    currentActivity = null
                }
            }
        })

        // 0–1. Conscrypt clinit + ISRG trust install do jar/manifest I/O and can
        // ANR on main (BETTERSTREAMFLIX-2J). Prefs/UI setup do not need them yet;
        // the IO block below installs both before DoH / first OkHttp traffic.
        // Fire OS / low-RAM sticks can fail loading native Conscrypt — never abort.

        // 2. Inizializzazione preferenze (con applicationContext)
        runCatching { UserPreferences.setup(this) }
            .onFailure { Log.e("BetterStreamflixApp", "UserPreferences.setup failed", it) }
        // Recover from a persisted provider name that no longer resolves —
        // otherwise Home opens into a crash loop until clear-data.
        runCatching { UserPreferences.clearInvalidCurrentProvider() }
        runCatching {
            com.betterstreamflix.app.logo.TmdbLogoCache.init(this)
            com.betterstreamflix.app.logo.TmdbLogoTelemetry.init(this)
            com.betterstreamflix.app.logo.ChannelLogoStore.init(this)
            com.betterstreamflix.app.settings.SettingsRepository.init(this)
            com.betterstreamflix.app.logo.ProviderLogoStore.init(this)
            com.betterstreamflix.app.logo.CustomLogoStore.init(this)
            UserPreferences.applyTmdbLogoTtls()
            com.betterstreamflix.app.logo.FanartLogoFetch.apiKey =
                UserPreferences.fanartApiKey
        }
        // Profiles must never kill cold start (BETTERSTREAMFLIX-1B was a Gson/R8
        // TypeToken crash inside ProfileStore during init on older builds).
        runCatching { ProfileManager.init(this) }
            .onFailure { Log.e("BetterStreamflixApp", "ProfileManager.init failed", it) }
        runCatching { CrashReporter.install(this) }
        runCatching {
            com.betterstreamflix.app.platform.PlatformBootstrap.start(this)
        }

        runCatching {
            com.betterstreamflix.app.download.DownloadConnectivityMonitor.start(this)
            com.betterstreamflix.app.download.DownloadNotifier.ensureChannel(this)
            com.betterstreamflix.app.notifications.ProductNotifier.ensureChannels(this)
            com.betterstreamflix.app.notifications.NotificationCheckScheduler.sync(this)
            com.betterstreamflix.app.net.NetworkThroughputHistory.attach(this)
        }

        val appContext = applicationContext
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val threshold = if (isTv) 10L else 50L
        val dohUrl = runCatching { UserPreferences.dohProviderUrl }.getOrDefault("")

        // SSL providers + DoH off-main (BETTERSTREAMFLIX-2J).
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }.onFailure {
                Log.e("BetterStreamflixApp", "Conscrypt init failed: ${it.message}")
            }
            // ISRG Root X1 for Let's Encrypt on Android < 7 (no network_security_config).
            runCatching { IsrgRootTrustProvider.install() }
            runCatching { DnsResolver.setDnsUrl(dohUrl) }
                .onFailure { Log.e("BetterStreamflixApp", "DnsResolver.setDnsUrl failed", it) }
            // Rebuild after DoH is applied so TMDB prefers the configured resolver.
            runCatching { TMDb3.rebuildService() }
            runCatching {
                com.betterstreamflix.app.download.StreamflixDownloadManager.get(appContext)
                // Force-stop / cold start does not get BOOT_COMPLETED — schedule
                // resume work when Room still has an active/queued download.
                com.betterstreamflix.app.download.DownloadResumeScheduler.enqueue(appContext)
            }
            runCatching { AppDatabase.setup(appContext) }
            runCatching {
                com.betterstreamflix.app.tvlauncher.TvLauncherSync.start(appContext)
            }
            runCatching { SupabaseProvider.initialize(appContext) }
            runCatching {
                CloudSyncManager.initialize(appContext)
                SentryBootstrap.setCloudUser(
                    CloudSyncManager.currentUserId(),
                    CloudSyncManager.currentUserEmail(),
                )
            }
            runCatching { SerienStreamProvider.initialize(appContext) }
            runCatching {
                com.betterstreamflix.app.providers.SerienStreamAuthManager.seedRuntimeCookies()
            }
            runCatching {
                com.betterstreamflix.app.providers.AniWorldAuthManager.seedRuntimeCookies()
            }
            runCatching { KinoGerProvider.init(appContext) }
            runCatching { SoloLatinoProvider.init(appContext) }
            runCatching { AniWorldProvider.initialize(appContext) }
            runCatching { ArtworkRepairScheduler.schedule(appContext, UserPreferences.currentProvider) }
            runCatching {
                val report = ProviderSmoke.validateRegistry()
                if (!report.ok) {
                    android.util.Log.w(
                        "BetterStreamflixApp",
                        "Provider registry issues: duplicates=${report.duplicateNames}",
                    )
                }
            }
            // Skip automatic cache wipe on constrained Fire TV sticks: creating a WebView
            // during cold start can kill the process right after the splash screen.
            if (!DeviceCapabilities.shouldUseConstrainedPlayback(appContext)) {
                runCatching { CacheUtils.autoClearIfNeeded(appContext, thresholdMb = threshold) }
            }
        }

        if (DeviceCapabilities.shouldReduceHomeEffects(this)) {
            runCatching {
                com.bumptech.glide.Glide.get(this)
                    .setMemoryCategory(com.bumptech.glide.MemoryCategory.LOW)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Push pending batched logo-cache writes to disk on any pressure signal.
        runCatching { com.betterstreamflix.app.logo.TmdbLogoCache.flush() }
        if (level >= TRIM_MEMORY_RUNNING_MODERATE) {
            runCatching {
                com.bumptech.glide.Glide.get(this).clearMemory()
            }
        }
        if (level >= TRIM_MEMORY_RUNNING_LOW &&
            !DeviceCapabilities.shouldUseConstrainedPlayback(this)
        ) {
            CacheUtils.clearAppCache(this)
            applicationScope.launch(Dispatchers.IO) {
                runCatching {
                    com.bumptech.glide.Glide.get(this@BetterStreamflixApp).clearDiskCache()
                }
            }
        }
    }
}
