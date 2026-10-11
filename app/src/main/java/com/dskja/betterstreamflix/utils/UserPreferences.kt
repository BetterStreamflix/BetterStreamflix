package com.dskja.betterstreamflix.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import com.dskja.betterstreamflix.BetterStreamflixApp
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadQualityPreset
import com.dskja.betterstreamflix.player.PlaybackVideoQuality
import com.dskja.betterstreamflix.download.DownloadStorageLocation
import com.dskja.betterstreamflix.fragments.player.settings.PlayerSettingsView
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.providers.Provider.Companion.providers
import com.dskja.betterstreamflix.providers.TmdbProvider
import androidx.core.content.edit
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.profiles.PinHasher
import com.dskja.betterstreamflix.profiles.ProfileManager
import org.json.JSONObject
import org.json.JSONArray

object UserPreferences {

    private const val TAG = "UserPrefsDebug"

    private lateinit var prefs: SharedPreferences

    // Default DoH Provider URL (Cloudflare)
    private const val DEFAULT_DOH_PROVIDER_URL = "https://cloudflare-dns.com/dns-query"
    const val DOH_DISABLED_VALUE = "" // Value to represent DoH being disabled
    private const val DEFAULT_SERIENSTREAM_DOMAIN = "186.2.175.5"
    private val DEPRECATED_SERIENSTREAM_DOMAINS = setOf(
        "s.to",
        "www.s.to",
        "serien.stream",
        // Invalid TLS / parking as of 2026 — prefer serien.domains proxy / .to / .cx.
        "serienstream.sx",
        "www.serienstream.sx",
    )
    private const val DEFAULT_MOFLIX_DOMAIN = "moflix-stream.xyz"
    private const val DEFAULT_STREAMINGCOMMUNITY_DOMAIN = "streamingunity.win"
    private val DEPRECATED_STREAMINGCOMMUNITY_DOMAINS = setOf(
        "streamingunity.cc",
        "streamingcommunityz.green",
        "streamingunity.club",
        "streamingunity.bike",
        "streamingcommunityz.buzz",
    )
    private const val DEFAULT_CUEVANA_DOMAIN = "cuevana3.gs"
    private val DEPRECATED_CUEVANA_DOMAINS = setOf(
        "cuevana.gs",
    )
    private const val DEFAULT_POSEIDON_DOMAIN = "www.poseidonhd2.co"

    const val PROVIDER_URL = "URL"
    const val PROVIDER_LOGO = "LOGO"
    const val PROVIDER_PORTAL_URL = "PORTAL_URL"
    const val PROVIDER_AUTOUPDATE = "AUTOUPDATE_URL"
    const val PROVIDER_NEW_INTERFACE = "NEW_INTERFACE"
    const val PROVIDER_PREFERRED_SERVER = "PREFERRED_SERVER"
    const val PROVIDER_AUTH_TOKEN = "AUTH_TOKEN"
    const val PROVIDER_AUTH_USERNAME = "AUTH_USERNAME"
    const val PROVIDER_AUTH_PASSWORD = "AUTH_PASSWORD"

    lateinit var providerCache: JSONObject

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message())
        }
    }

    fun setup(context: Context) {
        val prefsName = "${BuildConfig.APPLICATION_ID}.preferences"
        prefs = context.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE,
        )
        if (::prefs.isInitialized) {
            debugLog { "prefs initialized: ${prefs.hashCode()}" }

            val jsonString = Key.PROVIDER_CACHE.getString() ?: "{}"
            providerCache = runCatching { JSONObject(jsonString) }.getOrDefault(JSONObject())
        }
    }


    var currentProvider: Provider?
        get() {
            val providerName = Key.CURRENT_PROVIDER.getString()
            if (providerName?.startsWith("TMDb (") == true && providerName.endsWith(")")) {
                val lang = providerName.substringAfter("TMDb (").substringBefore(")")
                return TmdbProvider.forLanguage(lang)
            }
            return Provider.providers.keys.find { it.name == providerName }
                ?: providerName?.let { Provider.findByName(it) }
        }
        set(value) {
            // Prefer [prepareProviderSwitch] + CLEAR_TASK + [consumePendingProviderSwitch]
            // for picker restarts. Live setter remains for rare in-process paths but
            // now stages the same pending flag so Room reset happens on next Main*
            // recreate when possible (SS-05). Still resets + notifies for sticky
            // in-process callers that do not recreate.
            if (value != null) {
                prepareProviderSwitch(value)
            } else {
                Key.CURRENT_PROVIDER.setString(null)
            }
            AppDatabase.resetInstance()
            // Provider language drives TMDb logo ranking — drop details so logos refetch.
            runCatching { TmdbCache.clear() }
            runCatching {
                ArtworkRepairScheduler.schedule(BetterStreamflixApp.instance, value)
            }
            ProviderChangeNotifier.notifyProviderChanged()
        }

    /**
     * Transient sticky handoff for Continue Watching / Featured / detail play when the
     * item belongs to another provider. Sets [currentProvider] without
     * [AppDatabase.resetInstance] or [ProviderChangeNotifier] so Home/library collectors
     * are not thrashed mid-playback navigation.
     */
    fun setCurrentProviderForPlayback(provider: Provider) {
        if (currentProvider?.name == provider.name) return
        Key.CURRENT_PROVIDER.setString(provider.name)
        runCatching {
            ArtworkRepairScheduler.schedule(BetterStreamflixApp.instance, provider)
        }
    }

    private const val PROVIDER_SWITCH_PENDING = "PROVIDER_SWITCH_PENDING"

    /**
     * Persist a provider pick for a CLEAR_TASK restart without closing Room or
     * notifying collectors mid-teardown (that race left the app crash-looping
     * until clear-data).
     */
    fun prepareProviderSwitch(provider: Provider) {
        Key.CURRENT_PROVIDER.setString(provider.name)
        prefs.edit { putBoolean(PROVIDER_SWITCH_PENDING, true) }
        runCatching {
            ArtworkRepairScheduler.schedule(BetterStreamflixApp.instance, provider)
        }
    }

    /**
     * Call once from Main*Activity.onCreate after a provider switch restart.
     * Returns true when a pending switch was consumed.
     */
    fun consumePendingProviderSwitch(): Boolean {
        if (!::prefs.isInitialized) return false
        if (!prefs.getBoolean(PROVIDER_SWITCH_PENDING, false)) return false
        prefs.edit { putBoolean(PROVIDER_SWITCH_PENDING, false) }
        runCatching { AppDatabase.resetInstance() }
        runCatching { TmdbCache.clear() }
        return true
    }

    /** Drop a persisted provider name that no longer resolves (corrupt / removed). */
    fun clearInvalidCurrentProvider() {
        if (!::prefs.isInitialized) return
        val name = Key.CURRENT_PROVIDER.getString() ?: return
        if (currentProvider != null) return
        debugLog { "Clearing unresolved CURRENT_PROVIDER=$name" }
        Key.CURRENT_PROVIDER.setString(null)
        prefs.edit { putBoolean(PROVIDER_SWITCH_PENDING, false) }
    }

    /**
     * Drop the preferred provider entirely (corrupt Room file, unrecoverable open).
     * Does not close Room — callers should [AppDatabase.resetInstance] as needed.
     */
    fun clearCurrentProvider() {
        if (!::prefs.isInitialized) return
        Key.CURRENT_PROVIDER.setString(null)
        prefs.edit { putBoolean(PROVIDER_SWITCH_PENDING, false) }
    }

    fun getProviderCache(provider: Provider, key: String): String {
        return providerCache
            .optJSONObject(provider.name)
            ?.optString(key)
            .orEmpty()
    }

    fun setProviderCache(provider: Provider?, key: String, value: String) {
        val providerName = provider?.name ?: currentProvider?.name ?: return
        val innerJson = providerCache.optJSONObject(providerName)
            ?: JSONObject().also { providerCache.put(providerName, it) }
        innerJson.put(key, value)
        Key.PROVIDER_CACHE.setString(providerCache.toString())
    }

    fun clearProviderCache(providerName: String) {
        if (providerCache.has(providerName)) {
            debugLog { "CACHE: removing stored data for $providerName" }
            providerCache.remove(providerName)
            Key.PROVIDER_CACHE.setString(providerCache.toString())
        }
    }

    var currentLanguage: String?
        get() = Key.CURRENT_LANGUAGE.getString()
        set(value) = Key.CURRENT_LANGUAGE.setString(value)

    var providerLanguage: String?
        get() = Key.PROVIDER_LANGUAGE.getString()
        set(value) = Key.PROVIDER_LANGUAGE.setString(value)

    /**
     * Caption text-size multiplier for the active profile (Subtitle Studio light).
     * Falls back to the legacy device-wide value so existing installs keep their size.
     */
    var captionTextSize: Float
        get() = profileScopedFloat(Key.CAPTION_TEXT_SIZE)
            ?: Key.CAPTION_TEXT_SIZE.getFloat()
            ?: PlayerSettingsView.Settings.Subtitle.Style.TextSize.DEFAULT.value
        set(value) {
            setProfileScopedFloat(Key.CAPTION_TEXT_SIZE, value)
        }

    var autoplay: Boolean
        get() = Key.AUTOPLAY.getBoolean() ?: true
        set(value) {
            Key.AUTOPLAY.setBoolean(value)
        }

    var downloadWifiOnly: Boolean
        get() = Key.DOWNLOAD_WIFI_ONLY.getBoolean() ?: true
        set(value) {
            Key.DOWNLOAD_WIFI_ONLY.setBoolean(value)
        }

    var downloadMaxConcurrent: Int
        get() = Key.DOWNLOAD_MAX_CONCURRENT.getInt() ?: 2
        set(value) {
            Key.DOWNLOAD_MAX_CONCURRENT.setInt(value.coerceIn(1, 4))
        }

    var downloadQualityPreset: DownloadQualityPreset
        get() = DownloadQualityPreset.fromKey(Key.DOWNLOAD_QUALITY_PRESET.getString())
        set(value) {
            Key.DOWNLOAD_QUALITY_PRESET.setString(value.name)
        }

    var downloadNotifyComplete: Boolean
        get() = Key.DOWNLOAD_NOTIFY_COMPLETE.getBoolean() ?: true
        set(value) {
            Key.DOWNLOAD_NOTIFY_COMPLETE.setBoolean(value)
        }

    /** Opt-in: alert when a My List series gets a new latest episode. */
    var notifyWatchlistNewEpisode: Boolean
        get() = Key.NOTIFY_WATCHLIST_NEW_EPISODE.getBoolean() ?: false
        set(value) {
            Key.NOTIFY_WATCHLIST_NEW_EPISODE.setBoolean(value)
        }

    /** Opt-in: alert when a Live Hub favorite newly appears as live / starting. */
    var notifyLiveFavoriteStarting: Boolean
        get() = Key.NOTIFY_LIVE_FAVORITE_STARTING.getBoolean() ?: false
        set(value) {
            Key.NOTIFY_LIVE_FAVORITE_STARTING.setBoolean(value)
        }

    /** Silent baseline taken after Live notify opt-in (avoid spam on enable). */
    var notifyLiveBaselineReady: Boolean
        get() = Key.NOTIFY_LIVE_BASELINE_READY.getBoolean() ?: false
        set(value) {
            Key.NOTIFY_LIVE_BASELINE_READY.setBoolean(value)
        }

    var notifyLiveActiveKeys: Set<String>
        get() = Key.NOTIFY_LIVE_ACTIVE_KEYS.getStringSet() ?: emptySet()
        set(value) {
            Key.NOTIFY_LIVE_ACTIVE_KEYS.setStringSet(value.ifEmpty { null })
        }

    var notifyLiveCooldownJson: String
        get() = Key.NOTIFY_LIVE_COOLDOWN_JSON.getString().orEmpty()
        set(value) {
            Key.NOTIFY_LIVE_COOLDOWN_JSON.setString(value.trim().ifEmpty { null })
        }

    /** Per-favorite snooze-until timestamps (JSON object). */
    var notifyLiveSnoozeJson: String
        get() = Key.NOTIFY_LIVE_SNOOZE_JSON.getString().orEmpty()
        set(value) {
            Key.NOTIFY_LIVE_SNOOZE_JSON.setString(value.trim().ifEmpty { null })
        }

    /** Recent Live reminder fire times (JSON array) for hourly flood cap. */
    var notifyLiveRecentFiresJson: String
        get() = Key.NOTIFY_LIVE_RECENT_FIRES_JSON.getString().orEmpty()
        set(value) {
            Key.NOTIFY_LIVE_RECENT_FIRES_JSON.setString(value.trim().ifEmpty { null })
        }

    /** Last kickoff fingerprint notified per favorite (JSON object). */
    var notifyLiveKickoffFpJson: String
        get() = Key.NOTIFY_LIVE_KICKOFF_FP_JSON.getString().orEmpty()
        set(value) {
            Key.NOTIFY_LIVE_KICKOFF_FP_JSON.setString(value.trim().ifEmpty { null })
        }

    /** Identity keys with an active pre-kickoff OneTime WorkManager schedule. */
    var notifyLiveScheduledKeys: Set<String>
        get() = Key.NOTIFY_LIVE_SCHEDULED_KEYS.getStringSet() ?: emptySet()
        set(value) {
            Key.NOTIFY_LIVE_SCHEDULED_KEYS.setStringSet(value.ifEmpty { null })
        }

    /**
     * Profile-scoped JSON for [com.dskja.betterstreamflix.providers.ProviderServerAb]
     * hoster stability learning (Provider A/B).
     */
    var providerServerAbJson: String
        get() = profileScopedString(Key.PROVIDER_SERVER_AB_JSON).orEmpty()
        set(value) = setProfileScopedString(
            Key.PROVIDER_SERVER_AB_JSON,
            value.trim(),
        )

    var notifyEpisodeFingerprintsJson: String
        get() = Key.NOTIFY_EPISODE_FINGERPRINTS_JSON.getString().orEmpty()
        set(value) {
            Key.NOTIFY_EPISODE_FINGERPRINTS_JSON.setString(value.trim().ifEmpty { null })
        }

    var notifyEpisodeLastCheckAtMillis: Long
        get() = Key.NOTIFY_EPISODE_LAST_CHECK_AT.getLong() ?: 0L
        set(value) {
            Key.NOTIFY_EPISODE_LAST_CHECK_AT.setLong(value)
        }

    var downloadFilterCurrentProvider: Boolean
        get() = Key.DOWNLOAD_FILTER_CURRENT_PROVIDER.getBoolean() ?: false
        set(value) {
            Key.DOWNLOAD_FILTER_CURRENT_PROVIDER.setBoolean(value)
        }

    var downloadSoftLimitGb: Int
        get() = Key.DOWNLOAD_SOFT_LIMIT_GB.getInt() ?: 20
        set(value) {
            Key.DOWNLOAD_SOFT_LIMIT_GB.setInt(value.coerceIn(0, 500))
        }

    var downloadStorageLocation: DownloadStorageLocation
        get() = DownloadStorageLocation.fromKey(Key.DOWNLOAD_STORAGE_LOCATION.getString())
        set(value) {
            Key.DOWNLOAD_STORAGE_LOCATION.setString(value.name)
        }

    /** Persisted SAF tree URI for custom / SD download folder (empty when unset). */
    var downloadTreeUri: String
        get() = Key.DOWNLOAD_TREE_URI.getString().orEmpty()
        set(value) {
            Key.DOWNLOAD_TREE_URI.setString(value.trim().ifEmpty { null })
        }

    /**
     * Absolute app-specific directory actually used for the Media3 cache.
     * Survives a revoked SAF grant so offline playback keeps the same files.
     */
    var downloadResolvedCacheDir: String
        get() = Key.DOWNLOAD_RESOLVED_CACHE_DIR.getString().orEmpty()
        set(value) {
            Key.DOWNLOAD_RESOLVED_CACHE_DIR.setString(value.trim().ifEmpty { null })
        }

    /** Remembered external player package (empty = always show chooser / auto-detect). */
    var externalPlayerPackage: String
        get() = Key.EXTERNAL_PLAYER_PACKAGE.getString().orEmpty()
        set(value) {
            Key.EXTERNAL_PLAYER_PACKAGE.setString(value.trim().ifEmpty { null })
        }

    /** Remembered external download manager package (ADM etc.). */
    var externalDownloaderPackage: String
        get() = Key.EXTERNAL_DOWNLOADER_PACKAGE.getString().orEmpty()
        set(value) {
            Key.EXTERNAL_DOWNLOADER_PACKAGE.setString(value.trim().ifEmpty { null })
        }

    /** Smart Downloads: auto-download the next episodes after one finishes (opt-in). */
    var downloadSmartEnabled: Boolean
        get() = Key.DOWNLOAD_SMART_ENABLED.getBoolean() ?: false
        set(value) {
            Key.DOWNLOAD_SMART_ENABLED.setBoolean(value)
        }

    /** How many upcoming episodes Smart Downloads tries to keep queued (default 3). */
    var downloadSmartLookahead: Int
        get() = Key.DOWNLOAD_SMART_LOOKAHEAD.getInt()
            ?: com.dskja.betterstreamflix.download.SmartDownloadsPolicy.DEFAULT_LOOKAHEAD
        set(value) {
            Key.DOWNLOAD_SMART_LOOKAHEAD.setInt(
                com.dskja.betterstreamflix.download.SmartDownloadsPolicy.clampLookahead(value),
            )
        }

    /** Max unwatched / in-flight Smart Downloads episodes kept per series. */
    var downloadSmartSeriesQuota: Int
        get() = Key.DOWNLOAD_SMART_SERIES_QUOTA.getInt()
            ?: com.dskja.betterstreamflix.download.SmartDownloadsPolicy.DEFAULT_SERIES_QUOTA
        set(value) {
            Key.DOWNLOAD_SMART_SERIES_QUOTA.setInt(
                com.dskja.betterstreamflix.download.SmartDownloadsPolicy.clampSeriesQuota(value),
            )
        }

    /** Delete episode downloads once they were watched (Netflix-style, opt-in). */
    var downloadAutoDeleteWatched: Boolean
        get() = Key.DOWNLOAD_AUTO_DELETE_WATCHED.getBoolean() ?: false
        set(value) {
            Key.DOWNLOAD_AUTO_DELETE_WATCHED.setBoolean(value)
        }

    var keepScreenOnWhenPaused: Boolean
        get() = Key.KEEP_SCREEN_ON_WHEN_PAUSED.getBoolean() ?: false
        set(value) {
            Key.KEEP_SCREEN_ON_WHEN_PAUSED.setBoolean(value)
        }

    var playerGestures: Boolean
        get() = Key.PLAYER_GESTURES.getBoolean() ?: true
        set(value) {
            Key.PLAYER_GESTURES.setBoolean(value)
        }

    /** Night audio preset: dialog boost + quieter action peaks (player PCM DSP). */
    var nightAudioMode: Boolean
        get() = Key.NIGHT_AUDIO_MODE.getBoolean() ?: false
        set(value) {
            Key.NIGHT_AUDIO_MODE.setBoolean(value)
        }

    /** Phones: when true, player uses SENSOR (portrait allowed); default landscape. */
    var allowPhonePortraitPlayback: Boolean
        get() = Key.ALLOW_PHONE_PORTRAIT_PLAYBACK.getBoolean() ?: false
        set(value) {
            Key.ALLOW_PHONE_PORTRAIT_PLAYBACK.setBoolean(value)
        }

    /** One-shot discoverability toast for Play-with long-press → Download with… */
    var downloadWithLongPressHintShown: Boolean
        get() = Key.DOWNLOAD_WITH_LONG_PRESS_HINT_SHOWN.getBoolean() ?: false
        set(value) {
            Key.DOWNLOAD_WITH_LONG_PRESS_HINT_SHOWN.setBoolean(value)
        }

    var immersiveMode: Boolean
        get() = Key.IMMERSIVE_MODE.getBoolean() ?: false // Default changed to false
        set(value) {
            Key.IMMERSIVE_MODE.setBoolean(value)
        }

    var forceExtraBuffering: Boolean
        get() = Key.FORCE_EXTRA_BUFFERING.getBoolean() ?: false
        set(value) {
            Key.FORCE_EXTRA_BUFFERING.setBoolean(value)
        }

    /** Global stream quality cap: Auto / 720p / 1080p (saves mobile data). */
    var playbackVideoQuality: PlaybackVideoQuality
        get() = PlaybackVideoQuality.fromKey(Key.PLAYBACK_VIDEO_QUALITY.getString())
        set(value) {
            Key.PLAYBACK_VIDEO_QUALITY.setString(value.name)
        }

    /**
     * One switch: caps stream quality (Auto→720), lowers poster/banner TMDb sizes,
     * and disables Glide artwork prefetch.
     */
    var dataSaverMode: Boolean
        get() = Key.DATA_SAVER_MODE.getBoolean() ?: false
        set(value) {
            Key.DATA_SAVER_MODE.setBoolean(value)
        }

    /** Playback quality after applying [dataSaverMode] auto-cap. */
    val effectivePlaybackVideoQuality: PlaybackVideoQuality
        get() = playbackVideoQuality.cappedForDataSaver(dataSaverMode)

    // --- Full Platform: Trakt / Jellyfin / Plex / Debrid / Player backend ---

    var traktEnabled: Boolean
        get() = Key.TRAKT_ENABLED.getBoolean() ?: false
        set(value) {
            Key.TRAKT_ENABLED.setBoolean(value)
        }

    var traktClientId: String
        get() = Key.TRAKT_CLIENT_ID.getString().orEmpty()
        set(value) {
            Key.TRAKT_CLIENT_ID.setString(value.ifBlank { null })
        }

    var traktAccessToken: String
        get() = Key.TRAKT_ACCESS_TOKEN.getString().orEmpty()
        set(value) {
            Key.TRAKT_ACCESS_TOKEN.setString(value.ifBlank { null })
        }

    var jellyfinBaseUrl: String
        get() = Key.JELLYFIN_BASE_URL.getString().orEmpty()
        set(value) {
            Key.JELLYFIN_BASE_URL.setString(value.ifBlank { null })
        }

    var jellyfinAccessToken: String
        get() = Key.JELLYFIN_ACCESS_TOKEN.getString().orEmpty()
        set(value) {
            Key.JELLYFIN_ACCESS_TOKEN.setString(value.ifBlank { null })
        }

    var jellyfinUserId: String
        get() = Key.JELLYFIN_USER_ID.getString().orEmpty()
        set(value) {
            Key.JELLYFIN_USER_ID.setString(value.ifBlank { null })
        }

    var jellyfinDeviceId: String
        get() = Key.JELLYFIN_DEVICE_ID.getString().orEmpty()
        set(value) {
            Key.JELLYFIN_DEVICE_ID.setString(value.ifBlank { null })
        }

    var plexBaseUrl: String
        get() = Key.PLEX_BASE_URL.getString().orEmpty()
        set(value) {
            Key.PLEX_BASE_URL.setString(value.ifBlank { null })
        }

    var plexToken: String
        get() = Key.PLEX_TOKEN.getString().orEmpty()
        set(value) {
            Key.PLEX_TOKEN.setString(value.ifBlank { null })
        }

    var plexClientId: String
        get() = Key.PLEX_CLIENT_ID.getString().orEmpty()
        set(value) {
            Key.PLEX_CLIENT_ID.setString(value.ifBlank { null })
        }

    var debridEnabled: Boolean
        get() = Key.DEBRID_ENABLED.getBoolean() ?: false
        set(value) {
            Key.DEBRID_ENABLED.setBoolean(value)
        }

    var realDebridToken: String
        get() = Key.REAL_DEBRID_TOKEN.getString().orEmpty()
        set(value) {
            Key.REAL_DEBRID_TOKEN.setString(value.ifBlank { null })
        }

    /** realdebrid | premiumize | alldebrid | torbox */
    var debridProvider: String
        get() = Key.DEBRID_PROVIDER.getString() ?: "realdebrid"
        set(value) {
            Key.DEBRID_PROVIDER.setString(value.ifBlank { "realdebrid" })
        }

    var premiumizeApiKey: String
        get() = Key.PREMIUMIZE_API_KEY.getString().orEmpty()
        set(value) {
            Key.PREMIUMIZE_API_KEY.setString(value.ifBlank { null })
        }

    var allDebridApiKey: String
        get() = Key.ALLDEBRID_API_KEY.getString().orEmpty()
        set(value) {
            Key.ALLDEBRID_API_KEY.setString(value.ifBlank { null })
        }

    var torBoxApiKey: String
        get() = Key.TORBOX_API_KEY.getString().orEmpty()
        set(value) {
            Key.TORBOX_API_KEY.setString(value.ifBlank { null })
        }

    var simklEnabled: Boolean
        get() = Key.SIMKL_ENABLED.getBoolean() ?: false
        set(value) {
            Key.SIMKL_ENABLED.setBoolean(value)
        }

    var simklClientId: String
        get() = Key.SIMKL_CLIENT_ID.getString().orEmpty()
        set(value) {
            Key.SIMKL_CLIENT_ID.setString(value.ifBlank { null })
        }

    var simklAccessToken: String
        get() = Key.SIMKL_ACCESS_TOKEN.getString().orEmpty()
        set(value) {
            Key.SIMKL_ACCESS_TOKEN.setString(value.ifBlank { null })
        }

    var openSubtitlesApiKey: String
        get() = Key.OPENSUBTITLES_API_KEY.getString().orEmpty()
        set(value) {
            Key.OPENSUBTITLES_API_KEY.setString(value.ifBlank { null })
        }

    var openSubtitlesJwt: String
        get() = Key.OPENSUBTITLES_JWT.getString().orEmpty()
        set(value) {
            Key.OPENSUBTITLES_JWT.setString(value.ifBlank { null })
        }

    var openSubtitlesUserAgent: String
        get() = Key.OPENSUBTITLES_USER_AGENT.getString().orEmpty()
        set(value) {
            Key.OPENSUBTITLES_USER_AGENT.setString(value.ifBlank { null })
        }

    /** Comma-separated ISO language codes for OpenSubtitles.com (e.g. en,de). */
    var openSubtitlesLanguages: String
        get() = Key.OPENSUBTITLES_LANGUAGES.getString() ?: "en,de"
        set(value) {
            Key.OPENSUBTITLES_LANGUAGES.setString(value.ifBlank { "en,de" })
        }

    /** Prefetch next episode onto Chromecast queue while casting. */
    var castQueueNextEpisode: Boolean
        get() = Key.CAST_QUEUE_NEXT_EPISODE.getBoolean() ?: true
        set(value) {
            Key.CAST_QUEUE_NEXT_EPISODE.setBoolean(value)
        }

    /** Progress report interval for Jellyfin/Plex (ms). Clamped 5s–60s at use site. */
    var selfHostProgressIntervalMs: Long
        get() = Key.SELF_HOST_PROGRESS_INTERVAL_MS.getLong() ?: 15_000L
        set(value) {
            Key.SELF_HOST_PROGRESS_INTERVAL_MS.setLong(value.coerceIn(5_000L, 60_000L))
        }

    var traktClientSecret: String
        get() = Key.TRAKT_CLIENT_SECRET.getString().orEmpty()
        set(value) {
            Key.TRAKT_CLIENT_SECRET.setString(value.ifBlank { null })
        }

    var traktRefreshToken: String
        get() = Key.TRAKT_REFRESH_TOKEN.getString().orEmpty()
        set(value) {
            Key.TRAKT_REFRESH_TOKEN.setString(value.ifBlank { null })
        }

    var disabledPluginIds: Set<String>
        get() = Key.DISABLED_PLUGIN_IDS.getStringSet() ?: emptySet()
        set(value) {
            Key.DISABLED_PLUGIN_IDS.setStringSet(value.ifEmpty { null })
        }

    fun isPluginDisabled(pluginId: String): Boolean =
        disabledPluginIds.contains(pluginId)

    fun setPluginDisabled(pluginId: String, disabled: Boolean) {
        val next = disabledPluginIds.toMutableSet()
        if (disabled) next.add(pluginId) else next.remove(pluginId)
        disabledPluginIds = next
    }

    /** exo | mpv */
    var playerBackend: String
        get() = Key.PLAYER_BACKEND.getString() ?: "exo"
        set(value) {
            Key.PLAYER_BACKEND.setString(value.ifBlank { "exo" })
        }

    var autoplayBuffer: Long
        get() = Key.AUTOPLAY_BUFFER.getLong() ?: 3L
        set(value) {
            Key.AUTOPLAY_BUFFER.setLong(value)
        }

    var serverAutoSubtitlesDisabled: Boolean
        get() = Key.SERVER_AUTO_SUBTITLES_DISABLED.getBoolean() ?: true
        set(value) {
            Key.SERVER_AUTO_SUBTITLES_DISABLED.setBoolean(value)
        }

    /**
     * Locked Cinema-Ink look for the active profile. Legacy / retired theme ids are
     * collapsed via [ThemeManager.resolveLook] so Settings never half-mix accents.
     */
    var selectedTheme: String
        get() = ThemeManager.resolveLook(
            profileScopedString(Key.SELECTED_THEME)
                ?: Key.SELECTED_THEME.getString(),
        )
        set(value) = setProfileScopedString(
            Key.SELECTED_THEME,
            ThemeManager.resolveLook(value),
        )

    /**
     * Retired with Looks Locked — OLED half-mix is ignored. Setter clears the flag.
     */
    var oledMode: Boolean
        get() = false
        set(value) {
            Key.OLED_MODE.setBoolean(false)
        }

    /**
     * Accessibility: bump Configuration.fontScale (see [AccessibilityPreferences.wrap]).
     * Device-wide; requires activity restart like changing Looks.
     */
    var largeText: Boolean
        get() = Key.LARGE_TEXT.getBoolean() ?: false
        set(value) = Key.LARGE_TEXT.setBoolean(value)

    /**
     * Accessibility: high-contrast theme overlay on top of the active locked look.
     * Device-wide; requires activity restart like changing Looks.
     */
    var highContrast: Boolean
        get() = Key.HIGH_CONTRAST.getBoolean() ?: false
        set(value) = Key.HIGH_CONTRAST.setBoolean(value)

    /**
     * TV Leanback poster density: [com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.COMPACT]
     * (more tiles) or [com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.COMFORT]
     * (larger posters). Device-wide; requires activity restart like Looks.
     */
    var leanbackDensity: String
        get() = Key.LEANBACK_DENSITY.getString()
            ?: com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.DEFAULT
        set(value) = Key.LEANBACK_DENSITY.setString(
            com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.valueOf(
                com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.resolve(value),
            ),
        )

    /**
     * Explicit Stick Energy Mode toggle, or null when never set (Fire TV auto-on —
     * see [com.dskja.betterstreamflix.ui.StickEnergyMode.isEnabled]).
     */
    val stickEnergyModeExplicit: Boolean?
        get() = Key.STICK_ENERGY_MODE.getBoolean()

    /**
     * Fire Stick energy mode: fewer parallel providers, SW-decoder bias, less UI motion.
     * Getter is the effective value; setter persists an explicit choice.
     */
    var stickEnergyMode: Boolean
        get() = com.dskja.betterstreamflix.ui.StickEnergyMode.isEnabled()
        set(value) = Key.STICK_ENERGY_MODE.setBoolean(value)

    /** Off-by-default complete mobile shell redesign. Requires activity restart. */
    var experimentalNewAppDesign: Boolean
        get() = Key.EXPERIMENTAL_NEW_APP_DESIGN.getBoolean() ?: false
        set(value) = Key.EXPERIMENTAL_NEW_APP_DESIGN.setBoolean(value)

    /**
     * Opt-in React/Vite home shell (DEBUG + Lumina only). Default off so the native
     * Featured ViewPager carousel stays active.
     */
    var experimentalReactHome: Boolean
        get() = Key.EXPERIMENTAL_REACT_HOME.getBoolean() ?: false
        set(value) = Key.EXPERIMENTAL_REACT_HOME.setBoolean(value)

    /** Lumina accent palette: crimson | ember | aurora | slate */
    var experimentalLuminaAccent: String
        get() = Key.EXPERIMENTAL_LUMINA_ACCENT.getString() ?: "crimson"
        set(value) = Key.EXPERIMENTAL_LUMINA_ACCENT.setString(value)

    var experimentalLuminaPureBlack: Boolean
        get() = Key.EXPERIMENTAL_LUMINA_PURE_BLACK.getBoolean() ?: false
        set(value) = Key.EXPERIMENTAL_LUMINA_PURE_BLACK.setBoolean(value)

    var experimentalLuminaDynamicColors: Boolean
        get() = Key.EXPERIMENTAL_LUMINA_DYNAMIC_COLORS.getBoolean() ?: false
        set(value) = Key.EXPERIMENTAL_LUMINA_DYNAMIC_COLORS.setBoolean(value)

    var experimentalLuminaNavAutoHide: Boolean
        get() = Key.EXPERIMENTAL_LUMINA_NAV_AUTO_HIDE.getBoolean() ?: true
        set(value) = Key.EXPERIMENTAL_LUMINA_NAV_AUTO_HIDE.setBoolean(value)

    var experimentalLuminaHeroParallax: Boolean
        get() = Key.EXPERIMENTAL_LUMINA_HERO_PARALLAX.getBoolean() ?: true
        set(value) = Key.EXPERIMENTAL_LUMINA_HERO_PARALLAX.setBoolean(value)

    var experimentalLuminaReducedGlass: Boolean
        get() = Key.EXPERIMENTAL_LUMINA_REDUCED_GLASS.getBoolean() ?: false
        set(value) = Key.EXPERIMENTAL_LUMINA_REDUCED_GLASS.setBoolean(value)

    /**
     * When true, the startup support presentation is never shown.
     * Default false → show once until the user opts out.
     */
    var neverShowSupportOnStart: Boolean
        get() = Key.NEVER_SHOW_SUPPORT_ON_START.getBoolean() ?: false
        set(value) = Key.NEVER_SHOW_SUPPORT_ON_START.setBoolean(value)

    /**
     * Permanent hide for the home support card (legacy “dismiss forever” / never-again).
     * Soft dismiss uses [homeSupportCardLastDismissedAtMs] + cool-down instead.
     */
    var homeSupportCardDismissed: Boolean
        get() = Key.HOME_SUPPORT_CARD_DISMISSED.getBoolean() ?: false
        set(value) = Key.HOME_SUPPORT_CARD_DISMISSED.setBoolean(value)

    /** Epoch ms of last soft dismiss of the home Support card (0 = never). */
    var homeSupportCardLastDismissedAtMs: Long
        get() = Key.HOME_SUPPORT_CARD_LAST_DISMISSED_AT_MS.getLong() ?: 0L
        set(value) = Key.HOME_SUPPORT_CARD_LAST_DISMISSED_AT_MS.setLong(value)

    /** Epoch ms when the home Support card was last presented (0 = never). */
    var homeSupportCardLastShownAtMs: Long
        get() = Key.HOME_SUPPORT_CARD_LAST_SHOWN_AT_MS.getLong() ?: 0L
        set(value) = Key.HOME_SUPPORT_CARD_LAST_SHOWN_AT_MS.setLong(value)

    var homeSupportCardShowCount: Int
        get() = Key.HOME_SUPPORT_CARD_SHOW_COUNT.getInt() ?: 0
        set(value) = Key.HOME_SUPPORT_CARD_SHOW_COUNT.setInt(value)

    /**
     * Weekly Watchlist Digest home card (5 new episodes from My List → one-tap queue).
     * Default on — soft dismiss uses cool-down; hard hide via [homeWatchlistDigestDismissed].
     */
    var homeWatchlistDigestEnabled: Boolean
        get() = Key.HOME_WATCHLIST_DIGEST_ENABLED.getBoolean() ?: true
        set(value) = Key.HOME_WATCHLIST_DIGEST_ENABLED.setBoolean(value)

    var homeWatchlistDigestDismissed: Boolean
        get() = Key.HOME_WATCHLIST_DIGEST_DISMISSED.getBoolean() ?: false
        set(value) = Key.HOME_WATCHLIST_DIGEST_DISMISSED.setBoolean(value)

    var homeWatchlistDigestLastDismissedAtMs: Long
        get() = Key.HOME_WATCHLIST_DIGEST_LAST_DISMISSED_AT_MS.getLong() ?: 0L
        set(value) = Key.HOME_WATCHLIST_DIGEST_LAST_DISMISSED_AT_MS.setLong(value)

    var homeWatchlistDigestLastShownAtMs: Long
        get() = Key.HOME_WATCHLIST_DIGEST_LAST_SHOWN_AT_MS.getLong() ?: 0L
        set(value) = Key.HOME_WATCHLIST_DIGEST_LAST_SHOWN_AT_MS.setLong(value)

    var homeWatchlistDigestShowCount: Int
        get() = Key.HOME_WATCHLIST_DIGEST_SHOW_COUNT.getInt() ?: 0
        set(value) = Key.HOME_WATCHLIST_DIGEST_SHOW_COUNT.setInt(value)

    var homeWatchlistDigestLastQueuedAtMs: Long
        get() = Key.HOME_WATCHLIST_DIGEST_LAST_QUEUED_AT_MS.getLong() ?: 0L
        set(value) = Key.HOME_WATCHLIST_DIGEST_LAST_QUEUED_AT_MS.setLong(value)

    /**
     * Thanks After Binge — soft Support nudge after a season finale.
     * Permanent opt-out; soft shows use a long cool-down (see ThanksAfterBingePolicy).
     */
    var neverShowThanksAfterBinge: Boolean
        get() = Key.NEVER_SHOW_THANKS_AFTER_BINGE.getBoolean() ?: false
        set(value) = Key.NEVER_SHOW_THANKS_AFTER_BINGE.setBoolean(value)

    var thanksAfterBingeLastShownAtMs: Long
        get() = Key.THANKS_AFTER_BINGE_LAST_SHOWN_AT_MS.getLong() ?: 0L
        set(value) = Key.THANKS_AFTER_BINGE_LAST_SHOWN_AT_MS.setLong(value)

    var thanksAfterBingeLastDismissedAtMs: Long
        get() = Key.THANKS_AFTER_BINGE_LAST_DISMISSED_AT_MS.getLong() ?: 0L
        set(value) = Key.THANKS_AFTER_BINGE_LAST_DISMISSED_AT_MS.setLong(value)

    var thanksAfterBingeShowCount: Int
        get() = Key.THANKS_AFTER_BINGE_SHOW_COUNT.getInt() ?: 0
        set(value) = Key.THANKS_AFTER_BINGE_SHOW_COUNT.setInt(value)

    /** Offline Expiry Nudge — last soft prompt timestamps (see OfflineExpiryPolicy). */
    var offlineExpiryNudgeLastShownAtMs: Long
        get() = Key.OFFLINE_EXPIRY_NUDGE_LAST_SHOWN_AT_MS.getLong() ?: 0L
        set(value) = Key.OFFLINE_EXPIRY_NUDGE_LAST_SHOWN_AT_MS.setLong(value)

    var offlineExpiryNudgeLastDismissedAtMs: Long
        get() = Key.OFFLINE_EXPIRY_NUDGE_LAST_DISMISSED_AT_MS.getLong() ?: 0L
        set(value) = Key.OFFLINE_EXPIRY_NUDGE_LAST_DISMISSED_AT_MS.setLong(value)

    /** Unique finished episodes counted toward the Support tip threshold. */
    var supportEpisodesWatchedCount: Int
        get() = Key.SUPPORT_EPISODES_WATCHED_COUNT.getInt() ?: 0
        set(value) = Key.SUPPORT_EPISODES_WATCHED_COUNT.setInt(value)

    /** Episode ids already counted (bounded set; see SupportPromptPolicy). */
    var supportEpisodeIdsCounted: Set<String>
        get() = Key.SUPPORT_EPISODE_IDS_COUNTED.getStringSet() ?: emptySet()
        set(value) = Key.SUPPORT_EPISODE_IDS_COUNTED.setStringSet(value.ifEmpty { null })

    /** Epoch ms of last Support startup soft-prompt (0 = never). */
    var supportStartupLastShownAtMs: Long
        get() = Key.SUPPORT_STARTUP_LAST_SHOWN_AT_MS.getLong() ?: 0L
        set(value) = Key.SUPPORT_STARTUP_LAST_SHOWN_AT_MS.setLong(value)

    var supportStartupShowCount: Int
        get() = Key.SUPPORT_STARTUP_SHOW_COUNT.getInt() ?: 0
        set(value) = Key.SUPPORT_STARTUP_SHOW_COUNT.setInt(value)

    var supportHubLastVisitedAtMs: Long
        get() = Key.SUPPORT_HUB_LAST_VISITED_AT_MS.getLong() ?: 0L
        set(value) = Key.SUPPORT_HUB_LAST_VISITED_AT_MS.setLong(value)

    var supportHubVisitCount: Int
        get() = Key.SUPPORT_HUB_VISIT_COUNT.getInt() ?: 0
        set(value) = Key.SUPPORT_HUB_VISIT_COUNT.setInt(value)

    /** Persisted soft thanks pending flag (survives process death). */
    var supportAppreciationPending: Boolean
        get() = Key.SUPPORT_APPRECIATION_PENDING.getBoolean() ?: false
        set(value) = Key.SUPPORT_APPRECIATION_PENDING.setBoolean(value)

    var supportAppreciationPendingAtMs: Long
        get() = Key.SUPPORT_APPRECIATION_PENDING_AT_MS.getLong() ?: 0L
        set(value) = Key.SUPPORT_APPRECIATION_PENDING_AT_MS.setLong(value)

    /** Soft Telegram join gate — permanent unlock for current channel version. */
    var telegramJoinGateUnlocked: Boolean
        get() = Key.TELEGRAM_JOIN_GATE_UNLOCKED.getBoolean() ?: false
        set(value) = Key.TELEGRAM_JOIN_GATE_UNLOCKED.setBoolean(value)

    var telegramJoinGateUnlockedAtMs: Long
        get() = Key.TELEGRAM_JOIN_GATE_UNLOCKED_AT_MS.getLong() ?: 0L
        set(value) = Key.TELEGRAM_JOIN_GATE_UNLOCKED_AT_MS.setLong(value)

    var telegramJoinGateOpenedAtMs: Long
        get() = Key.TELEGRAM_JOIN_GATE_OPENED_AT_MS.getLong() ?: 0L
        set(value) = Key.TELEGRAM_JOIN_GATE_OPENED_AT_MS.setLong(value)

    var telegramJoinGateOpenCount: Int
        get() = Key.TELEGRAM_JOIN_GATE_OPEN_COUNT.getInt() ?: 0
        set(value) = Key.TELEGRAM_JOIN_GATE_OPEN_COUNT.setInt(value)

    var telegramJoinGateConfirmCount: Int
        get() = Key.TELEGRAM_JOIN_GATE_CONFIRM_COUNT.getInt() ?: 0
        set(value) = Key.TELEGRAM_JOIN_GATE_CONFIRM_COUNT.setInt(value)

    var telegramJoinGateChannelVersion: Int
        get() = Key.TELEGRAM_JOIN_GATE_CHANNEL_VERSION.getInt() ?: 0
        set(value) = Key.TELEGRAM_JOIN_GATE_CHANNEL_VERSION.setInt(value)

    var catalogSortMode: CatalogSortMode
        get() = CatalogSortMode.fromKey(Key.CATALOG_SORT_MODE.getString())
        set(value) = Key.CATALOG_SORT_MODE.setString(value.name)

    var searchSortMode: SearchSortMode
        get() = SearchSortMode.fromKey(Key.SEARCH_SORT_MODE.getString())
        set(value) = Key.SEARCH_SORT_MODE.setString(value.name)

    /** Null means show all years. */
    var searchYearFilter: Int?
        get() = Key.SEARCH_YEAR_FILTER.getInt()
        set(value) = Key.SEARCH_YEAR_FILTER.setInt(value)

    var castEnabled: Boolean
        get() = Key.CAST_ENABLED.getBoolean() ?: true
        set(value) = Key.CAST_ENABLED.setBoolean(value)

    /** Forward selected text tracks to Chromecast when available. */
    var castSubtitlesEnabled: Boolean
        get() = Key.CAST_SUBTITLES_ENABLED.getBoolean() ?: true
        set(value) = Key.CAST_SUBTITLES_ENABLED.setBoolean(value)

    /** Keep phone screen on briefly while a Cast session is active. */
    var castKeepScreenAwake: Boolean
        get() = Key.CAST_KEEP_SCREEN_AWAKE.getBoolean() ?: false
        set(value) = Key.CAST_KEEP_SCREEN_AWAKE.setBoolean(value)

    var tmdbApiKey: String
        get() = Key.TMDB_API_KEY.getString() ?: ""
        set(value) {
            Key.TMDB_API_KEY.setString(value)
            TMDb3.rebuildService()
        }
    var enableTmdb: Boolean
        get() = Key.ENABLE_TMDB.getBoolean() ?: true
        set(value) {
            Key.ENABLE_TMDB.setBoolean(value)
            TMDb3.rebuildService()
            if (value) {
                runCatching {
                    ArtworkRepairScheduler.schedule(BetterStreamflixApp.instance, currentProvider)
                }
            }
        }

    /** Master toggle for TMDb title logos (still requires [enableTmdb]). */
    var enableTmdbLogos: Boolean
        get() = Key.ENABLE_TMDB_LOGOS.getBoolean() ?: true
        set(value) {
            Key.ENABLE_TMDB_LOGOS.setBoolean(value)
        }

    /** `original_first` (default) or `w1280_first`. */
    var tmdbLogoQuality: String
        get() = Key.TMDB_LOGO_QUALITY.getString() ?: "original_first"
        set(value) {
            Key.TMDB_LOGO_QUALITY.setString(value)
        }

    var tmdbLogoMissTtlHours: Int
        get() = Key.TMDB_LOGO_MISS_TTL_HOURS.getInt() ?: 6
        set(value) {
            Key.TMDB_LOGO_MISS_TTL_HOURS.setInt(value.coerceIn(1, 168))
            applyTmdbLogoTtls()
        }

    var tmdbLogoHitTtlHours: Int
        get() = Key.TMDB_LOGO_HIT_TTL_HOURS.getInt() ?: 24
        set(value) {
            Key.TMDB_LOGO_HIT_TTL_HOURS.setInt(value.coerceIn(1, 336))
            applyTmdbLogoTtls()
        }

    fun applyTmdbLogoTtls() {
        runCatching {
            com.dskja.betterstreamflix.logo.TmdbLogoCache.configureTtls(
                missTtlMs = tmdbLogoMissTtlHours * 60L * 60L * 1000L,
                hitTtlMs = tmdbLogoHitTtlHours * 60L * 60L * 1000L,
            )
        }
    }

    /** When false, Home hides the Continue Watching row. */
    var showContinueWatching: Boolean
        get() = profileScopedBoolean(Key.SHOW_CONTINUE_WATCHING) ?: true
        set(value) {
            setProfileScopedBoolean(Key.SHOW_CONTINUE_WATCHING, value)
        }

    /** When false, Home hides the Recently Watched row. */
    var showRecentlyWatched: Boolean
        get() = profileScopedBoolean(Key.SHOW_RECENTLY_WATCHED) ?: true
        set(value) {
            setProfileScopedBoolean(Key.SHOW_RECENTLY_WATCHED, value)
        }

    /** When false, Home hides the Next Episodes row. */
    var showNextEpisodes: Boolean
        get() = profileScopedBoolean(Key.SHOW_NEXT_EPISODES) ?: true
        set(value) {
            setProfileScopedBoolean(Key.SHOW_NEXT_EPISODES, value)
        }

    /** When false, Home hides Smart Collections (Chill / Kids / DE audio). */
    var showSmartCollections: Boolean
        get() = profileScopedBoolean(Key.SHOW_SMART_COLLECTIONS) ?: true
        set(value) {
            setProfileScopedBoolean(Key.SHOW_SMART_COLLECTIONS, value)
        }

    /**
     * Adult opt-in for Spoiler Shield (Kids profiles always shield).
     * Profile-scoped — blur episode titles/thumbs until unlock.
     */
    var spoilerShieldEnabled: Boolean
        get() = profileScopedBoolean(Key.SPOILER_SHIELD_ENABLED) ?: false
        set(value) {
            setProfileScopedBoolean(Key.SPOILER_SHIELD_ENABLED, value)
        }

    /** When false, Home hides the watchlist “New this week” calendar strip. */
    var showWatchlistWeek: Boolean
        get() = profileScopedBoolean(Key.SHOW_WATCHLIST_WEEK) ?: true
        set(value) {
            setProfileScopedBoolean(Key.SHOW_WATCHLIST_WEEK, value)
        }

    /**
     * Remix Home block order — comma keys `continue,live,collections`
     * (profile-scoped). See [com.dskja.betterstreamflix.home.HomeRemix].
     */
    var homeShelfBlockOrder: String
        get() = profileScopedString(Key.HOME_SHELF_BLOCK_ORDER).orEmpty()
        set(value) {
            setProfileScopedString(Key.HOME_SHELF_BLOCK_ORDER, value.trim())
        }

    /**
     * JSON array of up to 3 Home shelf ids pinned under Featured (Shelf Pinning).
     * Profile-scoped. Applied after Remix Home block order.
     */
    var homeShelfPinsJson: String
        get() = profileScopedString(Key.HOME_SHELF_PINS_JSON).orEmpty()
        set(value) = setProfileScopedString(Key.HOME_SHELF_PINS_JSON, value.trim())

    /**
     * Series Fast-Forward: when true, Next Episodes / Favorite TV shelves keep
     * only titles new since the last Home visit watermark.
     */
    var seriesFastForwardEnabled: Boolean
        get() = profileScopedBoolean(Key.SERIES_FAST_FORWARD_ENABLED) ?: false
        set(value) {
            setProfileScopedBoolean(Key.SERIES_FAST_FORWARD_ENABLED, value)
        }

    /** Epoch ms of the last Home [onPause] visit commit (per profile). */
    var homeLastVisitAtMs: Long
        get() = profileScopedLong(Key.HOME_LAST_VISIT_AT_MS) ?: 0L
        set(value) {
            setProfileScopedLong(Key.HOME_LAST_VISIT_AT_MS, value.coerceAtLeast(0L))
        }

    /** Unfiltered Next Episode ids snapshotted on the last Home pause. */
    var homeVisitNextEpisodeIds: Set<String>
        get() = profileScopedStringSet(Key.HOME_VISIT_NEXT_EPISODE_IDS)
        set(value) {
            setProfileScopedStringSet(Key.HOME_VISIT_NEXT_EPISODE_IDS, value)
        }

    /** Unfiltered Favorite TV show ids snapshotted on the last Home pause. */
    var homeVisitFavoriteTvIds: Set<String>
        get() = profileScopedStringSet(Key.HOME_VISIT_FAVORITE_TV_IDS)
        set(value) {
            setProfileScopedStringSet(Key.HOME_VISIT_FAVORITE_TV_IDS, value)
        }

    /**
     * Kids Bedtime master switch (profile-scoped). Only applies while the
     * active profile is Kids — see [com.dskja.betterstreamflix.kids.KidsBedtime].
     */
    var kidsBedtimeEnabled: Boolean
        get() = profileScopedBoolean(Key.KIDS_BEDTIME_ENABLED) ?: false
        set(value) {
            setProfileScopedBoolean(Key.KIDS_BEDTIME_ENABLED, value)
        }

    /** Local minute-of-day for bedtime (0–1439). Default 20:00. */
    var kidsBedtimeMinuteOfDay: Int
        get() = profileScopedInt(Key.KIDS_BEDTIME_MINUTE_OF_DAY)
            ?: com.dskja.betterstreamflix.kids.KidsBedtime.DEFAULT_MINUTE_OF_DAY
        set(value) {
            setProfileScopedInt(
                Key.KIDS_BEDTIME_MINUTE_OF_DAY,
                com.dskja.betterstreamflix.kids.KidsBedtime.clampMinuteOfDay(value),
            )
        }

    /** True after auto-stop until a parental/profile PIN clears it. */
    var kidsBedtimeLocked: Boolean
        get() = profileScopedBoolean(Key.KIDS_BEDTIME_LOCKED) ?: false
        set(value) {
            setProfileScopedBoolean(Key.KIDS_BEDTIME_LOCKED, value)
        }

    /** Wall-clock bypass after PIN unlock (until wake). */
    var kidsBedtimeBypassUntilMs: Long
        get() = profileScopedLong(Key.KIDS_BEDTIME_BYPASS_UNTIL_MS) ?: 0L
        set(value) {
            setProfileScopedLong(Key.KIDS_BEDTIME_BYPASS_UNTIL_MS, value.coerceAtLeast(0L))
        }

    /** When true, chronically brittle providers appear in the provider picker. */
    var showQuarantinedProviders: Boolean
        get() = Key.SHOW_QUARANTINED_PROVIDERS.getBoolean() ?: false
        set(value) {
            Key.SHOW_QUARANTINED_PROVIDERS.setBoolean(value)
        }

    /**
     * When true, Vavoo VOD language variants appear as selectable catalog providers
     * in the picker. Vavoo VOD remains available inside TMDb DE aggregation regardless.
     */
    var showVavooVodProviders: Boolean
        get() = Key.SHOW_VAVOO_VOD_PROVIDERS.getBoolean() ?: false
        set(value) {
            Key.SHOW_VAVOO_VOD_PROVIDERS.setBoolean(value)
        }

    enum class LibraryScope(val key: String) {
        /** Favorites / continue watching only from the active provider DB. */
        PER_PROVIDER("per_provider"),
        /** Favorites / continue watching merged from every provider DB on disk. */
        CROSS_PROVIDER("cross_provider");

        companion object {
            fun fromKey(key: String?): LibraryScope =
                entries.firstOrNull { it.key == key } ?: PER_PROVIDER
        }
    }

    var libraryScope: LibraryScope
        get() = LibraryScope.fromKey(profileScopedString(Key.LIBRARY_SCOPE))
        set(value) {
            setProfileScopedString(Key.LIBRARY_SCOPE, value.key)
        }

    val isCrossProviderLibrary: Boolean
        get() = libraryScope == LibraryScope.CROSS_PROVIDER

    /**
     * Stored parental PIN hash (or legacy plaintext until first successful verify).
     * Assigning a raw digit PIN always hashes via [PinHasher] before write.
     * Assign `""` to clear.
     */
    var parentalControlPin: String
        get() = profileScopedString(Key.PARENTAL_CONTROL_PIN) ?: ""
        set(value) {
            setProfileScopedString(
                Key.PARENTAL_CONTROL_PIN,
                hashParentalPinForStorage(value.trim(), ProfileManager.parentalPinSalt()),
            )
        }

    /**
     * Stored admin PIN hash (or legacy plaintext until first successful verify).
     * Assigning a raw digit PIN always hashes via [PinHasher] before write.
     * Assign `""` to clear.
     */
    var parentalControlAdminPin: String
        get() = profileScopedString(Key.PARENTAL_CONTROL_ADMIN_PIN) ?: ""
        set(value) {
            setProfileScopedString(
                Key.PARENTAL_CONTROL_ADMIN_PIN,
                hashParentalPinForStorage(value.trim(), ProfileManager.adminPinSalt()),
            )
        }

    var parentalControlMaxAge: Int?
        get() = profileScopedInt(Key.PARENTAL_CONTROL_MAX_AGE)
        set(value) {
            setProfileScopedInt(Key.PARENTAL_CONTROL_MAX_AGE, value)
        }

    var parentalControlFailedAttempts: Int
        get() = profileScopedInt(Key.PARENTAL_CONTROL_FAILED_ATTEMPTS) ?: 0
        set(value) {
            setProfileScopedInt(Key.PARENTAL_CONTROL_FAILED_ATTEMPTS, value)
        }

    var parentalControlLockedUntilMillis: Long
        get() = profileScopedLong(Key.PARENTAL_CONTROL_LOCKED_UNTIL) ?: 0L
        set(value) {
            setProfileScopedLong(Key.PARENTAL_CONTROL_LOCKED_UNTIL, value)
        }

    var parentalControlHardLocked: Boolean
        get() = profileScopedBoolean(Key.PARENTAL_CONTROL_HARD_LOCKED) ?: false
        set(value) {
            setProfileScopedBoolean(Key.PARENTAL_CONTROL_HARD_LOCKED, value)
        }

    val isParentalControlActive: Boolean
        get() = enableTmdb && parentalControlPin.isNotBlank() && parentalControlMaxAge != null

    val isParentalControlTemporarilyLocked: Boolean
        get() = parentalControlLockedUntilMillis > System.currentTimeMillis()

    val parentalControlLockRemainingMillis: Long
        get() = (parentalControlLockedUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * Verify the parental PIN. Legacy plaintext values are accepted once and
     * re-hashed on success (lazy migration).
     */
    fun verifyParentalControlPin(entered: String): Boolean {
        val stored = parentalControlPin
        if (stored.isBlank()) return false
        if (PinHasher.looksLikePlaintextPin(stored)) {
            if (stored != entered) return false
            // Lazy migrate: setter hashes raw PINs.
            parentalControlPin = entered
            return true
        }
        return PinHasher.verify(entered, stored, ProfileManager.parentalPinSalt())
    }

    /** Verify the admin recovery PIN with the same hash / migrate rules. */
    fun verifyParentalControlAdminPin(entered: String): Boolean {
        val stored = parentalControlAdminPin
        if (stored.isBlank()) return false
        if (PinHasher.looksLikePlaintextPin(stored)) {
            if (stored != entered) return false
            parentalControlAdminPin = entered
            return true
        }
        return PinHasher.verify(entered, stored, ProfileManager.adminPinSalt())
    }

    /**
     * Kids profiles must keep at least one exit PIN. Clearing the parental PIN
     * is blocked when kids is active and the profile has no PIN of its own.
     */
    fun canClearParentalPinWhileKidsActive(): Boolean {
        if (!ProfileManager.isKidsActive()) return true
        return ProfileManager.activeProfile()?.pinHash != null
    }

    fun registerParentalPinSuccess() {
        parentalControlFailedAttempts = 0
        parentalControlLockedUntilMillis = 0L
        parentalControlHardLocked = false
    }

    fun registerParentalPinFailure(nowMillis: Long = System.currentTimeMillis()) {
        val attempts = parentalControlFailedAttempts + 1
        parentalControlFailedAttempts = attempts

        when {
            attempts >= 7 && parentalControlAdminPin.isNotBlank() -> {
                parentalControlHardLocked = true
                parentalControlLockedUntilMillis = 0L
            }
            attempts >= 7 -> {
                parentalControlLockedUntilMillis = nowMillis + 24L * 60L * 60L * 1000L
            }
            attempts >= 5 -> {
                parentalControlLockedUntilMillis = nowMillis + 30L * 60L * 1000L
            }
            attempts >= 3 -> {
                parentalControlLockedUntilMillis = nowMillis + 5L * 60L * 1000L
            }
        }
    }

    var updateCheckEnabled: Boolean
        get() = Key.UPDATE_CHECK_ENABLED.getBoolean() ?: true
        set(value) {
            Key.UPDATE_CHECK_ENABLED.setBoolean(value)
        }

    fun unlockParentalControls() {
        parentalControlFailedAttempts = 0
        parentalControlLockedUntilMillis = 0L
        parentalControlHardLocked = false
    }

    var subdlApiKey: String
        get() = Key.SUBDL_API_KEY.getString() ?: ""
        set(value) {
            Key.SUBDL_API_KEY.setString(value)
        }

    var bypassWsAdvertisedHost: String
        get() = Key.BYPASS_WS_ADVERTISED_HOST.getString() ?: ""
        set(value) {
            Key.BYPASS_WS_ADVERTISED_HOST.setString(value.trim())
        }

    /**
     * SerienStream / Cloudflare session cookie header (`name=value; …`).
     * Set after WebView login — DuckDuckGo/browser noise is stripped on write.
     */
    var serienStreamSessionCookies: String
        get() = Key.SERIENSTREAM_SESSION_COOKIES.getString() ?: ""
        set(value) {
            val cleaned = com.dskja.betterstreamflix.player.SerienStreamBypassHelper
                .sanitizeSessionCookies(value)
            Key.SERIENSTREAM_SESSION_COOKIES.setString(cleaned)
        }

    /** Optional display name scraped from SerienStream `/account` after validation. */
    var serienStreamSessionDisplayName: String
        get() = Key.SERIENSTREAM_SESSION_DISPLAY_NAME.getString() ?: ""
        set(value) = Key.SERIENSTREAM_SESSION_DISPLAY_NAME.setString(value.trim())

    /**
     * True after WebView HTML proved a real login (Laravel session without remember-me)
     * or after a successful account validation probe.
     */
    var serienStreamAccountConfirmed: Boolean
        get() = Key.SERIENSTREAM_ACCOUNT_CONFIRMED.getBoolean() ?: false
        set(value) = Key.SERIENSTREAM_ACCOUNT_CONFIRMED.setBoolean(value)

    /** Epoch millis of the last SerienStream session validation probe (0 = never). */
    var serienStreamSessionValidatedAtMs: Long
        get() = Key.SERIENSTREAM_SESSION_VALIDATED_AT.getLong() ?: 0L
        set(value) = Key.SERIENSTREAM_SESSION_VALIDATED_AT.setLong(value.coerceAtLeast(0L))

    var serienStreamSessionValidatedOk: Boolean
        get() = Key.SERIENSTREAM_SESSION_VALIDATED_OK.getBoolean() ?: false
        set(value) = Key.SERIENSTREAM_SESSION_VALIDATED_OK.setBoolean(value)

    /**
     * AniWorld session cookie header (`name=value; …`), separate from SerienStream.
     */
    var aniWorldSessionCookies: String
        get() = Key.ANIWORLD_SESSION_COOKIES.getString() ?: ""
        set(value) {
            val cleaned = com.dskja.betterstreamflix.player.SerienStreamBypassHelper
                .sanitizeSessionCookies(value)
            Key.ANIWORLD_SESSION_COOKIES.setString(cleaned)
        }

    var aniWorldSessionDisplayName: String
        get() = Key.ANIWORLD_SESSION_DISPLAY_NAME.getString() ?: ""
        set(value) = Key.ANIWORLD_SESSION_DISPLAY_NAME.setString(value.trim())

    var aniWorldAccountConfirmed: Boolean
        get() = Key.ANIWORLD_ACCOUNT_CONFIRMED.getBoolean() ?: false
        set(value) = Key.ANIWORLD_ACCOUNT_CONFIRMED.setBoolean(value)

    var aniWorldSessionValidatedAtMs: Long
        get() = Key.ANIWORLD_SESSION_VALIDATED_AT.getLong() ?: 0L
        set(value) = Key.ANIWORLD_SESSION_VALIDATED_AT.setLong(value.coerceAtLeast(0L))

    var aniWorldSessionValidatedOk: Boolean
        get() = Key.ANIWORLD_SESSION_VALIDATED_OK.getBoolean() ?: false
        set(value) = Key.ANIWORLD_SESSION_VALIDATED_OK.setBoolean(value)

    enum class PlayerResize(
        val stringRes: Int,
        val resizeMode: Int,
    ) {
        Fit(R.string.player_aspect_ratio_fit, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        Fill(R.string.player_aspect_ratio_fill, AspectRatioFrameLayout.RESIZE_MODE_FILL),
        Zoom(R.string.player_aspect_ratio_zoom, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
        Stretch43(R.string.player_aspect_ratio_zoom_4_3, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        StretchVertical(R.string.player_aspect_ratio_stretch_vertical, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        SuperZoom(R.string.player_aspect_ratio_super_zoom, AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    var playerResize: PlayerResize
        get() = PlayerResize.entries.find { it.resizeMode == Key.PLAYER_RESIZE.getInt() && it.name == Key.PLAYER_RESIZE_NAME.getString() }
            ?: PlayerResize.entries.find { it.resizeMode == Key.PLAYER_RESIZE.getInt() }
            ?: PlayerResize.Fit
        set(value) {
            Key.PLAYER_RESIZE.setInt(value.resizeMode)
            Key.PLAYER_RESIZE_NAME.setString(value.name)
        }

    /**
     * Caption colors / edge for the active profile (Subtitle Studio light).
     * Reads profile-scoped keys first, then legacy global keys, then defaults.
     */
    var captionStyle: CaptionStyleCompat
        get() = CaptionStyleCompat(
            profileScopedInt(Key.CAPTION_STYLE_FONT_COLOR)
                ?: Key.CAPTION_STYLE_FONT_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.foregroundColor,
            profileScopedInt(Key.CAPTION_STYLE_BACKGROUND_COLOR)
                ?: Key.CAPTION_STYLE_BACKGROUND_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.backgroundColor,
            profileScopedInt(Key.CAPTION_STYLE_WINDOW_COLOR)
                ?: Key.CAPTION_STYLE_WINDOW_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.windowColor,
            profileScopedInt(Key.CAPTION_STYLE_EDGE_TYPE)
                ?: Key.CAPTION_STYLE_EDGE_TYPE.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.edgeType,
            profileScopedInt(Key.CAPTION_STYLE_EDGE_COLOR)
                ?: Key.CAPTION_STYLE_EDGE_COLOR.getInt()
                ?: PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.edgeColor,
            PlayerSettingsView.Settings.Subtitle.Style.DEFAULT.typeface
        )
        set(value) {
            setProfileScopedInt(Key.CAPTION_STYLE_FONT_COLOR, value.foregroundColor)
            setProfileScopedInt(Key.CAPTION_STYLE_BACKGROUND_COLOR, value.backgroundColor)
            setProfileScopedInt(Key.CAPTION_STYLE_WINDOW_COLOR, value.windowColor)
            setProfileScopedInt(Key.CAPTION_STYLE_EDGE_TYPE, value.edgeType)
            setProfileScopedInt(Key.CAPTION_STYLE_EDGE_COLOR, value.edgeColor)
        }

    var captionMargin: Int
        get() = profileScopedInt(Key.CAPTION_STYLE_MARGIN)
            ?: Key.CAPTION_STYLE_MARGIN.getInt()
            ?: 24
        set(value) {
            setProfileScopedInt(Key.CAPTION_STYLE_MARGIN, value)
        }

    var qualityHeight: Int?
        get() = Key.QUALITY_HEIGHT.getInt()
        set(value) {
            Key.QUALITY_HEIGHT.setInt(value)
        }

    var subtitleName: String?
        get() = Key.SUBTITLE_NAME.getString()
        set(value) = Key.SUBTITLE_NAME.setString(value)
    var streamingcommunityDomain: String
        get() {
            if (!::prefs.isInitialized) {
                Log.e(TAG, "streamingcommunityDomain GET: prefs is not initialized")
                return DEFAULT_STREAMINGCOMMUNITY_DOMAIN
            }
            val storedValue = prefs.getString(Key.STREAMINGCOMMUNITY_DOMAIN.name, null)
                ?.trim()
                ?.removePrefix("https://")
                ?.removePrefix("http://")
                ?.trimEnd('/')
            if (storedValue.isNullOrEmpty()) return DEFAULT_STREAMINGCOMMUNITY_DOMAIN
            // streamingunity.cc now redirects to streamingunity.win; migrate stale domains.
            if (storedValue.removePrefix("www.").lowercase() in DEPRECATED_STREAMINGCOMMUNITY_DOMAINS) {
                with(prefs.edit()) {
                    putString(Key.STREAMINGCOMMUNITY_DOMAIN.name, DEFAULT_STREAMINGCOMMUNITY_DOMAIN)
                    apply()
                }
                clearProviderCache("StreamingCommunity")
                return DEFAULT_STREAMINGCOMMUNITY_DOMAIN
            }
            return storedValue
        }
        set(value) {
            val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.STREAMINGCOMMUNITY_DOMAIN.name, null) else null
            if (!::prefs.isInitialized) {
                Log.e(TAG, "streamingcommunityDomain SET: prefs is not initialized")
                return
            }

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("StreamingCommunity")
            }

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.STREAMINGCOMMUNITY_DOMAIN.name)
                } else {
                    putString(Key.STREAMINGCOMMUNITY_DOMAIN.name, value)
                }
                apply()
            }
        }

    var serienstreamDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_SERIENSTREAM_DOMAIN
            val storedValue = prefs.getString(Key.SERIENSTREAM_DOMAIN.name, null)
                ?.trim()
                ?.removePrefix("https://")
                ?.removePrefix("http://")
                ?.trimEnd('/')
            if (storedValue.isNullOrEmpty()) return DEFAULT_SERIENSTREAM_DOMAIN
            // s.to is dead (serien.domains); migrate to the official CUII proxy IP.
            if (storedValue.lowercase() in DEPRECATED_SERIENSTREAM_DOMAINS) {
                with(prefs.edit()) {
                    putString(Key.SERIENSTREAM_DOMAIN.name, DEFAULT_SERIENSTREAM_DOMAIN)
                    apply()
                }
                clearProviderCache("SerienStream")
                return DEFAULT_SERIENSTREAM_DOMAIN
            }
            return storedValue
        }
        set(value) {
            val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.SERIENSTREAM_DOMAIN.name, null) else null
            if (!::prefs.isInitialized) return

            val normalized = value
                ?.trim()
                ?.removePrefix("https://")
                ?.removePrefix("http://")
                ?.trimEnd('/')
                ?.ifBlank { DEFAULT_SERIENSTREAM_DOMAIN }
                ?: DEFAULT_SERIENSTREAM_DOMAIN
            val effective = if (normalized.lowercase() in DEPRECATED_SERIENSTREAM_DOMAINS) {
                DEFAULT_SERIENSTREAM_DOMAIN
            } else {
                normalized
            }

            if (effective != oldDomain && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("SerienStream")
            }

            with(prefs.edit()) {
                putString(Key.SERIENSTREAM_DOMAIN.name, effective)
                apply()
            }
        }

    var cuevanaDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_CUEVANA_DOMAIN
            val storedValue = prefs.getString(Key.CUEVANA_DOMAIN.name, null)
                ?.trim()
                ?.removePrefix("https://")
                ?.removePrefix("http://")
                ?.trimEnd('/')
            if (storedValue.isNullOrEmpty()) return DEFAULT_CUEVANA_DOMAIN
            // cuevana.gs redirects to cuevana3.gs; migrate stale stored domains.
            if (storedValue.removePrefix("www.").lowercase() in DEPRECATED_CUEVANA_DOMAINS) {
                with(prefs.edit()) {
                    putString(Key.CUEVANA_DOMAIN.name, DEFAULT_CUEVANA_DOMAIN)
                    apply()
                }
                clearProviderCache("Cuevana 3")
                return DEFAULT_CUEVANA_DOMAIN
            }
            return storedValue
        }
        set(value) {
            val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.CUEVANA_DOMAIN.name, null) else null
            if (!::prefs.isInitialized) return

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("Cuevana 3")
            }

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.CUEVANA_DOMAIN.name)
                } else {
                    putString(Key.CUEVANA_DOMAIN.name, value)
                }
                apply()
            }
        }

    var poseidonDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_POSEIDON_DOMAIN
            val storedValue = prefs.getString(Key.POSEIDON_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) DEFAULT_POSEIDON_DOMAIN else storedValue
        }
        set(value) {
            val oldDomain = if (::prefs.isInitialized) prefs.getString(Key.POSEIDON_DOMAIN.name, null) else null
            if (!::prefs.isInitialized) return

            if (value != oldDomain && !value.isNullOrEmpty() && !oldDomain.isNullOrEmpty()) {
                clearProviderCache("Poseidonhd2")
            }

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.POSEIDON_DOMAIN.name)
                } else {
                    putString(Key.POSEIDON_DOMAIN.name, value)
                }
                apply()
            }
        }

    var moflixDomain: String
        get() {
            if (!::prefs.isInitialized) return DEFAULT_MOFLIX_DOMAIN
            val storedValue = prefs.getString(Key.MOFLIX_DOMAIN.name, null)
            return if (storedValue.isNullOrEmpty()) DEFAULT_MOFLIX_DOMAIN else storedValue
        }
        set(value) {
            if (!::prefs.isInitialized) return

            with(prefs.edit()) {
                if (value.isNullOrEmpty()) {
                    remove(Key.MOFLIX_DOMAIN.name)
                } else {
                    putString(Key.MOFLIX_DOMAIN.name, value)
                }
                apply()
            }
        }

    var dohProviderUrl: String
        get() = Key.DOH_PROVIDER_URL.getString() ?: DEFAULT_DOH_PROVIDER_URL
        set(value) {
            Key.DOH_PROVIDER_URL.setString(value)
            DnsResolver.setDnsUrl(value)
            TMDb3.rebuildService()
        }

    var paddingX: Int
        get() = Key.SCREEN_PADDING_X.getInt() ?: 0
        set(value) = Key.SCREEN_PADDING_X.setInt(value)

    var paddingY: Int
        get() = Key.SCREEN_PADDING_Y.getInt() ?: 0
        set(value) = Key.SCREEN_PADDING_Y.setInt(value)

    var favoriteProviders: Set<String>
        get() = Key.FAVORITE_PROVIDERS.getStringSet() ?: emptySet()
        set(value) {
            Key.FAVORITE_PROVIDERS.setStringSet(value)
        }

    /**
     * First-launch onboarding (language → 3 favorites → Kids) finished or skipped.
     * Existing installs are grandfathered by [com.dskja.betterstreamflix.onboarding.OnboardingPolicy].
     */
    var onboardingCompleted: Boolean
        get() = Key.ONBOARDING_COMPLETED.getBoolean() ?: false
        set(value) = Key.ONBOARDING_COMPLETED.setBoolean(value)

    fun getFavoriteCategoryOrder(providerName: String): List<String> {
        val key = ProfileManager.scopedPrefKey("FAVORITE_CATEGORY_ORDER_$providerName")
        val saved = prefs.getString(key, null)
            ?.split(',')
            ?.filter { it == "movies" || it == "tv_shows" }
            .orEmpty()
        return (saved + listOf("movies", "tv_shows")).distinct()
    }

    fun setFavoriteCategoryOrder(providerName: String, order: List<String>) {
        val normalized = (order.filter { it == "movies" || it == "tv_shows" } +
            listOf("movies", "tv_shows")).distinct()
        prefs.edit {
            putString(
                ProfileManager.scopedPrefKey("FAVORITE_CATEGORY_ORDER_$providerName"),
                normalized.joinToString(","),
            )
        }
    }

    fun getFavoriteItemOrder(providerName: String, section: String): List<String> {
        val raw = prefs.getString(
            ProfileManager.scopedPrefKey("FAVORITE_ITEM_ORDER_${providerName}_$section"),
            null,
        ) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            List(json.length()) { index -> json.getString(index) }
        }.getOrDefault(emptyList())
    }

    fun setFavoriteItemOrder(providerName: String, section: String, order: List<String>) {
        prefs.edit {
            putString(
                ProfileManager.scopedPrefKey("FAVORITE_ITEM_ORDER_${providerName}_$section"),
                JSONArray(order).toString(),
            )
        }
    }

    fun getFavoriteSortMode(providerName: String): String =
        prefs.getString(
            ProfileManager.scopedPrefKey("FAVORITE_SORT_MODE_$providerName"),
            "manual",
        ) ?: "manual"

    fun setFavoriteSortMode(providerName: String, mode: String) {
        prefs.edit {
            putString(ProfileManager.scopedPrefKey("FAVORITE_SORT_MODE_$providerName"), mode)
        }
    }

    private fun profileScopedBoolean(key: Key): Boolean? =
        readBoolean(ProfileManager.scopedPrefKey(key.name))

    private fun setProfileScopedBoolean(key: Key, value: Boolean) {
        prefs.edit { putBoolean(ProfileManager.scopedPrefKey(key.name), value) }
    }

    private fun profileScopedString(key: Key): String? =
        readString(ProfileManager.scopedPrefKey(key.name))

    private fun setProfileScopedString(key: Key, value: String) {
        prefs.edit { putString(ProfileManager.scopedPrefKey(key.name), value) }
    }

    /**
     * New sets always hash. Already-hashed values (v2 / legacy hex) and blanks
     * pass through so lazy migration can rewrite storage safely.
     */
    private fun hashParentalPinForStorage(trimmed: String, saltScope: String): String {
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith(PinHasher.V2_PREFIX)) return trimmed
        // Legacy profile-style SHA-256 hex (64 lowercase hex chars).
        if (trimmed.length == 64 && trimmed.all { it in '0'..'9' || it in 'a'..'f' }) {
            return trimmed
        }
        // Raw digit PIN — always hash on write.
        if (trimmed.all { it.isDigit() }) {
            return PinHasher.hash(trimmed, saltScope)
        }
        return trimmed
    }

    private fun profileScopedInt(key: Key): Int? =
        readInt(ProfileManager.scopedPrefKey(key.name))

    private fun setProfileScopedInt(key: Key, value: Int?) {
        val scoped = ProfileManager.scopedPrefKey(key.name)
        prefs.edit {
            if (value == null) remove(scoped) else putInt(scoped, value)
        }
    }

    private fun profileScopedLong(key: Key): Long? =
        readLong(ProfileManager.scopedPrefKey(key.name))

    private fun profileScopedFloat(key: Key): Float? =
        readFloat(ProfileManager.scopedPrefKey(key.name))

    private fun setProfileScopedFloat(key: Key, value: Float) {
        prefs.edit { putFloat(ProfileManager.scopedPrefKey(key.name), value) }
    }

    /**
     * Profile-scoped keys never fall back to the unsuffixed default-profile value.
     * That fallback copied parental PINs, age ceilings, and library toggles onto
     * every other profile. A missing key means "unset" for this profile.
     * Readers tolerate a string left behind by a Preference widget on an int key.
     */
    private fun readBoolean(key: String): Boolean? {
        if (!prefs.contains(key)) return null
        return runCatching { prefs.getBoolean(key, false) }.getOrNull()
    }

    private fun readString(key: String): String? {
        if (!prefs.contains(key)) return null
        return runCatching { prefs.getString(key, null) }.getOrNull()
    }

    private fun readInt(key: String): Int? {
        if (!prefs.contains(key)) return null
        runCatching { return prefs.getInt(key, 0) }
        return runCatching { prefs.getString(key, null)?.trim()?.toIntOrNull() }.getOrNull()
    }

    private fun readLong(key: String): Long? {
        if (!prefs.contains(key)) return null
        runCatching { return prefs.getLong(key, 0L) }
        return runCatching { prefs.getString(key, null)?.trim()?.toLongOrNull() }.getOrNull()
    }

    private fun readFloat(key: String): Float? {
        if (!prefs.contains(key)) return null
        runCatching { return prefs.getFloat(key, 0f) }
        return runCatching { prefs.getString(key, null)?.trim()?.toFloatOrNull() }.getOrNull()
    }

    private fun setProfileScopedLong(key: Key, value: Long) {
        prefs.edit { putLong(ProfileManager.scopedPrefKey(key.name), value) }
    }

    private fun profileScopedStringSet(key: Key): Set<String> {
        if (!::prefs.isInitialized) return emptySet()
        val scoped = ProfileManager.scopedPrefKey(key.name)
        return prefs.getStringSet(scoped, null)?.toSet().orEmpty()
    }

    private fun setProfileScopedStringSet(key: Key, value: Set<String>) {
        if (!::prefs.isInitialized) return
        val scoped = ProfileManager.scopedPrefKey(key.name)
        prefs.edit {
            if (value.isEmpty()) remove(scoped)
            else putStringSet(scoped, value.toSet())
        }
    }

    private enum class Key {
        APP_LAYOUT,
        CURRENT_LANGUAGE,
        CURRENT_PROVIDER,
        PLAYER_RESIZE,
        PLAYER_RESIZE_NAME,
        CAPTION_TEXT_SIZE,
        CAPTION_STYLE_FONT_COLOR,
        CAPTION_STYLE_BACKGROUND_COLOR,
        CAPTION_STYLE_WINDOW_COLOR,
        CAPTION_STYLE_EDGE_TYPE,
        CAPTION_STYLE_EDGE_COLOR,
        CAPTION_STYLE_MARGIN,
        SCREEN_PADDING_X,
        SCREEN_PADDING_Y,
        QUALITY_HEIGHT,
        SUBTITLE_NAME,
        SERIENSTREAM_DOMAIN,
        MOFLIX_DOMAIN,
        STREAMINGCOMMUNITY_DOMAIN,
        CUEVANA_DOMAIN,
        POSEIDON_DOMAIN,
        DOH_PROVIDER_URL, // Removed STREAMINGCOMMUNITY_DNS_OVER_HTTPS, added DOH_PROVIDER_URL
        AUTOPLAY,
        PROVIDER_CACHE,
        KEEP_SCREEN_ON_WHEN_PAUSED,
        PLAYER_GESTURES,
        NIGHT_AUDIO_MODE,
        ALLOW_PHONE_PORTRAIT_PLAYBACK,
        DOWNLOAD_WITH_LONG_PRESS_HINT_SHOWN,
        IMMERSIVE_MODE,
        TMDB_API_KEY,
        SUBDL_API_KEY,
        FORCE_EXTRA_BUFFERING,
        PLAYBACK_VIDEO_QUALITY,
        DATA_SAVER_MODE,
        AUTOPLAY_BUFFER,
        SERVER_AUTO_SUBTITLES_DISABLED,
        ENABLE_TMDB,
        ENABLE_TMDB_LOGOS,
        TMDB_LOGO_QUALITY,
        TMDB_LOGO_MISS_TTL_HOURS,
        TMDB_LOGO_HIT_TTL_HOURS,
        SHOW_CONTINUE_WATCHING,
        SHOW_RECENTLY_WATCHED,
        SHOW_NEXT_EPISODES,
        SHOW_SMART_COLLECTIONS,
        SPOILER_SHIELD_ENABLED,
        SHOW_WATCHLIST_WEEK,
        HOME_SHELF_BLOCK_ORDER,
        HOME_SHELF_PINS_JSON,
        SERIES_FAST_FORWARD_ENABLED,
        HOME_LAST_VISIT_AT_MS,
        HOME_VISIT_NEXT_EPISODE_IDS,
        HOME_VISIT_FAVORITE_TV_IDS,
        KIDS_BEDTIME_ENABLED,
        KIDS_BEDTIME_MINUTE_OF_DAY,
        KIDS_BEDTIME_LOCKED,
        KIDS_BEDTIME_BYPASS_UNTIL_MS,
        SHOW_QUARANTINED_PROVIDERS,
        SHOW_VAVOO_VOD_PROVIDERS,
        LIBRARY_SCOPE,
        PARENTAL_CONTROL_PIN,
        PARENTAL_CONTROL_ADMIN_PIN,
        PARENTAL_CONTROL_MAX_AGE,
        PARENTAL_CONTROL_FAILED_ATTEMPTS,
        PARENTAL_CONTROL_LOCKED_UNTIL,
        PARENTAL_CONTROL_HARD_LOCKED,
        SELECTED_THEME,
        OLED_MODE,
        LARGE_TEXT,
        HIGH_CONTRAST,
        LEANBACK_DENSITY,
        STICK_ENERGY_MODE,
        EXPERIMENTAL_NEW_APP_DESIGN,
        EXPERIMENTAL_REACT_HOME,
        EXPERIMENTAL_LUMINA_ACCENT,
        EXPERIMENTAL_LUMINA_PURE_BLACK,
        EXPERIMENTAL_LUMINA_DYNAMIC_COLORS,
        EXPERIMENTAL_LUMINA_NAV_AUTO_HIDE,
        EXPERIMENTAL_LUMINA_HERO_PARALLAX,
        EXPERIMENTAL_LUMINA_REDUCED_GLASS,
        NEVER_SHOW_SUPPORT_ON_START,
        HOME_SUPPORT_CARD_DISMISSED,
        HOME_SUPPORT_CARD_LAST_DISMISSED_AT_MS,
        HOME_SUPPORT_CARD_LAST_SHOWN_AT_MS,
        HOME_SUPPORT_CARD_SHOW_COUNT,
        HOME_WATCHLIST_DIGEST_ENABLED,
        HOME_WATCHLIST_DIGEST_DISMISSED,
        HOME_WATCHLIST_DIGEST_LAST_DISMISSED_AT_MS,
        HOME_WATCHLIST_DIGEST_LAST_SHOWN_AT_MS,
        HOME_WATCHLIST_DIGEST_SHOW_COUNT,
        HOME_WATCHLIST_DIGEST_LAST_QUEUED_AT_MS,
        NEVER_SHOW_THANKS_AFTER_BINGE,
        THANKS_AFTER_BINGE_LAST_SHOWN_AT_MS,
        THANKS_AFTER_BINGE_LAST_DISMISSED_AT_MS,
        THANKS_AFTER_BINGE_SHOW_COUNT,
        OFFLINE_EXPIRY_NUDGE_LAST_SHOWN_AT_MS,
        OFFLINE_EXPIRY_NUDGE_LAST_DISMISSED_AT_MS,
        SUPPORT_EPISODES_WATCHED_COUNT,
        SUPPORT_EPISODE_IDS_COUNTED,
        SUPPORT_STARTUP_LAST_SHOWN_AT_MS,
        SUPPORT_STARTUP_SHOW_COUNT,
        SUPPORT_HUB_LAST_VISITED_AT_MS,
        SUPPORT_HUB_VISIT_COUNT,
        SUPPORT_APPRECIATION_PENDING,
        SUPPORT_APPRECIATION_PENDING_AT_MS,
        TELEGRAM_JOIN_GATE_UNLOCKED,
        TELEGRAM_JOIN_GATE_UNLOCKED_AT_MS,
        TELEGRAM_JOIN_GATE_OPENED_AT_MS,
        TELEGRAM_JOIN_GATE_OPEN_COUNT,
        TELEGRAM_JOIN_GATE_CONFIRM_COUNT,
        TELEGRAM_JOIN_GATE_CHANNEL_VERSION,
        CATALOG_SORT_MODE,
        SEARCH_SORT_MODE,
        SEARCH_YEAR_FILTER,
        CAST_ENABLED,
        CAST_SUBTITLES_ENABLED,
        CAST_KEEP_SCREEN_AWAKE,
        BYPASS_WS_ADVERTISED_HOST,
        SERIENSTREAM_SESSION_COOKIES,
        SERIENSTREAM_SESSION_DISPLAY_NAME,
        SERIENSTREAM_ACCOUNT_CONFIRMED,
        SERIENSTREAM_SESSION_VALIDATED_AT,
        SERIENSTREAM_SESSION_VALIDATED_OK,
        ANIWORLD_SESSION_COOKIES,
        ANIWORLD_SESSION_DISPLAY_NAME,
        ANIWORLD_ACCOUNT_CONFIRMED,
        ANIWORLD_SESSION_VALIDATED_AT,
        ANIWORLD_SESSION_VALIDATED_OK,
        UPDATE_CHECK_ENABLED,
        PROVIDER_LANGUAGE,
        FAVORITE_PROVIDERS,
        ONBOARDING_COMPLETED,
        DOWNLOAD_WIFI_ONLY,
        DOWNLOAD_MAX_CONCURRENT,
        DOWNLOAD_QUALITY_PRESET,
        DOWNLOAD_NOTIFY_COMPLETE,
        NOTIFY_WATCHLIST_NEW_EPISODE,
        NOTIFY_LIVE_FAVORITE_STARTING,
        NOTIFY_LIVE_BASELINE_READY,
        NOTIFY_LIVE_ACTIVE_KEYS,
        NOTIFY_LIVE_COOLDOWN_JSON,
        NOTIFY_LIVE_SNOOZE_JSON,
        NOTIFY_LIVE_RECENT_FIRES_JSON,
        NOTIFY_LIVE_KICKOFF_FP_JSON,
        NOTIFY_LIVE_SCHEDULED_KEYS,
        PROVIDER_SERVER_AB_JSON,
        NOTIFY_EPISODE_FINGERPRINTS_JSON,
        NOTIFY_EPISODE_LAST_CHECK_AT,
        DOWNLOAD_FILTER_CURRENT_PROVIDER,
        DOWNLOAD_SOFT_LIMIT_GB,
        DOWNLOAD_STORAGE_LOCATION,
        DOWNLOAD_TREE_URI,
        DOWNLOAD_RESOLVED_CACHE_DIR,
        EXTERNAL_PLAYER_PACKAGE,
        EXTERNAL_DOWNLOADER_PACKAGE,
        DOWNLOAD_SMART_ENABLED,
        DOWNLOAD_SMART_LOOKAHEAD,
        DOWNLOAD_SMART_SERIES_QUOTA,
        DOWNLOAD_AUTO_DELETE_WATCHED,
        TRAKT_ENABLED,
        TRAKT_CLIENT_ID,
        TRAKT_ACCESS_TOKEN,
        JELLYFIN_BASE_URL,
        JELLYFIN_ACCESS_TOKEN,
        JELLYFIN_USER_ID,
        JELLYFIN_DEVICE_ID,
        PLEX_BASE_URL,
        PLEX_TOKEN,
        PLEX_CLIENT_ID,
        DEBRID_ENABLED,
        REAL_DEBRID_TOKEN,
        DEBRID_PROVIDER,
        PREMIUMIZE_API_KEY,
        ALLDEBRID_API_KEY,
        TORBOX_API_KEY,
        TRAKT_CLIENT_SECRET,
        TRAKT_REFRESH_TOKEN,
        DISABLED_PLUGIN_IDS,
        PLAYER_BACKEND,
        SIMKL_ENABLED,
        SIMKL_CLIENT_ID,
        SIMKL_ACCESS_TOKEN,
        OPENSUBTITLES_API_KEY,
        OPENSUBTITLES_JWT,
        OPENSUBTITLES_USER_AGENT,
        OPENSUBTITLES_LANGUAGES,
        CAST_QUEUE_NEXT_EPISODE,
        SELF_HOST_PROGRESS_INTERVAL_MS;

        fun getStringSet(): Set<String>? {
            if (!::prefs.isInitialized) return null
            return when {
                prefs.contains(name) -> prefs.getStringSet(name, null)
                else -> null
            }
        }

        fun setStringSet(value: Set<String>?) {
            if (!::prefs.isInitialized) return
            value?.let {
                with(prefs.edit()) {
                    putStringSet(name, value)
                    apply()
                }
            } ?: remove()
        }

        fun getBoolean(): Boolean? {
            if (!::prefs.isInitialized) return null
            return when {
                prefs.contains(name) -> prefs.getBoolean(name, false)
                else -> null
            }
        }

        fun getFloat(): Float? {
            if (!::prefs.isInitialized) return null
            return when {
                prefs.contains(name) -> prefs.getFloat(name, 0F)
                else -> null
            }
        }

        fun getInt(): Int? {
            if (!::prefs.isInitialized) return null
            return when {
                prefs.contains(name) -> prefs.getInt(name, 0)
                else -> null
            }
        }

        fun getLong(): Long? {
            if (!::prefs.isInitialized) return null
            return when {
                prefs.contains(name) -> prefs.getLong(name, 0)
                else -> null
            }
        }

        fun getString(): String? {
            // Prefs may be touched from DnsResolver / provider clinit before setup().
            if (!::prefs.isInitialized) return null
            return when {
                prefs.contains(name) -> prefs.getString(name, null)
                else -> null
            }
        }

        fun setBoolean(value: Boolean?) {
            if (!::prefs.isInitialized) return
            value?.let {
                with(prefs.edit()) {
                    putBoolean(name, value)
                    apply()
                }
            } ?: remove()
        }

        fun setFloat(value: Float?) {
            if (!::prefs.isInitialized) return
            value?.let {
                with(prefs.edit()) {
                    putFloat(name, value)
                    apply()
                }
            } ?: remove()
        }

        fun setInt(value: Int?) {
            if (!::prefs.isInitialized) return
            value?.let {
                with(prefs.edit()) {
                    putInt(name, value)
                    apply()
                }
            } ?: remove()
        }

        fun setLong(value: Long?) {
            if (!::prefs.isInitialized) return
            value?.let {
                with(prefs.edit()) {
                    putLong(name, value)
                    apply()
                }
            } ?: remove()
        }

        fun setString(value: String?) {
            if (!::prefs.isInitialized) return
            value?.let {
                with(prefs.edit()) {
                    putString(name, value)
                    apply()
                }
            } ?: remove()
        }

        fun remove() {
            if (!::prefs.isInitialized) return
            with(prefs.edit()) {
                remove(name)
                apply()
            }
        }
    }
}
