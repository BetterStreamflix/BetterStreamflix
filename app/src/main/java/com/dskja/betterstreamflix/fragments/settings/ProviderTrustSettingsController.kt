package com.dskja.betterstreamflix.fragments.settings

import android.content.Context
import android.text.format.DateUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.providers.ProviderServerPriority
import com.dskja.betterstreamflix.providers.ProviderTrustLayer
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified Provider Trust Layer Settings surface — Health + Passport + A/B +
 * manual pins + outage in one place.
 */
object ProviderTrustSettingsController {

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
        openScreen: ((String, String) -> Unit)? = null,
    ) {
        val context = fragment.requireContext()
        val summaryPref = findPreference("PROVIDER_TRUST_SUMMARY")
        val refreshPref = findPreference("PROVIDER_TRUST_REFRESH")
        val outageCategory = findPreference("pc_provider_trust_outage") as? PreferenceCategory
        val learnedCategory = findPreference("pc_provider_trust_learned") as? PreferenceCategory
        val pinsCategory = findPreference("pc_provider_trust_pins") as? PreferenceCategory
        val openHealth = findPreference("PROVIDER_TRUST_OPEN_HEALTH")
        val openPassport = findPreference("PROVIDER_TRUST_OPEN_PASSPORT")

        fun paint(snapshot: ProviderTrustLayer.Snapshot) {
            summaryPref?.summary = context.getString(
                R.string.settings_provider_trust_summary_counts,
                snapshot.deadCount,
                snapshot.slowCount,
                snapshot.passportAttention,
                snapshot.learned.size,
            )
            val whenLabel = DateUtils.getRelativeTimeSpanString(
                snapshot.probedAtMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            refreshPref?.summary = context.getString(
                R.string.settings_provider_trust_refreshed_at,
                whenLabel,
            )
            paintOutage(context, outageCategory, snapshot)
            paintLearned(context, fragment, scope, learnedCategory, snapshot) { paint(it) }
            paintPins(context, fragment, scope, pinsCategory, snapshot) { paint(it) }
        }

        fun runRefresh() {
            refreshPref?.isEnabled = false
            refreshPref?.summary = context.getString(R.string.settings_provider_trust_checking)
            scope.launch {
                val snapshot = withContext(Dispatchers.IO) {
                    ProviderTrustLayer.refresh(liveProbe = true)
                }
                if (!fragment.isAdded) return@launch
                refreshPref?.isEnabled = true
                paint(snapshot)
            }
        }

        paint(ProviderTrustLayer.snapshot())
        runRefresh()

        refreshPref?.setOnPreferenceClickListener {
            fragment.view?.let(ExpMotion::hapticTap)
            runRefresh()
            true
        }
        openHealth?.setOnPreferenceClickListener {
            openScreen?.invoke(
                "screen_provider_health",
                context.getString(R.string.settings_provider_health_title),
            )
            true
        }
        openPassport?.setOnPreferenceClickListener {
            openScreen?.invoke(
                "screen_provider_passport",
                context.getString(R.string.settings_provider_passport_title),
            )
            true
        }
    }

    private fun paintOutage(
        context: Context,
        category: PreferenceCategory?,
        snapshot: ProviderTrustLayer.Snapshot,
    ) {
        if (category == null) return
        category.removeAll()
        if (snapshot.outageProviderNames.isEmpty()) {
            category.isVisible = false
            return
        }
        category.isVisible = true
        category.title = context.getString(R.string.settings_provider_trust_outage_title)
        snapshot.outageProviderNames.take(8).forEach { name ->
            category.addPreference(
                Preference(context).apply {
                    key = "provider_trust_outage_$name"
                    title = name
                    summary = context.getString(R.string.settings_provider_trust_outage_row)
                    isSelectable = false
                    isIconSpaceReserved = false
                },
            )
        }
    }

    private fun paintLearned(
        context: Context,
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        category: PreferenceCategory?,
        snapshot: ProviderTrustLayer.Snapshot,
        onChanged: (ProviderTrustLayer.Snapshot) -> Unit,
    ) {
        if (category == null) return
        category.removeAll()
        val provider = snapshot.currentProviderName
        if (provider.isNullOrBlank() || snapshot.learned.isEmpty()) {
            category.addPreference(
                Preference(context).apply {
                    key = "provider_trust_learned_empty"
                    title = context.getString(R.string.settings_provider_trust_learned_empty)
                    isSelectable = false
                    isIconSpaceReserved = false
                },
            )
            return
        }
        snapshot.learned.forEach { row ->
            category.addPreference(
                Preference(context).apply {
                    key = "provider_trust_learned_${row.name}"
                    title = row.name
                    val rate = if (row.successes + row.failures > 0) {
                        (row.successes * 100) / (row.successes + row.failures)
                    } else {
                        0
                    }
                    summary = buildString {
                        append(
                            context.getString(
                                R.string.settings_provider_trust_learned_row,
                                rate,
                                row.successes,
                                row.failures,
                            ),
                        )
                        if (row.pinned) {
                            append(" · ")
                            append(context.getString(R.string.server_why_badge_pinned))
                        }
                    }
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        fragment.view?.let(ExpMotion::hapticTap)
                        val pinned = ProviderServerPriority.toggle(provider, row.name)
                        ExpDialogChrome.notify(
                            context,
                            if (pinned) {
                                context.getString(R.string.settings_provider_trust_pin_on, row.name)
                            } else {
                                context.getString(R.string.settings_provider_trust_pin_off, row.name)
                            },
                        )
                        onChanged(ProviderTrustLayer.snapshot())
                        true
                    }
                },
            )
        }
    }

    private fun paintPins(
        context: Context,
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        category: PreferenceCategory?,
        snapshot: ProviderTrustLayer.Snapshot,
        onChanged: (ProviderTrustLayer.Snapshot) -> Unit,
    ) {
        if (category == null) return
        category.removeAll()
        val provider = snapshot.currentProviderName
        if (provider.isNullOrBlank() || snapshot.pins.isEmpty()) {
            category.isVisible = snapshot.currentProviderName != null
            if (category.isVisible) {
                category.addPreference(
                    Preference(context).apply {
                        key = "provider_trust_pins_empty"
                        title = context.getString(R.string.settings_provider_trust_pins_empty)
                        summary = context.getString(R.string.settings_provider_trust_pins_hint)
                        isSelectable = false
                        isIconSpaceReserved = false
                    },
                )
            }
            return
        }
        category.isVisible = true
        snapshot.pins.forEach { name ->
            category.addPreference(
                Preference(context).apply {
                    key = "provider_trust_pin_$name"
                    title = name
                    summary = context.getString(R.string.settings_provider_trust_pin_remove)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        fragment.view?.let(ExpMotion::hapticTap)
                        ProviderServerPriority.unpin(provider, name)
                        ExpDialogChrome.notify(
                            context,
                            context.getString(R.string.settings_provider_trust_pin_off, name),
                        )
                        onChanged(ProviderTrustLayer.snapshot())
                        true
                    }
                },
            )
        }
    }
}
