package com.dskja.betterstreamflix.platform.plugins

import android.content.Context
import android.content.Intent
import android.util.Log
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.utils.ProviderChangeNotifier
import com.dskja.betterstreamflix.utils.UserPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central plugin lifecycle: bootstrap, reload (without dropping LOCAL APKs),
 * enable/disable, uninstall, install from staged APK, extension dispatch.
 */
object PluginManager {
    private const val TAG = "PluginManager"

    private val homeHooks = CopyOnWriteArrayList<HomeContributionPlugin>()
    private val searchHooks = CopyOnWriteArrayList<SearchContributionPlugin>()
    private val metadataHooks = CopyOnWriteArrayList<MetadataEnrichmentPlugin>()
    private val playbackHooks = CopyOnWriteArrayList<PlaybackHookPlugin>()
    private val extractorHooks = CopyOnWriteArrayList<ExtractorContributionPlugin>()
    private val subtitleHooks = CopyOnWriteArrayList<SubtitleContributionPlugin>()
    private val settingsHooks = CopyOnWriteArrayList<SettingsContributionPlugin>()

    @Volatile
    private var appContext: Context? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        PluginRegistry.bootstrapBuiltins()
        refreshExtensionHooks()
        PluginEvents.record("system", "start", "plugins=${PluginRegistry.size}")
        Log.i(
            TAG,
            "Started plugins=${PluginRegistry.size} home=${homeHooks.size} " +
                "search=${searchHooks.size} playback=${playbackHooks.size}",
        )
    }

    /** Soft reload: keep builtins; never wipe the registry first. */
    fun reload(context: Context = requireContext()): Int {
        PluginRegistry.ensureBuiltins()
        refreshExtensionHooks()
        ProviderChangeNotifier.notifyProviderChanged()
        PluginEvents.record("system", "reload", "plugins=${PluginRegistry.size}")
        return PluginRegistry.size
    }

    fun setEnabled(pluginId: String, enabled: Boolean) {
        UserPreferences.setPluginDisabled(pluginId, disabled = !enabled)
        refreshExtensionHooks()
        ProviderChangeNotifier.notifyProviderChanged()
        PluginEvents.record(pluginId, if (enabled) "enabled" else "disabled")
        val current = UserPreferences.currentProvider
        if (current != null && !PluginRegistry.isProviderVisible(current)) {
            val next = PluginRegistry.enabledProviders().firstOrNull()
                ?: Provider.providers.keys.firstOrNull()
            if (next != null) {
                // Same CLEAR_TASK handoff as the provider picker — never the full setter.
                UserPreferences.prepareProviderSwitch(next)
                restartMainTask()
            } else {
                UserPreferences.clearInvalidCurrentProvider()
                runCatching { com.dskja.betterstreamflix.database.AppDatabase.resetInstance() }
            }
        }
    }

    private fun restartMainTask() {
        val ctx = appContext ?: return
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { ctx.startActivity(launch) }
    }

    fun isEnabled(pluginId: String): Boolean = !UserPreferences.isPluginDisabled(pluginId)

    suspend fun collectHomeCategories(provider: Provider): List<Category> {
        if (homeHooks.isEmpty()) return emptyList()
        return homeHooks.flatMap { hook ->
            if (UserPreferences.isPluginDisabled(hook.pluginId)) return@flatMap emptyList()
            if (!contributes(hook, home = true)) return@flatMap emptyList()
            runCatching { hook.homeCategories(provider) }.getOrElse {
                Log.w(TAG, "Home hook ${hook.pluginId}: ${it.message}")
                emptyList()
            }
        }
    }

    suspend fun collectSearchResults(
        provider: Provider,
        query: String,
        page: Int = 1,
    ): List<AppAdapter.Item> {
        if (query.isBlank() || searchHooks.isEmpty()) return emptyList()
        return searchHooks.flatMap { hook ->
            if (UserPreferences.isPluginDisabled(hook.pluginId)) return@flatMap emptyList()
            if (!contributes(hook, search = true)) return@flatMap emptyList()
            runCatching { hook.search(provider, query, page) }.getOrElse {
                Log.w(TAG, "Search hook ${hook.pluginId}: ${it.message}")
                emptyList()
            }
        }
    }

    suspend fun enrichMovie(provider: Provider, movie: Movie): Movie {
        var current = movie
        metadataHooks.forEach { hook ->
            if (UserPreferences.isPluginDisabled(hook.pluginId)) return@forEach
            if (!contributes(hook, metadata = true)) return@forEach
            current = runCatching { hook.enrichMovie(provider, current) }.getOrDefault(current)
        }
        return current
    }

    suspend fun enrichTvShow(provider: Provider, tvShow: TvShow): TvShow {
        var current = tvShow
        metadataHooks.forEach { hook ->
            if (UserPreferences.isPluginDisabled(hook.pluginId)) return@forEach
            if (!contributes(hook, metadata = true)) return@forEach
            current = runCatching { hook.enrichTvShow(provider, current) }.getOrDefault(current)
        }
        return current
    }

    fun dispatchPlaybackStarted(videoType: Video.Type, serverName: String?) {
        playbackHooks.forEach { hook ->
            if (UserPreferences.isPluginDisabled(hook.pluginId)) return@forEach
            if (!contributes(hook, playback = true)) return@forEach
            runCatching { hook.onPlaybackStarted(videoType, serverName) }
            PluginEvents.record(hook.pluginId, "playback_started", serverName.orEmpty())
        }
    }

    fun dispatchPlaybackEnded(videoType: Video.Type) {
        playbackHooks.forEach { hook ->
            if (UserPreferences.isPluginDisabled(hook.pluginId)) return@forEach
            if (!contributes(hook, playback = true)) return@forEach
            runCatching { hook.onPlaybackEnded(videoType) }
            PluginEvents.record(hook.pluginId, "playback_ended")
        }
    }

    fun dispatchServerResolved(server: Video.Server) {
        playbackHooks.forEach { hook ->
            if (UserPreferences.isPluginDisabled(hook.pluginId)) return@forEach
            if (!contributes(hook, playback = true)) return@forEach
            runCatching { hook.onServerResolved(server) }
        }
    }

    fun dispatchPlaybackProgress(videoType: Video.Type, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        playbackHooks.forEach { hook ->
            if (UserPreferences.isPluginDisabled(hook.pluginId)) return@forEach
            if (!contributes(hook, playback = true)) return@forEach
            runCatching { hook.onPlaybackProgress(videoType, positionMs, durationMs) }
        }
    }

    private fun refreshExtensionHooks() {
        homeHooks.clear()
        searchHooks.clear()
        metadataHooks.clear()
        playbackHooks.clear()
        extractorHooks.clear()
        subtitleHooks.clear()
        settingsHooks.clear()
        PluginRegistry.all().forEach { plugin ->
            if (plugin is BetterStreamflixPlugin) {
                runCatching { plugin.onAttach(DefaultPluginHost) }
            }
            if (plugin is HomeContributionPlugin && contributes(plugin, home = true)) {
                homeHooks.add(plugin)
            }
            if (plugin is SearchContributionPlugin && contributes(plugin, search = true)) {
                searchHooks.add(plugin)
            }
            if (plugin is MetadataEnrichmentPlugin && contributes(plugin, metadata = true)) {
                metadataHooks.add(plugin)
            }
            if (plugin is PlaybackHookPlugin && contributes(plugin, playback = true)) {
                playbackHooks.add(plugin)
            }
            if (plugin is ExtractorContributionPlugin && contributes(plugin, extractors = true)) {
                extractorHooks.add(plugin)
            }
            if (plugin is SubtitleContributionPlugin && contributes(plugin, subtitles = true)) {
                subtitleHooks.add(plugin)
            }
            if (plugin is SettingsContributionPlugin && contributes(plugin, settings = true)) {
                settingsHooks.add(plugin)
            }
        }
    }

    private fun contributes(
        plugin: PluginExtension,
        home: Boolean = false,
        search: Boolean = false,
        metadata: Boolean = false,
        playback: Boolean = false,
        extractors: Boolean = false,
        subtitles: Boolean = false,
        settings: Boolean = false,
    ): Boolean {
        // Built-ins and direct implementors: the `is XxxPlugin` check at the call site is enough.
        // Still honor capability flags when present so stubs don't falsely advertise hooks.
        val caps = (plugin as? SourcePlugin)?.manifest?.capabilities ?: return true
        return when {
            home -> caps.homeContribution || plugin is HomeContributionPlugin
            search -> caps.searchContribution || plugin is SearchContributionPlugin
            metadata -> caps.metadataEnrichment || plugin is MetadataEnrichmentPlugin
            playback -> caps.playbackHooks || plugin is PlaybackHookPlugin
            extractors -> caps.extractors || plugin is ExtractorContributionPlugin
            subtitles -> caps.subtitles || plugin is SubtitleContributionPlugin
            settings -> caps.settings || plugin is SettingsContributionPlugin
            else -> true
        }
    }

    private fun requireContext(): Context =
        appContext ?: error("PluginManager not started")
}
