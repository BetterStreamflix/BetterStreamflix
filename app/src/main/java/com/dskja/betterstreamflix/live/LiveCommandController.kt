package com.dskja.betterstreamflix.live

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.notifications.LiveReminderArm
import com.dskja.betterstreamflix.notifications.LiveReminderStore
import com.dskja.betterstreamflix.ui.AmbientModePolicy
import com.dskja.betterstreamflix.ui.FeaturedAdvancePolicy
import com.dskja.betterstreamflix.ui.UserDataNotifier
import com.dskja.betterstreamflix.utils.AccessibilityPreferences
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.utils.TvFocusChain
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.widgets.WidgetDeepLinkRouter
import com.dskja.betterstreamflix.widgets.WidgetDeepLinks

/**
 * Binds Live Command chrome on Live Hub (Mobile + TV): mode chips, EPG search,
 * command actions (Sender-Leiste / Reminders / Ambient / PiP Zap), status line.
 *
 * [onVisibleChanged] receives the visible categories and whether the host should
 * move focus to the channel grid (mode change / initial bind only — never while
 * the search box has focus or the query is non-blank).
 */
class LiveCommandController(
    private val fragment: Fragment,
    private val root: View,
    private val isTv: Boolean,
    private val onVisibleChanged: (List<Category>, focusGrid: Boolean) -> Unit,
    private val onModeChanged: (LiveCommandMode) -> Unit = {},
    private val focusPins: () -> Unit = {},
) {
    private val modesRow: HorizontalScrollView? = root.findViewById(R.id.hsv_live_command_modes)
    private val modesHost: LinearLayout? = root.findViewById(R.id.ll_live_command_modes)
    private val actionsHost: LinearLayout? = root.findViewById(R.id.ll_live_command_actions)
    private val searchBox: EditText? = root.findViewById(R.id.et_live_command_search)
    private val searchClear: View? = root.findViewById(R.id.btn_live_command_search_clear)
    private val statusView: TextView? = root.findViewById(R.id.tv_live_command_status)
    private val chrome: View? = root.findViewById(R.id.live_command_chrome)

    private var mode: LiveCommandMode =
        LiveCommandMode.fromStorage(UserPreferences.liveCommandMode)
    private var query: String = ""
    private var hubCategories: List<Category> = emptyList()
    private var pinCount: Int = 0
    private var pipCandidates: Int = 0
    private var initialBindDone: Boolean = false

    private val modeChips = LinkedHashMap<LiveCommandMode, TextView>()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchDebounce: Runnable? = null

    fun attach() {
        chrome?.isVisible = true
        bindModeChips()
        bindSearch()
        refreshActions()
        applySelection()
    }

    fun detach() {
        searchDebounce?.let { searchHandler.removeCallbacks(it) }
        searchDebounce = null
    }

    fun setRestricted(restricted: Boolean) {
        chrome?.isVisible = !restricted
        if (restricted) {
            hubCategories = emptyList()
            pinCount = 0
            pipCandidates = 0
        }
    }

    fun bindHub(
        categories: List<Category>,
        pinCount: Int = ChannelPinStore.pinCount(fragment.requireContext()),
        pipCandidates: Int = pinCount,
    ) {
        hubCategories = categories
        this.pinCount = pinCount
        this.pipCandidates = pipCandidates.coerceAtLeast(0)
        refreshActions()
        val focusGrid = !initialBindDone
        initialBindDone = true
        publish(focusGrid = focusGrid)
    }

    fun bindEmpty() {
        hubCategories = emptyList()
        pinCount = 0
        pipCandidates = 0
        refreshActions()
        publish(focusGrid = false)
    }

    fun currentMode(): LiveCommandMode = mode

    fun currentQuery(): String = query

    fun clearQuery() {
        searchDebounce?.let { searchHandler.removeCallbacks(it) }
        searchDebounce = null
        query = ""
        searchBox?.setText("")
        searchClear?.isVisible = false
        if (isTv) {
            searchClear?.isFocusable = false
            searchClear?.isFocusableInTouchMode = false
        }
        publish(focusGrid = false)
    }

    fun setMode(next: LiveCommandMode) {
        selectMode(next)
    }

    /** True when EPG search or a mode/action chip currently holds focus. */
    fun chromeHasFocus(): Boolean {
        val focused = fragment.activity?.currentFocus ?: return false
        if (focused === searchBox || focused === searchClear) return true
        if (focused.id == R.id.et_live_command_search ||
            focused.id == R.id.btn_live_command_search_clear
        ) {
            return true
        }
        if (modeChips.values.any { it === focused || it.hasFocus() }) return true
        val modes = modesHost
        if (modes != null && focused.isDescendantOf(modes)) return true
        val actions = actionsHost
        if (actions != null && focused.isDescendantOf(actions)) return true
        return false
    }

    fun firstModeChip(): View? = modeChips.values.firstOrNull()

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun reminderLabels(): LiveCommandPolicy.ReminderLabelResources {
        val ctx = fragment.requireContext()
        return LiveCommandPolicy.ReminderLabelResources(
            snoozed = ctx.getString(R.string.live_command_reminder_snoozed),
            live = ctx.getString(R.string.live_command_reminder_live),
            startsInHours = ctx.getString(R.string.live_command_reminder_starts_in_hours),
            startsInMinutes = ctx.getString(R.string.live_command_reminder_starts_in_minutes),
            startedMinutesAgo = ctx.getString(R.string.live_command_reminder_started_ago),
        )
    }

    private fun bindModeChips() {
        val host = modesHost ?: return
        host.removeAllViews()
        modeChips.clear()
        val inflater = fragment.layoutInflater
        for (m in LiveCommandMode.entries) {
            val chip = inflater.inflate(R.layout.view_live_command_chip, host, false) as TextView
            chip.text = modeLabel(m)
            chip.setOnClickListener {
                ExpMotion.hapticTap(it)
                selectMode(m)
            }
            if (isTv) {
                chip.isFocusable = true
                chip.isFocusableInTouchMode = false
            }
            host.addView(chip)
            modeChips[m] = chip
        }
        modesRow?.isVisible = true
        if (isTv) wireChromeFocusChain()
    }

    private fun bindSearch() {
        val box = searchBox ?: return
        box.hint = fragment.getString(R.string.live_command_search_hint)
        box.setText(query)
        searchClear?.isVisible = query.isNotBlank()
        searchClear?.setOnClickListener {
            clearQuery()
        }
        box.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDebounce?.let { searchHandler.removeCallbacks(it) }
                query = box.text?.toString().orEmpty()
                publish(focusGrid = false)
                true
            } else {
                false
            }
        }
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                val clear = searchClear
                if (clear != null) {
                    clear.isVisible = query.isNotBlank()
                    // FA-019: clear is DPAD-reachable when visible.
                    if (isTv) {
                        clear.isFocusable = clear.isVisible
                        clear.isFocusableInTouchMode = clear.isVisible
                    }
                }
                searchDebounce?.let { searchHandler.removeCallbacks(it) }
                val run = Runnable { publish(focusGrid = false) }
                searchDebounce = run
                searchHandler.postDelayed(run, SEARCH_DEBOUNCE_MS)
            }
        })
        // TV: keep focusable for DPAD but not touch-mode so keyboards don't steal first focus.
        box.isFocusable = true
        box.isFocusableInTouchMode = !isTv
        if (isTv) {
            searchClear?.isFocusable = query.isNotBlank()
            searchClear?.isFocusableInTouchMode = query.isNotBlank()
        }
        updateSearchNextFocus()
        if (isTv) wireChromeFocusChain()
    }

    private fun selectMode(next: LiveCommandMode) {
        // Clear query when entering Reminders so search does not override the panel.
        if (next == LiveCommandMode.REMINDERS && query.isNotBlank()) {
            searchDebounce?.let { searchHandler.removeCallbacks(it) }
            searchDebounce = null
            query = ""
            searchBox?.setText("")
            searchClear?.isVisible = false
        }
        val modeChanged = mode != next
        mode = next
        UserPreferences.liveCommandMode = next.name
        if (modeChanged) {
            onModeChanged(next)
        }
        applySelection()
        publish(focusGrid = isTv && modeChanged)
    }

    private fun applySelection() {
        for ((m, chip) in modeChips) {
            val selected = m == mode
            chip.isSelected = selected
            chip.setBackgroundResource(
                if (selected) R.drawable.bg_download_filter_chip_selected
                else R.drawable.bg_download_filter_chip,
            )
            // FA-018: cinema theme tokens (not hardcoded black/white) for HC / Looks.
            chip.setTextColor(
                if (selected) {
                    fragment.resources.getColor(R.color.cinema_surface, fragment.requireContext().theme)
                } else {
                    fragment.resources.getColor(R.color.cinema_text, fragment.requireContext().theme)
                },
            )
            val label = modeLabel(m)
            chip.contentDescription = if (selected) {
                fragment.getString(R.string.live_command_cd_mode_selected, label)
            } else {
                label
            }
            chip.accessibilityLiveRegion = if (selected) {
                View.ACCESSIBILITY_LIVE_REGION_POLITE
            } else {
                View.ACCESSIBILITY_LIVE_REGION_NONE
            }
            chip.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isSelected = selected
                }
            })
            // FA-032: announce selection for TalkBack without relying on visual alone.
            AccessibilityPreferences.announceSelected(chip, selected)
        }
        if (isTv) wireChromeFocusChain()
    }

    private fun publish(focusGrid: Boolean) {

        val favorites = hubCategories
            .firstOrNull { it.identityKey == LiveHubCatalog.SECTION_FAVORITES }
            ?.list
            ?.filterIsInstance<TvShow>()
            .orEmpty()
        val now = System.currentTimeMillis()
        val labels = reminderLabels()
        var visible = LiveCommandPolicy.resolveVisible(
            categories = hubCategories,
            mode = mode,
            query = query,
            searchLabel = fragment.getString(R.string.live_command_search_results),
            remindersLabel = fragment.getString(R.string.live_command_mode_reminders),
            favorites = favorites,
            nowMs = now,
            snoozedUntil = { LiveReminderStore.snoozedUntil(it) },
            labels = labels,
        )
        // Restored REMINDERS with no rows → fall back to All after publish path.
        if (mode == LiveCommandMode.REMINDERS &&
            query.isBlank() &&
            visible.isEmpty() &&
            hubCategories.isNotEmpty()
        ) {
            mode = LiveCommandMode.ALL
            UserPreferences.liveCommandMode = LiveCommandMode.ALL.name
            applySelection()
            visible = LiveCommandPolicy.resolveVisible(
                categories = hubCategories,
                mode = mode,
                query = query,
                searchLabel = fragment.getString(R.string.live_command_search_results),
                remindersLabel = fragment.getString(R.string.live_command_mode_reminders),
                favorites = favorites,
                nowMs = now,
                snoozedUntil = { LiveReminderStore.snoozedUntil(it) },
                labels = labels,
            )
        }
        val reminderRows = LiveCommandPolicy.buildReminderRows(
            favorites = favorites,
            nowMs = now,
            snoozedUntil = { LiveReminderStore.snoozedUntil(it) },
            labels = labels,
        )
        val reminderCount = reminderRows.size
        val shelfCount = visible.count { it.list.isNotEmpty() }
        val itemCount = visible.sumOf { it.list.size }
        val favoriteCount = favorites.size
        bindStatus(
            LiveCommandPolicy.statusLine(
                mode = mode,
                query = query,
                visibleCount = itemCount,
                reminderCount = reminderCount,
                pinCount = pinCount,
                favoriteCount = favoriteCount,
                shelfCount = shelfCount,
            ),
        )
        val searchFocused = searchBox?.hasFocus() == true
        val allowFocus = focusGrid && query.isBlank() && !searchFocused
        onVisibleChanged(visible, allowFocus)
    }

    private fun bindStatus(kind: LiveCommandPolicy.StatusKind) {
        val tv = statusView ?: return
        tv.text = when (kind) {
            is LiveCommandPolicy.StatusKind.Search ->
                fragment.getString(R.string.live_command_status_search, kind.hits, kind.query)
            is LiveCommandPolicy.StatusKind.Reminders ->
                fragment.getString(R.string.live_command_status_reminders, kind.count)
            LiveCommandPolicy.StatusKind.SportZap ->
                fragment.getString(R.string.live_command_status_sport_zap)
            is LiveCommandPolicy.StatusKind.News ->
                fragment.getString(R.string.live_command_status_news, kind.count)
            is LiveCommandPolicy.StatusKind.Favorites ->
                fragment.getString(R.string.live_command_status_favorites, kind.favoriteCount)
            is LiveCommandPolicy.StatusKind.Mode ->
                fragment.getString(R.string.live_command_status_mode, modeLabel(kind.mode), kind.shelves)
        }
        tv.isVisible = true
    }

    private fun refreshActions() {
        val host = actionsHost ?: return
        host.removeAllViews()
        val favorites = hubCategories
            .firstOrNull { it.identityKey == LiveHubCatalog.SECTION_FAVORITES }
            ?.list
            ?.filterIsInstance<TvShow>()
            .orEmpty()
        val reminderCount = LiveCommandPolicy.buildReminderRows(
            favorites = favorites,
            nowMs = System.currentTimeMillis(),
            snoozedUntil = { LiveReminderStore.snoozedUntil(it) },
            labels = reminderLabels(),
        ).size
        val snapshot = LiveCommandPolicy.CommandSnapshot(
            pinCount = pinCount,
            reminderCount = reminderCount,
            remindersEnabled = UserPreferences.notifyLiveFavoriteStarting,
            ambientEnabled = UserPreferences.tvAmbientModeEnabled,
            // Cinema Room + hard reduce + TalkBack / reduce-motion / HC gates (FA-011 / SE-007).
            ambientAvailable = isTv &&
                UserPreferences.cinemaRoomEnabled &&
                run {
                    val ctx = fragment.requireContext()
                    AmbientModePolicy.highEndOk(
                        hardReduceEffects = DeviceCapabilities.shouldHardReduceHomeEffects(ctx),
                        touchExploration = FeaturedAdvancePolicy.isTouchExplorationEnabled(ctx),
                        reduceMotion = FeaturedAdvancePolicy.shouldReduceMotion(ctx) ||
                            DeviceCapabilities.isAnimatorDurationScaleOff(ctx),
                        highContrast = UserPreferences.highContrast,
                    )
                },
            pipCandidateCount = pipCandidates,
            isTv = isTv,
        )
        val inflater = fragment.layoutInflater
        for (action in LiveCommandPolicy.visibleActions(snapshot)) {
            val chip = inflater.inflate(R.layout.view_live_command_chip, host, false) as TextView
            chip.text = actionLabel(action, snapshot)
            chip.setBackgroundResource(R.drawable.bg_download_filter_chip)
            chip.setTextColor(
                fragment.resources.getColor(R.color.cinema_text, fragment.requireContext().theme),
            )
            if (isTv) {
                chip.isFocusable = true
                chip.isFocusableInTouchMode = false
            }
            chip.setOnClickListener {
                ExpMotion.hapticTap(it)
                handleAction(action, snapshot)
            }
            host.addView(chip)
        }
        host.isVisible = host.childCount > 0
        updateSearchNextFocus()
        if (isTv) wireChromeFocusChain()
    }

    private fun updateSearchNextFocus() {
        val box = searchBox ?: return
        val actionsVisible = actionsHost?.isVisible == true && (actionsHost?.childCount ?: 0) > 0
        box.nextFocusDownId = when {
            actionsVisible -> R.id.ll_live_command_actions
            isTv -> R.id.vgv_live_hub
            else -> R.id.rv_live_hub
        }
    }

    /** FA-012: modes → search → clear → actions vertical + horizontal chains. */
    private fun wireChromeFocusChain() {
        val modes = modeChips.values.toList()
        if (modes.isNotEmpty()) {
            TvFocusChain.linkHorizontal(*modes.toTypedArray())
        }
        val actions = mutableListOf<View>()
        val host = actionsHost
        if (host != null) {
            for (i in 0 until host.childCount) {
                val child = host.getChildAt(i)
                if (child.isFocusable && child.isVisible) actions.add(child)
            }
            if (actions.isNotEmpty()) {
                TvFocusChain.linkHorizontal(*actions.toTypedArray())
            }
        }
        val search = searchBox
        val clear = searchClear?.takeIf { it.isVisible && it.isFocusable }
        val column = buildList {
            modes.firstOrNull()?.let { add(it) }
            search?.let { add(it) }
            clear?.let { add(it) }
            actions.firstOrNull()?.let { add(it) }
        }
        if (column.size >= 2) {
            TvFocusChain.linkVertical(*column.toTypedArray())
        }
        // Status is polite live region — do not steal TalkBack (FA-042).
        statusView?.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO
        statusView?.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun handleAction(
        action: LiveCommandPolicy.CommandAction,
        snapshot: LiveCommandPolicy.CommandSnapshot,
    ) {
        val ctx = fragment.requireContext()
        when (action) {
            LiveCommandPolicy.CommandAction.PINS -> {
                focusPins()
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.live_command_toast_pins, snapshot.pinCount),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            LiveCommandPolicy.CommandAction.REMINDERS -> {
                if (UserPreferences.notifyLiveFavoriteStarting) {
                    LiveReminderArm.disarmFromUi(ctx)
                    Toast.makeText(
                        ctx,
                        R.string.live_command_toast_reminders_off,
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    UserPreferences.notifyLiveFavoriteStarting = true
                    LiveReminderArm.armFromUi(fragment)
                    Toast.makeText(
                        ctx,
                        R.string.live_command_toast_reminders_on,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                UserDataNotifier.notifyChanged()
                refreshActions()
                selectMode(LiveCommandMode.REMINDERS)
            }
            LiveCommandPolicy.CommandAction.AMBIENT -> {
                if (!UserPreferences.cinemaRoomEnabled) {
                    Toast.makeText(
                        ctx,
                        R.string.live_command_toast_ambient_off,
                        Toast.LENGTH_SHORT,
                    ).show()
                    refreshActions()
                    return
                }
                val next = !UserPreferences.tvAmbientModeEnabled
                UserPreferences.tvAmbientModeEnabled = next
                Toast.makeText(
                    ctx,
                    if (next) R.string.live_command_toast_ambient_on
                    else R.string.live_command_toast_ambient_off,
                    Toast.LENGTH_SHORT,
                ).show()
                // Exit Ambient immediately when toggled off while Home is showing it.
                (fragment.activity as? com.dskja.betterstreamflix.activities.main.MainTvActivity)
                    ?.let { act ->
                        val home = act.supportFragmentManager
                            .findFragmentById(com.dskja.betterstreamflix.R.id.nav_main_fragment)
                            as? androidx.navigation.fragment.NavHostFragment
                        val host = home?.childFragmentManager?.primaryNavigationFragment
                            as? com.dskja.betterstreamflix.fragments.home.HomeTvFragment
                        host?.onAmbientGatesChanged()
                    }
                refreshActions()
            }
            LiveCommandPolicy.CommandAction.PIP_ZAP -> {
                val activity = fragment.activity
                val firstPin = ChannelPinStore.list(ctx).firstOrNull()
                if (activity != null && firstPin != null) {
                    WidgetDeepLinkRouter.handle(
                        activity,
                        WidgetDeepLinks.Target.LiveChannel(
                            id = firstPin.id,
                            provider = firstPin.provider,
                            title = firstPin.title,
                            poster = firstPin.poster,
                        ),
                    )
                } else {
                    Toast.makeText(
                        ctx,
                        R.string.live_command_toast_pip_need_live,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun modeLabel(mode: LiveCommandMode): String = when (mode) {
        LiveCommandMode.ALL -> fragment.getString(R.string.live_command_mode_all)
        LiveCommandMode.FAVORITES -> fragment.getString(R.string.live_hub_section_favorites)
        LiveCommandMode.SPORT -> fragment.getString(R.string.live_hub_section_sport)
        LiveCommandMode.NEWS -> fragment.getString(R.string.live_hub_section_news)
        LiveCommandMode.CHANNELS -> fragment.getString(R.string.live_hub_section_channels)
        LiveCommandMode.REMINDERS -> fragment.getString(R.string.live_command_mode_reminders)
    }

    private fun actionLabel(
        action: LiveCommandPolicy.CommandAction,
        snapshot: LiveCommandPolicy.CommandSnapshot,
    ): String = when (action) {
        LiveCommandPolicy.CommandAction.PINS ->
            fragment.getString(R.string.live_command_action_pins, snapshot.pinCount)
        LiveCommandPolicy.CommandAction.REMINDERS ->
            fragment.getString(R.string.live_command_action_reminders)
        LiveCommandPolicy.CommandAction.AMBIENT ->
            if (snapshot.ambientEnabled) {
                fragment.getString(R.string.live_command_action_ambient_on)
            } else {
                fragment.getString(R.string.live_command_action_ambient_off)
            }
        LiveCommandPolicy.CommandAction.PIP_ZAP ->
            fragment.getString(R.string.live_command_action_pip)
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 200L

        fun refreshPinStrip(activity: FragmentActivity?) {
            UserDataNotifier.notifyChanged()
            activity ?: return
        }
    }
}
