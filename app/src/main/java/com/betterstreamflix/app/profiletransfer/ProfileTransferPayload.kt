package com.betterstreamflix.app.profiletransfer

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Portable profile snapshot: settings + watchlist + episode notes.
 * No offline/download files, PINs, or watch history.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProfileTransferPayload(
    @EncodeDefault val v: Int = VERSION,
    @EncodeDefault val kind: String = KIND,
    @SerialName("exportedAt") val exportedAt: Long = 0L,
    @SerialName("sourceProfileId") val sourceProfileId: String = "",
    val persona: Persona? = null,
    val settings: SettingsSnapshot = SettingsSnapshot(),
    val watchlist: List<WatchlistItem> = emptyList(),
    val notes: List<NoteItem> = emptyList(),
    /** HMAC-SHA256 (hex) over canonical JSON — present on v2 exports. */
    val integrity: String? = null,
) {
    @Serializable
    data class Persona(
        val displayName: String = "",
        val avatarKey: String = "",
        val isKids: Boolean = false,
        val maxAgeRating: Int? = null,
        val enabledIntegrations: List<String> = emptyList(),
    )

    @Serializable
    data class SettingsSnapshot(
        val libraryScope: String? = null,
        val selectedTheme: String? = null,
        /** Compact / comfort — profile Leanback density (TV). */
        val leanbackDensity: String? = null,
        val showContinueWatching: Boolean? = null,
        val showRecentlyWatched: Boolean? = null,
        val showNextEpisodes: Boolean? = null,
        val showSmartCollections: Boolean? = null,
        val showWatchlistWeek: Boolean? = null,
        val homeShelfBlockOrder: String? = null,
        val homeShelfPinsJson: String? = null,
        val seriesFastForwardEnabled: Boolean? = null,
        val spoilerShieldEnabled: Boolean? = null,
        val parentalControlMaxAge: Int? = null,
        val favoriteSortByProvider: Map<String, String> = emptyMap(),
        val favoriteItemOrderByProvider: Map<String, Map<String, List<String>>> = emptyMap(),
        val favoriteCategoryOrderByProvider: Map<String, List<String>> = emptyMap(),
        /** Taste Graph JSON bundle (signals / affinity / tokens). Omitted from QR. */
        val tasteSignalsJson: String? = null,
        val tasteAffinityJson: String? = null,
        val tasteQueryTokensJson: String? = null,
        /** Search Memories JSON array. Omitted from QR. */
        val searchMemoriesJson: String? = null,
    )

    @Serializable
    data class WatchlistItem(
        val provider: String,
        val mediaType: String,
        val mediaId: String,
        val title: String = "",
        val poster: String? = null,
        val banner: String? = null,
        val favoritedAtMillis: Long? = null,
    )

    @Serializable
    data class NoteItem(
        val episodeId: String,
        val text: String,
    )

    fun forQr(): ProfileTransferPayload = copy(
        watchlist = watchlist.take(MAX_QR_WATCHLIST).map {
            it.copy(poster = null, banner = null, title = it.title.take(MAX_TITLE_CHARS))
        },
        // QR is shoulder-surfable — omit notes and kids/age metadata.
        notes = emptyList(),
        persona = persona?.copy(
            displayName = persona.displayName.take(MAX_TITLE_CHARS),
            isKids = false,
            maxAgeRating = null,
            enabledIntegrations = persona.enabledIntegrations.take(16),
        ),
        settings = settings.copy(
            homeShelfBlockOrder = settings.homeShelfBlockOrder?.take(240),
            homeShelfPinsJson = settings.homeShelfPinsJson?.take(480),
            parentalControlMaxAge = null,
            // Favorite order maps can blow QR — keep sort modes only.
            favoriteItemOrderByProvider = emptyMap(),
            favoriteCategoryOrderByProvider = emptyMap(),
            favoriteSortByProvider = settings.favoriteSortByProvider
                .entries.take(24)
                .associate { it.key.take(64) to it.value.take(32) },
            // Taste / memories are file-export only (too large for QR).
            tasteSignalsJson = null,
            tasteAffinityJson = null,
            tasteQueryTokensJson = null,
            searchMemoriesJson = null,
        ),
    )

    companion object {
        const val VERSION = 2
        const val VERSION_LEGACY = 1
        const val KIND = "bsf.profile"
        /** Silent caps at export — lists longer than this are truncated. */
        const val MAX_QR_WATCHLIST = 80
        const val MAX_QR_NOTES = 40
        const val MAX_FILE_WATCHLIST = 500
        const val MAX_FILE_NOTES = 200
        const val MAX_TITLE_CHARS = 120
        const val MAX_ID_CHARS = 96
        const val MAX_NOTE_CHARS = 160
    }
}
