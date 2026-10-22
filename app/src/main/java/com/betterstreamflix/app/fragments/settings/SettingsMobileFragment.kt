package com.betterstreamflix.app.fragments.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.findNavController
import com.betterstreamflix.app.R
import com.betterstreamflix.app.settings.SettingsCatalog
import com.betterstreamflix.app.settings.SettingsRepository
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.UserPreferences

/**
 * Cinema-Ink settings hub (mobile).
 *
 * Replaces the former flat AndroidX-Preference root: a catalog-driven
 * grouped home with search, a recently-touched strip and a provider/status
 * header. Sections open as [SettingsSectionFragment] children inside
 * [R.id.section_container]; their content stays the proven `screen_*`
 * preference subtrees, so every existing key/wiring keeps working.
 */
class SettingsMobileFragment : Fragment() {

    private var searchIndex: List<SettingsSearchIndex.Entry>? = null

    private val backStackListener = FragmentManager.OnBackStackChangedListener {
        syncSectionVisibility()
        renderRecents()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings_shell_mobile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        SettingsRepository.init(ctx)

        view.findViewById<View>(R.id.card_settings_search).setOnClickListener {
            ExpMotion.hapticTap(it)
            openSearch()
        }

        view.findViewById<TextView>(R.id.tv_settings_shell_provider).apply {
            val providerName = UserPreferences.currentProvider?.name
            if (providerName.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = getString(R.string.settings_shell_watching_via, providerName)
            }
        }

        renderGroups()
        renderRecents()
        syncSectionVisibility()

        childFragmentManager.addOnBackStackChangedListener(backStackListener)

        if (view.getTag(R.id.exp_enter_animated_tag) != true) {
            view.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.enterScreen(view)
        }

        view.post { consumeDeepLink() }
    }

    override fun onResume() {
        super.onResume()
        // Provider/profile visibility can change while away — rebuild cheaply.
        renderGroups()
        renderRecents()
    }

    override fun onDestroyView() {
        childFragmentManager.removeOnBackStackChangedListener(backStackListener)
        super.onDestroyView()
    }

    // ---- Sections ---------------------------------------------------------

    private fun openSection(
        screenKey: String,
        title: String? = null,
        flashKey: String? = null,
    ) {
        val resolvedTitle = title ?: pageTitle(screenKey)
        childFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.nav_enter, R.anim.nav_exit,
                R.anim.nav_pop_enter, R.anim.nav_pop_exit,
            )
            .replace(
                R.id.section_container,
                SettingsSectionFragment.newInstance(screenKey, resolvedTitle, flashKey),
            )
            .addToBackStack(screenKey)
            .commit()
        childFragmentManager.executePendingTransactions()
        syncSectionVisibility()
        SettingsRepository.markTouched(screenKey)
    }

    private fun pageTitle(screenKey: String): String =
        SettingsCatalog.page(screenKey)?.let { getString(it.titleRes) }
            ?: searchIndex?.firstOrNull { it.screenKey == screenKey }?.screenTitle?.takeIf { it.isNotBlank() }
            ?: getString(R.string.player_settings_title)

    private fun syncSectionVisibility() {
        val container = view?.findViewById<View>(R.id.section_container) ?: return
        val hasPage = childFragmentManager.backStackEntryCount > 0
        container.visibility = if (hasPage) View.VISIBLE else View.GONE
    }

    // ---- Deep links --------------------------------------------------------

    private fun consumeDeepLink() {
        val key = SettingsDeepLink.consumePendingScreenKey() ?: return
        if (!isAdded) return
        val title = when (key) {
            "screen_serienstream_auth" -> getString(R.string.serienstream_auth_category_title)
            "screen_aniworld_auth" -> getString(R.string.aniworld_auth_category_title)
            "screen_guardaflix_auth" -> getString(R.string.settings_category_guardaflix)
            "screen_notifications" -> getString(R.string.settings_screen_notifications)
            "screen_parent_dashboard" -> getString(R.string.parent_dashboard_title)
            else -> pageTitle(key)
        }
        openSection(key, title)
    }

    // ---- Search ------------------------------------------------------------

    private fun index(): List<SettingsSearchIndex.Entry> {
        return searchIndex ?: SettingsSearchIndex.index(requireContext(), R.xml.settings_mobile)
            .also { searchIndex = it }
    }

    private fun openSearch() {
        val ctx = context ?: return
        SettingsSearchDialog.show(ctx, R.xml.settings_mobile) { entry ->
            SettingsRepository.markTouched(entry.prefKey)
            entry.screenKey?.let { openSection(it, entry.screenTitle.ifBlank { null }, entry.prefKey) }
        }
    }

    // ---- Rendering ----------------------------------------------------------

    private fun renderGroups() {
        val container = view?.findViewById<LinearLayout>(R.id.ll_settings_groups) ?: return
        val ctx = requireContext()
        container.removeAllViews()

        // Merge nav pages into their declared groups.
        val navPages = SettingsCatalog.navPages(isTv = false)
        val rendered = LinkedHashMap<SettingsCatalog.GroupId, MutableList<SettingsCatalog.Page>>()
        SettingsCatalog.groups(isTv = false).forEach { (g, pages) ->
            rendered.getOrPut(g) { mutableListOf() } += pages
        }
        navPages.forEach { page -> rendered.getOrPut(page.group) { mutableListOf() } += page }

        rendered.forEach { (group, pages) ->
            val header = layoutInflater.inflate(
                R.layout.item_settings_group_header, container, false,
            ) as TextView
            header.text = getString(group.titleRes)
            container.addView(header)

            pages.forEach { page ->
                val card = layoutInflater.inflate(
                    R.layout.item_settings_hub_card, container, false,
                )
                card.findViewById<ImageView>(R.id.iv_settings_hub_card_icon)
                    .setImageResource(page.iconRes)
                card.findViewById<TextView>(R.id.tv_settings_hub_card_title)
                    .text = getString(page.titleRes)
                card.findViewById<TextView>(R.id.tv_settings_hub_card_summary)
                    .text = SettingsCatalog.summaryOverride(ctx, page)
                        ?: getString(page.summaryRes)
                card.contentDescription =
                    getString(R.string.settings_section_card_cd, getString(page.titleRes))
                card.setOnClickListener {
                    ExpMotion.hapticTap(it)
                    openPage(page)
                }
                container.addView(card)
            }
        }
    }

    private fun openPage(page: SettingsCatalog.Page) {
        when (page.navTarget) {
            SettingsCatalog.Page.NavTarget.SUPPORT ->
                runCatching { findNavController().navigate(R.id.support) }
            SettingsCatalog.Page.NavTarget.ABOUT ->
                runCatching { findNavController().navigate(R.id.settings_about) }
            null -> openSection(page.screenKey, getString(page.titleRes))
        }
    }

    private fun renderRecents() {
        val header = view?.findViewById<TextView>(R.id.tv_settings_recents_header) ?: return
        val scroller = view?.findViewById<View>(R.id.hsv_settings_recents) ?: return
        val container = view?.findViewById<LinearLayout>(R.id.ll_settings_recents) ?: return

        val byKey = index().associateBy { it.prefKey }
        val recents = SettingsRepository.recentKeys()
            .mapNotNull { key ->
                val entry = byKey[key]
                val title = entry?.title ?: SettingsCatalog.page(key)?.let { getString(it.titleRes) }
                val section = entry?.screenKey ?: SettingsCatalog.page(key)?.screenKey
                if (title == null || section == null) null
                else Triple(title, entry?.path?.ifBlank { null }
                    ?: SettingsCatalog.groupOf(section)?.let { getString(it.titleRes) }
                    ?: "", Triple(key, section, entry?.prefKey))
            }

        if (recents.isEmpty()) {
            header.visibility = View.GONE
            scroller.visibility = View.GONE
            return
        }
        header.visibility = View.VISIBLE
        scroller.visibility = View.VISIBLE
        container.removeAllViews()
        recents.forEach { (title, path, nav) ->
            val row = layoutInflater.inflate(
                R.layout.item_settings_recent_row, container, false,
            )
            row.findViewById<TextView>(R.id.tv_settings_recent_title).text = title
            val pathView = row.findViewById<TextView>(R.id.tv_settings_recent_path)
            if (path.isBlank()) pathView.visibility = View.GONE else pathView.text = path
            val (rowKey, section, flash) = nav
            row.setOnClickListener {
                ExpMotion.hapticTap(it)
                SettingsRepository.markTouched(rowKey)
                openSection(section, null, flash)
            }
            container.addView(row)
        }
    }
}
