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

/**
 * Cinema-Ink settings hub (TV / Leanback).
 *
 * Two-pane layout: left rail lists the grouped [SettingsCatalog] pages with
 * full D-pad focus choreography; the right pane hosts the active
 * [SettingsTvSectionFragment] (the proven `screen_*` preference subtree).
 * Nested screens push onto the child back stack inside the pane; the rail
 * keeps its selection highlighted for the top-level page.
 */
class SettingsTvFragment : Fragment() {

    private var searchIndex: List<SettingsSearchIndex.Entry>? = null
    private var selectedScreenKey: String? = null
    private val railRows = HashMap<String, View>()

    private val backStackListener = FragmentManager.OnBackStackChangedListener {
        // A nested page sits on top — keep the top-level rail highlight.
        renderRecents()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings_shell_tv, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        SettingsRepository.init(ctx)

        view.findViewById<View>(R.id.card_settings_search_tv).setOnClickListener {
            ExpMotion.hapticTap(it)
            openSearch()
        }

        renderRail()
        renderRecents()

        // The rail frame is focusable so the pane's nextFocusLeft lands on it;
        // forward the focus to the currently selected rail row.
        view.findViewById<View>(R.id.settings_tv_rail).setOnFocusChangeListener { _, focused ->
            if (focused) {
                railRows[selectedScreenKey]?.requestFocus()
                    ?: railRows.values.firstOrNull()?.requestFocus()
            }
        }

        childFragmentManager.addOnBackStackChangedListener(backStackListener)
        selectedScreenKey = savedInstanceState?.getString("selected_screen_key")

        // Deep link wins over the default selection.
        val pending = SettingsDeepLink.consumePendingScreenKey()
        if (pending != null) {
            selectPage(pending, focusRail = false)
        } else if (savedInstanceState == null) {
            val first = SettingsCatalog.groups(isTv = true)
                .firstOrNull()?.second?.firstOrNull()?.screenKey
            if (first != null) selectPage(first, focusRail = false)
        } else {
            selectedScreenKey?.let { highlightRail(it) }
        }

        if (view.getTag(R.id.exp_enter_animated_tag) != true) {
            view.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.enterScreen(view)
        }
    }

    override fun onResume() {
        super.onResume()
        val railHadFocus = view?.findViewById<View>(R.id.settings_tv_rail)
            ?.hasFocus() == true
        renderRail()
        renderRecents()
        selectedScreenKey?.let { key ->
            highlightRail(key)
            if (railHadFocus) railRows[key]?.requestFocus()
        }
        SettingsDeepLink.consumePendingScreenKey()?.let { key ->
            selectPage(key, focusRail = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected_screen_key", selectedScreenKey)
    }

    override fun onDestroyView() {
        childFragmentManager.removeOnBackStackChangedListener(backStackListener)
        super.onDestroyView()
    }

    // ---- Pages --------------------------------------------------------------

    private fun selectPage(
        screenKey: String,
        title: String? = null,
        flashKey: String? = null,
        focusRail: Boolean = false,
    ) {
        selectedScreenKey = screenKey
        highlightRail(screenKey)
        SettingsRepository.markTouched(screenKey)

        // Drop any nested stack so a top-level selection always lands cleanly.
        runCatching {
            while (childFragmentManager.backStackEntryCount > 0) {
                childFragmentManager.popBackStackImmediate()
            }
        }
        childFragmentManager.beginTransaction()
            .replace(
                R.id.settings_tv_pane,
                SettingsTvSectionFragment.newInstance(screenKey, title, flashKey),
            )
            .commit()
        if (focusRail) {
            railRows[screenKey]?.requestFocus()
        }
    }

    private fun openPage(page: SettingsCatalog.Page) {
        when (page.navTarget) {
            SettingsCatalog.Page.NavTarget.SUPPORT ->
                runCatching { findNavController().navigate(R.id.support) }
            SettingsCatalog.Page.NavTarget.ABOUT ->
                runCatching { findNavController().navigate(R.id.settings_about) }
            null -> selectPage(page.screenKey, getString(page.titleRes))
        }
    }

    private fun highlightRail(screenKey: String) {
        railRows.forEach { (key, row) ->
            row.findViewById<View>(R.id.v_settings_rail_indicator)
                ?.visibility = if (key == screenKey) View.VISIBLE else View.INVISIBLE
        }
    }

    // ---- Search --------------------------------------------------------------

    private fun index(): List<SettingsSearchIndex.Entry> {
        return searchIndex ?: SettingsSearchIndex.index(requireContext(), R.xml.settings_tv)
            .also { searchIndex = it }
    }

    private fun openSearch() {
        val ctx = context ?: return
        SettingsSearchDialog.show(ctx, R.xml.settings_tv, isTv = true) { entry ->
            SettingsRepository.markTouched(entry.prefKey)
            entry.screenKey?.let {
                selectPage(it, entry.screenTitle.ifBlank { null }, entry.prefKey)
            }
        }
    }

    // ---- Rendering -----------------------------------------------------------

    private fun renderRail() {
        val container = view?.findViewById<LinearLayout>(R.id.ll_settings_rail) ?: return
        val ctx = requireContext()
        container.removeAllViews()
        railRows.clear()

        val rendered = LinkedHashMap<SettingsCatalog.GroupId, MutableList<SettingsCatalog.Page>>()
        SettingsCatalog.groups(isTv = true).forEach { (g, pages) ->
            rendered.getOrPut(g) { mutableListOf() } += pages
        }
        SettingsCatalog.navPages(isTv = true).forEach { page ->
            rendered.getOrPut(page.group) { mutableListOf() } += page
        }

        rendered.forEach { (group, pages) ->
            val header = layoutInflater.inflate(
                R.layout.item_settings_group_header, container, false,
            ) as TextView
            header.text = getString(group.titleRes)
            container.addView(header)

            pages.forEach { page ->
                val row = layoutInflater.inflate(
                    R.layout.item_settings_rail_row, container, false,
                )
                row.findViewById<ImageView>(R.id.iv_settings_rail_icon)
                    .setImageResource(page.iconRes)
                row.findViewById<TextView>(R.id.tv_settings_rail_title)
                    .text = getString(page.titleRes)
                row.contentDescription =
                    getString(R.string.settings_section_card_cd, getString(page.titleRes))
                row.setOnClickListener {
                    ExpMotion.hapticTap(it)
                    openPage(page)
                }
                row.setOnFocusChangeListener { v, focused ->
                    v.animate().scaleX(if (focused) 1.03f else 1f)
                        .scaleY(if (focused) 1.03f else 1f)
                        .setDuration(120)
                        .start()
                }
                railRows[page.screenKey] = row
                container.addView(row)
            }
        }
    }

    private fun renderRecents() {
        val header = view?.findViewById<TextView>(R.id.tv_settings_recents_header_tv) ?: return
        val container = view?.findViewById<LinearLayout>(R.id.ll_settings_recents_tv) ?: return

        val byKey = index().associateBy { it.prefKey }
        val recents = SettingsRepository.recentKeys()
            .mapNotNull { key ->
                val entry = byKey[key]
                val title = entry?.title ?: SettingsCatalog.page(key)?.let { getString(it.titleRes) }
                val section = entry?.screenKey ?: SettingsCatalog.page(key)?.screenKey
                if (title == null || section == null) null
                else Triple(title, section, entry?.prefKey)
            }

        if (recents.isEmpty()) {
            header.visibility = View.GONE
            container.removeAllViews()
            return
        }
        header.visibility = View.VISIBLE
        container.removeAllViews()
        recents.take(3).forEach { (title, section, flash) ->
            val row = layoutInflater.inflate(
                R.layout.item_settings_rail_row, container, false,
            )
            row.findViewById<ImageView>(R.id.iv_settings_rail_icon)
                .setImageResource(R.drawable.ic_refresh)
            row.findViewById<TextView>(R.id.tv_settings_rail_title).text = title
            row.setOnClickListener {
                ExpMotion.hapticTap(it)
                selectPage(section, null, flash)
            }
            container.addView(row)
        }
    }
}
