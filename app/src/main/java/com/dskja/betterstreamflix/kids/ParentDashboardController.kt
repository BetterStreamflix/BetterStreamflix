package com.dskja.betterstreamflix.kids

import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadStorage
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.profiles.ProfileUnlockGate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parent Dashboard — kids fortress status + Continue/Downloads isolation.
 * Bound to PreferenceScreen `screen_parent_dashboard` (Mobile + TV).
 *
 * Bedtime is **view-only** here (locked/on/off in the card summary). Parents
 * edit bedtime from the kids profile Settings session, not by remote-editing
 * another kid from this dashboard (KB-019).
 */
object ParentDashboardController {

    private const val SCREEN_KEY = "screen_parent_dashboard"
    private const val CAT_KIDS = "pc_parent_dashboard_kids"
    private const val PREF_HOUSEHOLD = "PARENT_DASHBOARD_HOUSEHOLD"
    private const val PREF_EMPTY = "PARENT_DASHBOARD_EMPTY"

    private var refreshJob: Job? = null
    private val refreshGeneration = AtomicInteger(0)

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
        onOpenDashboard: (() -> Unit)? = null,
    ) {
        val eligible = KidsFortress.parentDashboardEligible()
        findPreference(SCREEN_KEY)?.isVisible = eligible
        findPreference("PARENT_DASHBOARD_OPEN")?.apply {
            isVisible = eligible
            setOnPreferenceClickListener {
                onOpenDashboard?.invoke()
                true
            }
        }
        val screen = resolveScreen(fragment, findPreference) ?: return
        refresh(fragment, scope, screen, findPreference)
    }

    fun refreshOnResume(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
    ) {
        findPreference(SCREEN_KEY)?.isVisible = KidsFortress.parentDashboardEligible()
        findPreference("PARENT_DASHBOARD_OPEN")?.isVisible =
            KidsFortress.parentDashboardEligible()
        val screen = resolveScreen(fragment, findPreference) ?: return
        refresh(fragment, fragment.lifecycleScope, screen, findPreference)
    }

    private fun resolveScreen(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
    ): PreferenceScreen? {
        (findPreference(SCREEN_KEY) as? PreferenceScreen)?.let { return it }
        val current = (fragment as? PreferenceFragmentCompat)?.preferenceScreen
        return current?.takeIf { it.key == SCREEN_KEY }
    }

    private fun refresh(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        screen: PreferenceScreen,
        findPreference: (String) -> Preference?,
    ) {
        // Generation token: in-flight loads that finish after a newer refresh
        // are ignored instead of cancelling mid-bind (KF-015).
        val generation = refreshGeneration.incrementAndGet()
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val snapshot = KidsFortressDashboard.load(fragment.requireContext())
            if (generation != refreshGeneration.get()) return@launch
            if (!fragment.isAdded) return@launch
            bindSnapshot(fragment, screen, findPreference, snapshot)
        }
    }

    private fun bindSnapshot(
        fragment: Fragment,
        screen: PreferenceScreen,
        findPreference: (String) -> Preference?,
        snapshot: KidsFortressDashboard.Snapshot,
    ) {
        val ctx = fragment.requireContext()
        val household = findPreference(PREF_HOUSEHOLD) ?: screen.findPreference(PREF_HOUSEHOLD)
        household?.apply {
            isVisible = snapshot.eligible
            summary = if (snapshot.householdPinSet) {
                ctx.getString(R.string.parent_dashboard_household_pin_on)
            } else {
                ctx.getString(R.string.parent_dashboard_household_pin_off)
            }
        }

        val kidsCat = (findPreference(CAT_KIDS) ?: screen.findPreference(CAT_KIDS))
            as? PreferenceCategory
        kidsCat?.removeAll()
        val empty = findPreference(PREF_EMPTY) ?: screen.findPreference(PREF_EMPTY)
        if (!snapshot.eligible) {
            empty?.apply {
                isVisible = true
                summary = ctx.getString(R.string.parent_dashboard_ineligible)
            }
            return
        }
        if (snapshot.kids.isEmpty()) {
            empty?.apply {
                isVisible = true
                title = ctx.getString(R.string.parent_dashboard_empty_title)
                summary = ctx.getString(R.string.parent_dashboard_empty_summary)
            }
            return
        }
        empty?.isVisible = false
        val category = kidsCat ?: return
        snapshot.kids.forEach { card ->
            val pref = Preference(ctx).apply {
                key = "PARENT_DASH_KID_${card.profileId}"
                title = card.displayName
                val base = KidsFortressPolicy.formatCardSummary(
                    maxAge = card.maxAgeRating,
                    hasPin = card.hasPin,
                    bedtimeEnabled = card.bedtimeEnabled,
                    bedtimeLocked = card.bedtimeLocked,
                    continueCount = card.continueCount,
                    downloadCount = card.downloadCount,
                    pinLabel = ctx.getString(R.string.parent_dashboard_pin_on),
                    noPinLabel = ctx.getString(R.string.parent_dashboard_pin_off),
                    bedtimeOnLabel = ctx.getString(
                        R.string.parent_dashboard_bedtime_on,
                        KidsBedtime.formatMinuteOfDay(card.bedtimeMinuteOfDay),
                    ),
                    bedtimeOffLabel = ctx.getString(R.string.parent_dashboard_bedtime_off),
                    bedtimeLockedLabel = ctx.getString(R.string.parent_dashboard_bedtime_locked),
                    continueLabel = ctx.getString(R.string.parent_dashboard_continue_short),
                    downloadsLabel = ctx.getString(R.string.parent_dashboard_downloads_short),
                )
                val bytesLabel = if (card.downloadBytes > 0L) {
                    " · ${DownloadStorage.formatBytes(ctx, card.downloadBytes)}"
                } else {
                    ""
                }
                summary = base + bytesLabel
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    confirmSwitchToKids(fragment, card.profileId, card.displayName)
                    true
                }
            }
            category.addPreference(pref)
        }
    }

    private fun confirmSwitchToKids(
        fragment: Fragment,
        profileId: String,
        displayName: String,
    ) {
        val ctx = fragment.requireContext()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.parent_dashboard_switch_title)
            .setMessage(ctx.getString(R.string.parent_dashboard_switch_message, displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.parent_dashboard_switch_confirm) { _, _ ->
                // Defer unlock so cold-start / kids-without-PIN still challenges (KF-011).
                val switched = ProfileManager.switchTo(ctx, profileId, markUnlocked = false)
                if (!switched) {
                    android.widget.Toast.makeText(
                        ctx,
                        ctx.getString(R.string.profile_kids_exit_requires_pin),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    return@setPositiveButton
                }
                val activity = fragment.activity
                if (activity != null) {
                    ProfileUnlockGate.maybeShow(
                        activity = activity,
                        hostFragmentProvider = { fragment },
                    )
                }
            }
            .show()
    }
}
