package com.betterstreamflix.app.live

import com.betterstreamflix.app.utils.LiveHubCatalog

/**
 * Live Command surface modes — second pillar filters for Live Hub.
 * Favorites / Sport / News / Channels mirror catalog shelves; Reminders is a
 * synthetic command panel; All keeps the full hub stack.
 */
enum class LiveCommandMode {
    ALL,
    FAVORITES,
    SPORT,
    NEWS,
    CHANNELS,
    REMINDERS,
    ;

    val sectionIdentity: String?
        get() = when (this) {
            ALL, REMINDERS -> null
            FAVORITES -> LiveHubCatalog.SECTION_FAVORITES
            SPORT -> LiveHubCatalog.SECTION_SPORT
            NEWS -> LiveHubCatalog.SECTION_NEWS
            CHANNELS -> LiveHubCatalog.SECTION_CHANNELS
        }

    companion object {
        fun fromStorage(raw: String?): LiveCommandMode {
            if (raw.isNullOrBlank()) return ALL
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ALL
        }
    }
}
