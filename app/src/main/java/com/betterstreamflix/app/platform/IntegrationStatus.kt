package com.betterstreamflix.app.platform

import android.content.Context
import com.betterstreamflix.app.R
import com.betterstreamflix.app.platform.debrid.DebridProviderId
import com.betterstreamflix.app.platform.debrid.DebridResolver
import com.betterstreamflix.app.platform.playerbackend.ExternalMpvBackend
import com.betterstreamflix.app.platform.playerbackend.PlayerBackendKind
import com.betterstreamflix.app.platform.playerbackend.PlayerBackendSelector
import com.betterstreamflix.app.platform.plugins.PluginRegistry
import com.betterstreamflix.app.platform.simkl.SimklConfig
import com.betterstreamflix.app.platform.subtitles.OpenSubtitlesV1Client
import com.betterstreamflix.app.platform.trakt.TraktConfig
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.profiles.UserProfile
import com.betterstreamflix.app.utils.UserPreferences

/**
 * Live connection snapshots for the Integrations hub and preference screens.
 * Pure preference reads — no network. Pair with [IntegrationProbes] for live tests.
 */
object IntegrationStatus {

    enum class Level {
        UNAVAILABLE,
        DISABLED,
        NOT_CONFIGURED,
        READY,
        SIGNED_IN,
    }

    data class Snapshot(
        val level: Level,
        /** Optional short detail (username, provider id, package name). */
        val detail: String? = null,
    ) {
        val isHealthy: Boolean get() = level == Level.READY || level == Level.SIGNED_IN
    }

    fun trakt(): Snapshot = trakt(
        ProfileManager.isActiveIntegrationEnabled(UserProfile.Integration.TRAKT),
    )

    private fun trakt(profileEnabled: Boolean): Snapshot = when {
        !TraktConfig.hasAppCredentials() -> Snapshot(Level.UNAVAILABLE)
        !UserPreferences.traktEnabled || !profileEnabled -> Snapshot(Level.DISABLED)
        TraktConfig.isSignedIn() -> Snapshot(Level.SIGNED_IN)
        else -> Snapshot(Level.NOT_CONFIGURED)
    }

    fun jellyfin(): Snapshot = jellyfin(
        ProfileManager.isActiveIntegrationEnabled(UserProfile.Integration.JELLYFIN),
    )

    private fun jellyfin(profileEnabled: Boolean): Snapshot {
        if (!profileEnabled) {
            return Snapshot(Level.DISABLED)
        }
        val url = UserPreferences.jellyfinBaseUrl.trim()
        val token = UserPreferences.jellyfinAccessToken.trim()
        val user = UserPreferences.jellyfinUserId.trim()
        return when {
            url.isBlank() -> Snapshot(Level.NOT_CONFIGURED)
            token.isBlank() || user.isBlank() -> Snapshot(Level.NOT_CONFIGURED, hostOf(url))
            else -> Snapshot(Level.SIGNED_IN, hostOf(url))
        }
    }

    fun plex(): Snapshot = plex(
        ProfileManager.isActiveIntegrationEnabled(UserProfile.Integration.PLEX),
    )

    private fun plex(profileEnabled: Boolean): Snapshot {
        if (!profileEnabled) {
            return Snapshot(Level.DISABLED)
        }
        val url = UserPreferences.plexBaseUrl.trim()
        val token = UserPreferences.plexToken.trim()
        return when {
            url.isBlank() -> Snapshot(Level.NOT_CONFIGURED)
            token.isBlank() -> Snapshot(Level.NOT_CONFIGURED, hostOf(url))
            else -> Snapshot(Level.SIGNED_IN, hostOf(url))
        }
    }

    fun debrid(): Snapshot = debrid(
        ProfileManager.isActiveIntegrationEnabled(UserProfile.Integration.DEBRID),
    )

    private fun debrid(profileEnabled: Boolean): Snapshot {
        if (!UserPreferences.debridEnabled || !profileEnabled) {
            return Snapshot(Level.DISABLED)
        }
        val provider = DebridProviderId.fromId(UserPreferences.debridProvider)
        return if (DebridResolver.activeService() != null) {
            Snapshot(Level.READY, provider.displayName)
        } else {
            Snapshot(Level.NOT_CONFIGURED, provider.displayName)
        }
    }

    fun simkl(): Snapshot = simkl(
        ProfileManager.isActiveIntegrationEnabled(UserProfile.Integration.SIMKL),
    )

    private fun simkl(profileEnabled: Boolean): Snapshot = when {
        !UserPreferences.simklEnabled || !profileEnabled -> Snapshot(Level.DISABLED)
        SimklConfig.configured() -> Snapshot(Level.SIGNED_IN)
        SimklConfig.clientId().isBlank() -> Snapshot(Level.NOT_CONFIGURED)
        else -> Snapshot(Level.NOT_CONFIGURED)
    }

    fun openSubtitles(): Snapshot = openSubtitles(
        ProfileManager.isActiveIntegrationEnabled(UserProfile.Integration.OPENSUBTITLES),
    )

    private fun openSubtitles(profileEnabled: Boolean): Snapshot {
        if (!profileEnabled) {
            return Snapshot(Level.DISABLED)
        }
        if (!OpenSubtitlesV1Client.configured()) return Snapshot(Level.NOT_CONFIGURED)
        val jwt = runCatching { UserPreferences.openSubtitlesJwt.isNotBlank() }.getOrDefault(false)
        return if (jwt) Snapshot(Level.SIGNED_IN) else Snapshot(Level.READY)
    }

    fun player(context: Context? = null): Snapshot {
        return when (PlayerBackendSelector.preferredKind()) {
            PlayerBackendKind.EXO -> Snapshot(Level.READY, "ExoPlayer")
            PlayerBackendKind.EXTERNAL_MPV -> {
                val pkg = context?.let { ExternalMpvBackend.preferredInstalledPackage(it) }
                if (pkg != null) Snapshot(Level.READY, pkg)
                else Snapshot(Level.NOT_CONFIGURED, "MPV")
            }
        }
    }

    fun tmdb(): Snapshot = tmdb(
        ProfileManager.isActiveIntegrationEnabled(UserProfile.Integration.TMDB),
    )

    private fun tmdb(profileEnabled: Boolean): Snapshot = when {
        !UserPreferences.enableTmdb || !profileEnabled -> Snapshot(Level.DISABLED)
        !com.betterstreamflix.app.utils.TMDb3.hasApiKey() -> Snapshot(Level.NOT_CONFIGURED)
        else -> Snapshot(Level.READY)
    }

    /**
     * Snapshot with the per-profile gate forced on — used by the per-profile
     * integrations editor where the checkbox itself represents the gate.
     */
    fun globalOnly(integration: String): Snapshot = when (integration) {
        UserProfile.Integration.TRAKT -> trakt(profileEnabled = true)
        UserProfile.Integration.JELLYFIN -> jellyfin(profileEnabled = true)
        UserProfile.Integration.PLEX -> plex(profileEnabled = true)
        UserProfile.Integration.DEBRID -> debrid(profileEnabled = true)
        UserProfile.Integration.SIMKL -> simkl(profileEnabled = true)
        UserProfile.Integration.OPENSUBTITLES -> openSubtitles(profileEnabled = true)
        UserProfile.Integration.TMDB -> tmdb(profileEnabled = true)
        else -> Snapshot(Level.NOT_CONFIGURED)
    }

    fun hubOverview(context: Context): String {
        val snapshots = listOf(
            trakt(), jellyfin(), plex(), debrid(), simkl(), openSubtitles(), player(context), tmdb(),
        )
        val connected = snapshots.count { it.isHealthy }
        return context.getString(R.string.platform_hub_overview, connected, snapshots.size)
    }

    fun label(context: Context, snapshot: Snapshot): String {
        val base = when (snapshot.level) {
            Level.UNAVAILABLE -> context.getString(R.string.platform_status_unavailable)
            Level.DISABLED -> context.getString(R.string.platform_status_disabled)
            Level.NOT_CONFIGURED -> context.getString(R.string.platform_status_not_configured)
            Level.READY -> context.getString(R.string.platform_status_ready)
            Level.SIGNED_IN -> context.getString(R.string.platform_status_signed_in)
        }
        val detail = snapshot.detail?.takeIf { it.isNotBlank() } ?: return base
        return context.getString(R.string.platform_status_with_detail, base, detail)
    }

    fun forScreen(screenKey: String, context: Context? = null): Snapshot = when (screenKey) {
        "screen_platform_trakt" -> trakt()
        "screen_platform_jellyfin" -> jellyfin()
        "screen_platform_plex" -> plex()
        "screen_platform_debrid" -> debrid()
        "screen_platform_simkl" -> simkl()
        "screen_platform_subtitles" -> openSubtitles()
        "screen_platform_player" -> player(context)
        else -> Snapshot(Level.NOT_CONFIGURED)
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
}
