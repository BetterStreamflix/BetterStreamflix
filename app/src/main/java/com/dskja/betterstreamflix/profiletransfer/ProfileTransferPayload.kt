package com.dskja.betterstreamflix.profiletransfer

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
        val showContinueWatching: Boolean? = null,
        val showRecentlyWatched: Boolean? = null,
        val showNextEpisodes: Boolean? = null,
        val showSmartCollections: Boolean? = null,
        val showWatchlistWeek: Boolean? = null,
        val homeShelfBlockOrder: String? = null,
        val homeShelfPinsJson: String? = null,
        val seriesFastForwardEnabled: Boolean? = null,
        val parentalControlMaxAge: Int? = null,
        val favoriteSortByProvider: Map<String, String> = emptyMap(),
        val favoriteItemOrderByProvider: Map<String, Map<String, List<String>>> = emptyMap(),
        val favoriteCategoryOrderByProvider: Map<String, List<String>> = emptyMap(),
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
        notes = notes.take(MAX_QR_NOTES).map {
            it.copy(
                episodeId = it.episodeId.take(MAX_ID_CHARS),
                text = it.text.take(MAX_NOTE_CHARS),
            )
        },
        persona = persona?.copy(
            displayName = persona.displayName.take(MAX_TITLE_CHARS),
            enabledIntegrations = persona.enabledIntegrations.take(16),
        ),
        settings = settings.copy(
            homeShelfBlockOrder = settings.homeShelfBlockOrder?.take(240),
            homeShelfPinsJson = settings.homeShelfPinsJson?.take(480),
            // Favorite order maps can blow QR — keep sort modes only.
            favoriteItemOrderByProvider = emptyMap(),
            favoriteCategoryOrderByProvider = emptyMap(),
            favoriteSortByProvider = settings.favoriteSortByProvider
                .entries.take(24)
                .associate { it.key.take(64) to it.value.take(32) },
        ),
    )

    companion object {
        const val VERSION = 1
        const val KIND = "bsf.profile"
        const val MAX_QR_WATCHLIST = 80
        const val MAX_QR_NOTES = 40
        const val MAX_FILE_WATCHLIST = 500
        const val MAX_FILE_NOTES = 200
        const val MAX_TITLE_CHARS = 120
        const val MAX_ID_CHARS = 96
        const val MAX_NOTE_CHARS = 160
    }
}
