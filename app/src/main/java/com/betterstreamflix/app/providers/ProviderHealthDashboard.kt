package com.betterstreamflix.app.providers

import com.betterstreamflix.app.ui.StickEnergyMode
import com.betterstreamflix.app.utils.DomainRedirectChecker
import com.betterstreamflix.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Settings-facing snapshot of which catalog sources look dead or slow today,
 * built from [ProviderHealth] / [ProviderSmoke] and optional live
 * [DomainRedirectChecker] probes.
 */
object ProviderHealthDashboard {

    enum class Severity {
        DEAD,
        SLOW,
        OK,
    }

    enum class Reason {
        QUARANTINED,
        PARKED,
        CIRCUIT_OPEN,
        FLAKY,
        PROBE_FAILED,
        PROBE_SLOW,
        HEALTHY,
    }

    data class Row(
        val name: String,
        val provider: Provider,
        val severity: Severity,
        val reason: Reason,
        val latencyMs: Long? = null,
        val detail: String? = null,
        val canUpdateDomain: Boolean,
    )

    data class Snapshot(
        val rows: List<Row>,
        val deadCount: Int,
        val slowCount: Int,
        val okCount: Int,
        val probedAtMs: Long,
    ) {
        val issueCount: Int get() = deadCount + slowCount
    }

    data class ProbeOutcome(
        val ok: Boolean,
        val latencyMs: Long,
        val detail: String?,
        val redirected: Boolean = false,
    )

    /** Home probes slower than this are treated as "slow today". */
    const val SLOW_PROBE_MS = 8_000L

    /** Cap concurrent domain checks so Settings stays responsive. */
    private const val MAX_LIVE_PROBES = 12

    fun snapshotFromRuntime(
        providers: Collection<Provider> = Provider.providers.keys,
        probes: Map<String, ProbeOutcome> = emptyMap(),
        nowMs: Long = System.currentTimeMillis(),
    ): Snapshot {
        val rows = providers.map { provider ->
            buildRow(provider, probes[provider.name])
        }.sortedWith(
            compareBy<Row> { severityRank(it.severity) }
                .thenBy { it.name.lowercase() },
        )
        return Snapshot(
            rows = rows,
            deadCount = rows.count { it.severity == Severity.DEAD },
            slowCount = rows.count { it.severity == Severity.SLOW },
            okCount = rows.count { it.severity == Severity.OK },
            probedAtMs = nowMs,
        )
    }

    /**
     * Live-check configurable domains that are already unhealthy or on the
     * domain-update allowlist — enough to answer "what's dead/slow today"
     * without hammering every registry entry.
     */
    suspend fun probePriorityDomains(
        providers: Collection<Provider> = Provider.providers.keys,
    ): Map<String, ProbeOutcome> = withContext(Dispatchers.IO) {
        val targets = providers
            .filter { it is ProviderConfigUrl }
            .filter { provider ->
                ProviderHealth.supportsDomainUpdate(provider) ||
                    ProviderHealth.isQuarantined(provider) ||
                    ProviderSmoke.peekHomeCircuitOpen(provider.name) ||
                    ProviderSmoke.failureCount(provider.name) >= 2
            }
            .distinctBy { it.name }
            .take(MAX_LIVE_PROBES)
        if (targets.isEmpty()) return@withContext emptyMap()
        // SE-009: serialize probes under Stick Energy.
        val gate = Semaphore(StickEnergyMode.providerParallelism(default = 3))
        coroutineScope {
            targets.map { provider ->
                async {
                    gate.withPermit {
                        provider.name to probeOne(provider, provider as ProviderConfigUrl)
                    }
                }
            }.awaitAll().toMap()
        }
    }

    suspend fun refresh(
        providers: Collection<Provider> = Provider.providers.keys,
        liveProbe: Boolean = true,
    ): Snapshot {
        val probes = if (liveProbe) probePriorityDomains(providers) else emptyMap()
        return snapshotFromRuntime(providers, probes)
    }

    private fun buildRow(provider: Provider, probe: ProbeOutcome?): Row {
        val canUpdate = ProviderHealth.supportsDomainUpdate(provider)
        when {
            ProviderHealth.parkedDefaultNames.contains(provider.name) ->
                return Row(
                    name = provider.name,
                    provider = provider,
                    severity = Severity.DEAD,
                    reason = Reason.PARKED,
                    detail = probe?.detail,
                    canUpdateDomain = canUpdate,
                )
            ProviderHealth.isQuarantined(provider) ->
                return Row(
                    name = provider.name,
                    provider = provider,
                    severity = Severity.DEAD,
                    reason = Reason.QUARANTINED,
                    latencyMs = probe?.latencyMs,
                    detail = probe?.detail,
                    canUpdateDomain = canUpdate,
                )
            probe != null && !probe.ok ->
                return Row(
                    name = provider.name,
                    provider = provider,
                    severity = Severity.DEAD,
                    reason = Reason.PROBE_FAILED,
                    latencyMs = probe.latencyMs,
                    detail = probe.detail,
                    canUpdateDomain = canUpdate,
                )
            ProviderSmoke.peekHomeCircuitOpen(provider.name) ->
                return Row(
                    name = provider.name,
                    provider = provider,
                    severity = Severity.SLOW,
                    reason = Reason.CIRCUIT_OPEN,
                    latencyMs = probe?.latencyMs,
                    detail = probe?.detail,
                    canUpdateDomain = canUpdate,
                )
            probe != null && probe.latencyMs >= SLOW_PROBE_MS ->
                return Row(
                    name = provider.name,
                    provider = provider,
                    severity = Severity.SLOW,
                    reason = Reason.PROBE_SLOW,
                    latencyMs = probe.latencyMs,
                    detail = probe.detail,
                    canUpdateDomain = canUpdate,
                )
            ProviderSmoke.failureCount(provider.name) >= 2 ->
                return Row(
                    name = provider.name,
                    provider = provider,
                    severity = Severity.SLOW,
                    reason = Reason.FLAKY,
                    latencyMs = probe?.latencyMs,
                    detail = probe?.detail,
                    canUpdateDomain = canUpdate,
                )
            else ->
                return Row(
                    name = provider.name,
                    provider = provider,
                    severity = Severity.OK,
                    reason = Reason.HEALTHY,
                    latencyMs = probe?.latencyMs,
                    detail = probe?.detail,
                    canUpdateDomain = canUpdate,
                )
        }
    }

    private fun severityRank(severity: Severity): Int = when (severity) {
        Severity.DEAD -> 0
        Severity.SLOW -> 1
        Severity.OK -> 2
    }

    private suspend fun probeOne(provider: Provider, config: ProviderConfigUrl): ProbeOutcome {
        val start = UserPreferences.getProviderCache(
            provider,
            UserPreferences.PROVIDER_URL,
        ).ifBlank { config.defaultBaseUrl }
        val began = System.nanoTime()
        return runCatching {
            val result = DomainRedirectChecker.check(start)
            val latencyMs = (System.nanoTime() - began) / 1_000_000L
            ProbeOutcome(
                ok = true,
                latencyMs = latencyMs,
                detail = if (result.changed) result.finalUrl else null,
                redirected = result.changed,
            )
        }.getOrElse { error ->
            val latencyMs = (System.nanoTime() - began) / 1_000_000L
            ProbeOutcome(
                ok = false,
                latencyMs = latencyMs,
                detail = error.message ?: "probe failed",
            )
        }
    }
}
