package com.dskja.betterstreamflix.live

import android.text.Editable
import android.text.TextWatcher
import android.view.View
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
import com.dskja.betterstreamflix.notifications.LiveReminderStore
import com.dskja.betterstreamflix.ui.AmbientModePolicy
import com.dskja.betterstreamflix.ui.FeaturedAdvancePolicy
import com.dskja.betterstreamflix.ui.UserDataNotifier
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Binds Live Command chrome on Live Hub (Mobile + TV): mode chips, EPG search,
 * command actions (Sender-Leiste / Reminders / Ambient / PiP Zap), status line.
 */
class LiveCommandController(
    private val fragment: Fragment,
    private val root: View,
    private val isTv: Boolean,
    private val onVisibleChanged: (List<Category>) -> Unit,
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

    private val modeChips = LinkedHashMap<LiveCommandMode, TextView>()

    fun attach() {
        chrome?.isVisible = true
        bindModeChips()
        bindSearch()
        refreshActions()
        applySelection()
    }

    fun setRestricted(restricted: Boolean) {
        chrome?.isVisible = !restricted
    }

    fun bindHub(
        categories: List<Category>,
        pinCount: Int = ChannelPinStore.pinCount(fragment.requireContext()),
        pipCandidates: Int = pinCount,
    ) {
        hubCategories = categories
        this.pinCount = pinCount
        this.pipCandidates = pipCandidates.coerceAtLeast(pinCount)
        refreshActions()
        publish()
    }

    fun currentMode(): LiveCommandMode = mode

    fun currentQuery(): String = query

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
                chip.isFocusableInTouchMode = true
            }
            host.addView(chip)
            modeChips[m] = chip
        }
        modesRow?.isVisible = true
    }

    private fun bindSearch() {
        val box = searchBox ?: return
        box.hint = fragment.getString(R.string.live_command_search_hint)
        box.setText(query)
        searchClear?.isVisible = query.isNotBlank()
        searchClear?.setOnClickListener {
            box.setText("")
            query = ""
            publish()
        }
        box.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                query = box.text?.toString().orEmpty()
                publish()
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
                searchClear?.isVisible = query.isNotBlank()
                publish()
            }
        })
        // TV: search is optional; keep focusable but not first.
        box.isFocusable = true
        box.isFocusableInTouchMode = !isTv || true
    }

    private fun selectMode(next: LiveCommandMode) {
        if (mode == next && query.isEmpty()) {
            applySelection()
            return
        }
        mode = next
        UserPreferences.liveCommandMode = next.name
        if (next != LiveCommandMode.REMINDERS) {
            // Keep query when switching shelves; clear only when entering reminders for clarity.
        }
        onModeChanged(next)
        applySelection()
        publish()
    }

    private fun applySelection() {
        for ((m, chip) in modeChips) {
            val selected = m == mode
            chip.isSelected = selected
            chip.setBackgroundResource(
                if (selected) R.drawable.bg_download_filter_chip_selected
                else R.drawable.bg_download_filter_chip,
            )
            chip.setTextColor(
                if (selected) {
                    fragment.resources.getColor(R.color.black, fragment.requireContext().theme)
                } else {
                    fragment.resources.getColor(R.color.white, fragment.requireContext().theme)
                },
            )
        }
    }

    private fun publish() {
        val favorites = hubCategories
            .firstOrNull { it.identityKey == LiveHubCatalog.SECTION_FAVORITES }
            ?.list
            ?.filterIsInstance<TvShow>()
            .orEmpty()
        val now = System.currentTimeMillis()
        val visible = LiveCommandPolicy.resolveVisible(
            categories = hubCategories,
            mode = mode,
            query = query,
            searchLabel = fragment.getString(R.string.live_command_search_results),
            remindersLabel = fragment.getString(R.string.live_command_mode_reminders),
            favorites = favorites,
            nowMs = now,
            snoozedUntil = { LiveReminderStore.snoozedUntil(it) },
        )
        val reminderCount = LiveCommandPolicy.buildReminderRows(
            favorites = favorites,
            nowMs = now,
            snoozedUntil = { LiveReminderStore.snoozedUntil(it) },
        ).size
        bindStatus(
            LiveCommandPolicy.statusLine(
                mode = mode,
                query = query,
                visibleCount = visible.sumOf { it.list.size },
                reminderCount = reminderCount,
                pinCount = pinCount,
            ),
        )
        onVisibleChanged(visible)
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
            is LiveCommandPolicy.StatusKind.Favorites ->
                fragment.getString(R.string.live_command_status_favorites, kind.pinCount)
            is LiveCommandPolicy.StatusKind.Mode ->
                fragment.getString(R.string.live_command_status_mode, modeLabel(kind.mode), kind.shelves)
        }
        tv.isVisible = true
    }

    private fun refreshActions() {
        val host = actionsHost ?: return
        host.removeAllViews()
        val snapshot = LiveCommandPolicy.CommandSnapshot(
            pinCount = pinCount,
            reminderCount = 0,
            remindersEnabled = UserPreferences.notifyLiveFavoriteStarting,
            ambientEnabled = UserPreferences.tvAmbientModeEnabled,
            ambientAvailable = isTv &&
                UserPreferences.cinemaRoomEnabled &&
                run {
                    val ctx = fragment.requireContext()
                    AmbientModePolicy.highEndOk(
                        hardReduceEffects = DeviceCapabilities.shouldHardReduceHomeEffects(ctx),
                        touchExploration = FeaturedAdvancePolicy.isTouchExplorationEnabled(ctx),
                        reduceMotion = DeviceCapabilities.isAnimatorDurationScaleOff(ctx),
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
            chip.setTextColor(fragment.resources.getColor(R.color.white, fragment.requireContext().theme))
            if (isTv) {
                chip.isFocusable = true
                chip.isFocusableInTouchMode = true
            }
            chip.setOnClickListener {
                ExpMotion.hapticTap(it)
                handleAction(action, snapshot)
            }
            host.addView(chip)
        }
        host.isVisible = host.childCount > 0
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
                if (!UserPreferences.notifyLiveFavoriteStarting) {
                    UserPreferences.notifyLiveFavoriteStarting = true
                    Toast.makeText(
                        ctx,
                        R.string.live_command_toast_reminders_on,
                        Toast.LENGTH_SHORT,
                    ).show()
                    UserDataNotifier.notifyChanged()
                }
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
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.live_command_toast_pip, snapshot.pipCandidateCount),
                    Toast.LENGTH_SHORT,
                ).show()
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
        fun refreshPinStrip(activity: FragmentActivity?) {
            // Pin strip lives on Main activities — nudge via UserDataNotifier.
            UserDataNotifier.notifyChanged()
            activity ?: return
        }
    }
}
