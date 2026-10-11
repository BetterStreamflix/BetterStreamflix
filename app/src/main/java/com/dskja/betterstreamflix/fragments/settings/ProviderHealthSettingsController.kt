package com.dskja.betterstreamflix.fragments.settings

import android.content.Context
import android.text.format.DateUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.providers.ProviderDomainUpdater
import com.dskja.betterstreamflix.providers.ProviderHealthDashboard
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ProviderHealthSettingsController {

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
    ) {
        val context = fragment.requireContext()
        val summaryPref = findPreference("PROVIDER_HEALTH_SUMMARY")
        val refreshPref = findPreference("PROVIDER_HEALTH_REFRESH")
        val issuesCategory = findPreference("pc_provider_health_issues") as? PreferenceCategory
        val healthyCategory = findPreference("pc_provider_health_ok") as? PreferenceCategory

        fun paint(snapshot: ProviderHealthDashboard.Snapshot) {
            summaryPref?.summary = context.getString(
                R.string.settings_provider_health_summary_counts,
                snapshot.deadCount,
                snapshot.slowCount,
                snapshot.okCount,
            )
            val whenLabel = DateUtils.getRelativeTimeSpanString(
                snapshot.probedAtMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            refreshPref?.summary = context.getString(
                R.string.settings_provider_health_refreshed_at,
                whenLabel,
            )
            val hostView = fragment.view
            paintCategory(
                context = context,
                hostView = hostView,
                scope = scope,
                category = issuesCategory,
                rows = snapshot.rows.filter {
                    it.severity != ProviderHealthDashboard.Severity.OK
                },
                emptyTitle = context.getString(R.string.settings_provider_health_issues_empty),
                onUpdated = { paint(it) },
            )
            paintCategory(
                context = context,
                hostView = hostView,
                scope = scope,
                category = healthyCategory,
                rows = snapshot.rows.filter {
                    it.severity == ProviderHealthDashboard.Severity.OK
                }.take(8),
                emptyTitle = null,
                onUpdated = { paint(it) },
            )
            healthyCategory?.isVisible = snapshot.okCount > 0
        }

        fun runRefresh(liveProbe: Boolean) {
            refreshPref?.isEnabled = false
            refreshPref?.summary = context.getString(R.string.settings_provider_health_checking)
            scope.launch {
                val snapshot = withContext(Dispatchers.IO) {
                    ProviderHealthDashboard.refresh(liveProbe = liveProbe)
                }
                if (!fragment.isAdded) return@launch
                refreshPref?.isEnabled = true
                paint(snapshot)
            }
        }

        // Instant runtime view, then a live domain probe.
        paint(ProviderHealthDashboard.snapshotFromRuntime())
        runRefresh(liveProbe = true)

        refreshPref?.setOnPreferenceClickListener {
            fragment.view?.let(ExpMotion::hapticTap)
            runRefresh(liveProbe = true)
            true
        }
    }

    private fun paintCategory(
        context: Context,
        hostView: android.view.View?,
        scope: LifecycleCoroutineScope,
        category: PreferenceCategory?,
        rows: List<ProviderHealthDashboard.Row>,
        emptyTitle: String?,
        onUpdated: (ProviderHealthDashboard.Snapshot) -> Unit,
    ) {
        if (category == null) return
        category.removeAll()
        if (rows.isEmpty()) {
            if (emptyTitle != null) {
                category.addPreference(
                    Preference(context).apply {
                        key = "${category.key}_empty"
                        title = emptyTitle
                        isSelectable = false
                        isIconSpaceReserved = false
                    },
                )
            }
            return
        }
        rows.forEach { row ->
            category.addPreference(
                Preference(context).apply {
                    key = "provider_health_row_${row.name}"
                    title = row.name
                    summary = rowSummary(context, row)
                    isIconSpaceReserved = false
                    isSelectable = row.canUpdateDomain
                    if (row.canUpdateDomain) {
                        setOnPreferenceClickListener {
                            hostView?.let(ExpMotion::hapticTap)
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    ProviderDomainUpdater.update(row.provider)
                                }
                                val message = when (outcome) {
                                    is ProviderDomainUpdater.Outcome.Updated ->
                                        context.getString(
                                            R.string.provider_update_domain_updated,
                                            outcome.newUrl,
                                        )
                                    ProviderDomainUpdater.Outcome.Unchanged ->
                                        context.getString(R.string.provider_update_domain_unchanged)
                                    is ProviderDomainUpdater.Outcome.Failed ->
                                        context.getString(
                                            R.string.provider_update_domain_failed,
                                            outcome.message,
                                        )
                                }
                                ExpDialogChrome.notify(
                                    context,
                                    message,
                                    R.string.settings_provider_health_title,
                                )
                                if (outcome is ProviderDomainUpdater.Outcome.Updated) {
                                    val snapshot = withContext(Dispatchers.IO) {
                                        ProviderHealthDashboard.refresh(liveProbe = true)
                                    }
                                    onUpdated(snapshot)
                                }
                            }
                            true
                        }
                    }
                },
            )
        }
    }

    private fun rowSummary(context: Context, row: ProviderHealthDashboard.Row): String {
        val status = when (row.reason) {
            ProviderHealthDashboard.Reason.QUARANTINED ->
                context.getString(R.string.provider_health_quarantined)
            ProviderHealthDashboard.Reason.PARKED ->
                context.getString(R.string.settings_provider_health_status_dead)
            ProviderHealthDashboard.Reason.CIRCUIT_OPEN ->
                context.getString(R.string.provider_health_unstable)
            ProviderHealthDashboard.Reason.FLAKY ->
                context.getString(R.string.provider_health_flaky)
            ProviderHealthDashboard.Reason.PROBE_FAILED ->
                context.getString(R.string.settings_provider_health_status_dead)
            ProviderHealthDashboard.Reason.PROBE_SLOW ->
                context.getString(R.string.settings_provider_health_status_slow)
            ProviderHealthDashboard.Reason.HEALTHY ->
                context.getString(R.string.settings_provider_health_status_ok)
        }
        val latency = row.latencyMs?.let { ms ->
            context.getString(R.string.settings_provider_health_latency_ms, ms)
        }
        val cta = if (row.canUpdateDomain) {
            context.getString(R.string.provider_update_domain)
        } else {
            null
        }
        val detail = row.detail?.takeIf { it.isNotBlank() }
        val learned = com.dskja.betterstreamflix.providers.ProviderHealth
            .learnedStableServers(row.name, limit = 3)
            .takeIf { it.isNotEmpty() }
            ?.let { names ->
                context.getString(
                    R.string.settings_provider_health_learned_servers,
                    names.joinToString(", "),
                )
            }
        return listOfNotNull(status, latency, detail, learned, cta).joinToString(" · ")
    }
}
