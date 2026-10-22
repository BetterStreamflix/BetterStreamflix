package com.betterstreamflix.app.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.betterstreamflix.app.R
import com.betterstreamflix.app.fragments.settings.ProviderSettingsVisibility
import com.betterstreamflix.app.kids.KidsFortress
import com.betterstreamflix.app.utils.UserPreferences

/**
 * Declarative catalog of the Settings information architecture.
 *
 * This is the single source of truth for which settings pages exist, where
 * they live in the new grouped IA, and under which conditions they are
 * visible. Both renderers (mobile hub + TV two-pane) consume this catalog —
 * no business decision is duplicated per platform.
 *
 * AUDIT TABLE — legacy flat screen -> new location
 * (every former root PreferenceScreen of settings_mobile.xml /
 *  settings_tv.xml is mapped; no screen key was dropped or renamed so all
 *  SettingsDeepLink targets keep working 1:1)
 *
 *   screen_content            -> INTEGRATIONS   (TMDb, Fanart, artwork sources)
 *   screen_profiles           -> HOUSEHOLD
 *   screen_parent_dashboard   -> HOUSEHOLD      (only when a Kids profile exists)
 *   screen_platform           -> INTEGRATIONS   (player backend + trakt/jellyfin/
 *                               plex/simkl/opensubtitles/debrid sub-screens)
 *   screen_playback           -> PLAYBACK       (autoplay, buffering, quality,
 *                               gestures, trailers, subtitle defaults)
 *   screen_downloads          -> DOWNLOADS
 *   screen_notifications      -> DOWNLOADS
 *   screen_appearance         -> APPEARANCE
 *   screen_cinema_room        -> PLAYBACK       (mobile only)
 *   screen_watchlist_import   -> LIBRARY        (mobile only)
 *   screen_serienstream_auth  -> INTEGRATIONS   (only for SerienStream provider)
 *   screen_aniworld_auth      -> INTEGRATIONS   (only for AniWorld provider)
 *   screen_network            -> NETWORK
 *   screen_provider           -> NETWORK        (active provider connection)
 *   screen_guardaflix_auth    -> INTEGRATIONS   (only for GuardaFlix provider)
 *   screen_cloud_sync         -> HOUSEHOLD      (Supabase, device link, cast rooms)
 *   screen_backup             -> SYSTEM
 *   screen_more               -> SYSTEM
 *   p_settings_support        -> SYSTEM         (navigates to Support)
 *   settings_about            -> SYSTEM         (navigates to About)
 *
 * Finer-grained row regrouping (e.g. moving Library rows out of
 * screen_profiles) is future work once screens migrate from XML subtrees to
 * fully typed spec rows.
 */
object SettingsCatalog {

    enum class GroupId(@StringRes val titleRes: Int) {
        HOUSEHOLD(R.string.settings_group_household),
        PLAYBACK(R.string.settings_group_playback),
        APPEARANCE(R.string.settings_group_appearance),
        LIBRARY(R.string.settings_group_library),
        DOWNLOADS(R.string.settings_group_downloads),
        NETWORK(R.string.settings_group_network),
        INTEGRATIONS(R.string.settings_group_integrations),
        SYSTEM(R.string.settings_group_system),
    }

    /**
     * One openable settings destination. [screenKey] equals the legacy
     * `screen_*` PreferenceScreen key so SettingsDeepLink, search results and
     * provider gears keep targeting it unchanged. [navTarget] marks entries
     * that leave Settings entirely (Support / About).
     */
    data class Page(
        val screenKey: String,
        val group: GroupId,
        @StringRes val titleRes: Int,
        @StringRes val summaryRes: Int,
        @DrawableRes val iconRes: Int,
        val onMobile: Boolean = true,
        val onTv: Boolean = true,
        val navTarget: NavTarget? = null,
        val visibleIf: () -> Boolean = { true },
    ) {
        enum class NavTarget { SUPPORT, ABOUT }

        fun visible(isTv: Boolean): Boolean =
            (if (isTv) onTv else onMobile) && navTarget == null && visibleIf()
    }

    private val pages = listOf(
        // ---- Household -------------------------------------------------
        Page(
            screenKey = "screen_profiles",
            group = GroupId.HOUSEHOLD,
            titleRes = R.string.settings_screen_profiles_title,
            summaryRes = R.string.settings_screen_profiles_summary,
            iconRes = R.drawable.ic_person_placeholder,
        ),
        Page(
            screenKey = "screen_parent_dashboard",
            group = GroupId.HOUSEHOLD,
            titleRes = R.string.parent_dashboard_title,
            summaryRes = R.string.parent_dashboard_summary,
            iconRes = R.drawable.ic_favorite_enable,
            visibleIf = { KidsFortress.parentDashboardEligible() },
        ),
        Page(
            screenKey = "screen_cloud_sync",
            group = GroupId.HOUSEHOLD,
            titleRes = R.string.cloud_sync_title,
            summaryRes = R.string.cloud_sync_screen_summary,
            iconRes = R.drawable.ic_refresh,
        ),

        // ---- Playback --------------------------------------------------
        Page(
            screenKey = "screen_playback",
            group = GroupId.PLAYBACK,
            titleRes = R.string.player_settings,
            summaryRes = R.string.settings_screen_playback_summary,
            iconRes = R.drawable.ic_exp_play,
        ),
        Page(
            screenKey = "screen_cinema_room",
            group = GroupId.PLAYBACK,
            titleRes = R.string.settings_cinema_room_title,
            summaryRes = R.string.settings_cinema_room_summary,
            iconRes = R.drawable.ic_menu_tv,
            onTv = false,
        ),

        // ---- Appearance ------------------------------------------------
        Page(
            screenKey = "screen_appearance",
            group = GroupId.APPEARANCE,
            titleRes = R.string.settings_category_appearance,
            summaryRes = R.string.settings_screen_appearance_summary,
            iconRes = R.drawable.ic_brightness,
        ),

        // ---- Library ---------------------------------------------------
        Page(
            screenKey = "screen_watchlist_import",
            group = GroupId.LIBRARY,
            titleRes = R.string.settings_watchlist_import_title,
            summaryRes = R.string.settings_watchlist_import_summary,
            iconRes = R.drawable.ic_import_file,
            onTv = false,
            visibleIf = {
                ProviderSettingsVisibility.showWatchlistImport(UserPreferences.currentProvider)
            },
        ),

        // ---- Downloads -------------------------------------------------
        Page(
            screenKey = "screen_downloads",
            group = GroupId.DOWNLOADS,
            titleRes = R.string.settings_screen_downloads,
            summaryRes = R.string.settings_screen_downloads_summary,
            iconRes = R.drawable.ic_menu_downloads,
        ),
        Page(
            screenKey = "screen_notifications",
            group = GroupId.DOWNLOADS,
            titleRes = R.string.settings_screen_notifications,
            summaryRes = R.string.settings_screen_notifications_summary,
            iconRes = R.drawable.ic_favorite_enable,
        ),

        // ---- Network & Sources -----------------------------------------
        Page(
            screenKey = "screen_provider",
            group = GroupId.NETWORK,
            titleRes = R.string.settings_category_provider_title,
            summaryRes = R.string.settings_screen_provider_summary,
            iconRes = R.drawable.ic_provider_default_logo,
        ),
        Page(
            screenKey = "screen_network",
            group = GroupId.NETWORK,
            titleRes = R.string.settings_category_network_title,
            summaryRes = R.string.settings_screen_network_summary,
            iconRes = R.drawable.ic_providers_language,
        ),

        // ---- Integrations ----------------------------------------------
        Page(
            screenKey = "screen_content",
            group = GroupId.INTEGRATIONS,
            titleRes = R.string.settings_section_content_title,
            summaryRes = R.string.settings_screen_content_summary,
            iconRes = R.drawable.ic_menu_movie,
        ),
        Page(
            screenKey = "screen_platform",
            group = GroupId.INTEGRATIONS,
            titleRes = R.string.platform_settings_title,
            summaryRes = R.string.settings_hub_featured_body,
            iconRes = R.drawable.ic_player_settings_servers,
        ),
        Page(
            screenKey = "screen_serienstream_auth",
            group = GroupId.INTEGRATIONS,
            titleRes = R.string.serienstream_auth_category_title,
            summaryRes = R.string.settings_serienstream_session_login_summary,
            iconRes = R.drawable.ic_providers_language,
            visibleIf = {
                ProviderSettingsVisibility.showSerienStreamSettings(UserPreferences.currentProvider)
            },
        ),
        Page(
            screenKey = "screen_aniworld_auth",
            group = GroupId.INTEGRATIONS,
            titleRes = R.string.aniworld_auth_category_title,
            summaryRes = R.string.settings_aniworld_session_login_summary,
            iconRes = R.drawable.ic_providers_language,
            visibleIf = {
                ProviderSettingsVisibility.showAniWorldSettings(UserPreferences.currentProvider)
            },
        ),
        Page(
            screenKey = "screen_guardaflix_auth",
            group = GroupId.INTEGRATIONS,
            titleRes = R.string.settings_category_guardaflix,
            summaryRes = R.string.settings_serienstream_session_login_summary,
            iconRes = R.drawable.ic_providers_language,
            visibleIf = {
                UserPreferences.currentProvider is
                    com.betterstreamflix.app.providers.GuardaFlixProvider
            },
        ),

        // ---- System ----------------------------------------------------
        Page(
            screenKey = "screen_backup",
            group = GroupId.SYSTEM,
            titleRes = R.string.backup_category_title,
            summaryRes = R.string.settings_screen_backup_summary,
            iconRes = R.drawable.ic_player_settings_download,
        ),
        Page(
            screenKey = "screen_more",
            group = GroupId.SYSTEM,
            titleRes = R.string.miscelaneous_title,
            summaryRes = R.string.settings_hub_more_summary,
            iconRes = R.drawable.ic_settings_help,
        ),
        Page(
            screenKey = "nav_support",
            group = GroupId.SYSTEM,
            titleRes = R.string.support_settings_entry_title,
            summaryRes = R.string.support_settings_entry_summary,
            iconRes = R.drawable.ic_support_heart,
            navTarget = Page.NavTarget.SUPPORT,
        ),
        Page(
            screenKey = "nav_about",
            group = GroupId.SYSTEM,
            titleRes = R.string.settings_about,
            summaryRes = R.string.settings_screen_misc_summary,
            iconRes = R.drawable.ic_settings_about,
            navTarget = Page.NavTarget.ABOUT,
        ),
    )

    /** Pages grouped for display, preserving enum order, only visible ones. */
    fun groups(isTv: Boolean): List<Pair<GroupId, List<Page>>> {
        val visible = pages.filter { it.visible(isTv) }
        return GroupId.entries.mapNotNull { group ->
            val inGroup = visible.filter { it.group == group }
            if (inGroup.isEmpty()) null else group to inGroup
        }
    }

    /** Nav-target pages (Support/About) live outside the section screens. */
    fun navPages(isTv: Boolean): List<Page> =
        pages.filter {
            it.navTarget != null && (if (isTv) it.onTv else it.onMobile)
        }

    fun page(screenKey: String): Page? = pages.firstOrNull { it.screenKey == screenKey }

    /** Group breadcrumb for a screen key, e.g. "Integrations". */
    fun groupOf(screenKey: String): GroupId? = page(screenKey)?.group

    /** Dynamic subtitle for the provider page — shows the active provider. */
    fun summaryOverride(context: android.content.Context, page: Page): CharSequence? =
        if (page.screenKey == "screen_provider") {
            UserPreferences.currentProvider?.name?.let {
                context.getString(R.string.settings_screen_provider_summary_with_name, it)
            }
        } else {
            null
        }
}
