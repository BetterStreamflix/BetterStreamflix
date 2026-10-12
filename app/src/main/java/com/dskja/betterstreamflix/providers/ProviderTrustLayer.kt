package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Unified Provider Trust Layer — aggregates Health, Passport, A/B learning,
 * manual pins, and outage signals into one product-facing snapshot.
 */
object ProviderTrustLayer {

    enum class OutageLevel {
        NONE,
        WATCH,
        SEVERE,
    }

    data class LearnedServer(
        val name: String,
        val successes: Int,
        val failures: Int,
        val pinned: Boolean,
    )

    data class Snapshot(
        val health: ProviderHealthDashboard.Snapshot,
        val passport: ProviderPassport.Snapshot,
        val currentProviderName: String?,
        val learned: List<LearnedServer>,
        val pins: List<String>,
        val outageLevel: OutageLevel,
        val outageProviderNames: List<String>,
        val probedAtMs: Long,
    ) {
        val deadCount: Int get() = health.deadCount
        val slowCount: Int get() = health.slowCount
        val okCount: Int get() = health.okCount
        val passportAttention: Int get() = passport.attentionCount
        val hasIssues: Boolean
            get() = deadCount > 0 || slowCount > 0 || passportAttention > 0 ||
                outageLevel != OutageLevel.NONE
    }

    fun snapshot(
        health: ProviderHealthDashboard.Snapshot = ProviderHealthDashboard.snapshotFromRuntime(),
        passport: ProviderPassport.Snapshot = ProviderPassport.snapshotFromRuntime(),
        currentProvider: Provider? = UserPreferences.currentProvider,
        nowMs: Long = System.currentTimeMillis(),
    ): Snapshot {
        val providerName = currentProvider?.name
        val learned = if (providerName != null && ProviderServerAb.shouldTrack(currentProvider)) {
            ProviderServerAb.topServers(providerName, limit = 5, nowMs = nowMs).map { (name, stats) ->
                LearnedServer(
                    name = titleCaseServerName(name),
                    successes = stats.successes,
                    failures = stats.failures,
                    pinned = ProviderServerPriority.isPinnedName(providerName, name),
                )
            }
        } else {
            emptyList()
        }
        val pins = providerName?.let { ProviderServerPriority.pinsFor(it) }.orEmpty()
        // Quarantined / parked registry entries are intentional product state — they must
        // not inflate the temporary outage banner (probe failures + live circuits only).
        val outageNames = health.rows
            .filter { it.severity == ProviderHealthDashboard.Severity.DEAD }
            .filter {
                it.reason != ProviderHealthDashboard.Reason.QUARANTINED &&
                    it.reason != ProviderHealthDashboard.Reason.PARKED
            }
            .map { it.name }
        val currentDead = providerName != null &&
            outageNames.any { it.equals(providerName, ignoreCase = true) }
        val outageLevel = when {
            currentDead -> OutageLevel.SEVERE
            outageNames.size >= 3 -> OutageLevel.SEVERE
            outageNames.isNotEmpty() -> OutageLevel.WATCH
            else -> OutageLevel.NONE
        }
        return Snapshot(
            health = health,
            passport = passport,
            currentProviderName = providerName,
            learned = learned,
            pins = pins,
            outageLevel = outageLevel,
            outageProviderNames = outageNames,
            probedAtMs = maxOf(health.probedAtMs, passport.probedAtMs, nowMs),
        )
    }

    suspend fun refresh(liveProbe: Boolean = true): Snapshot {
        val health = ProviderHealthDashboard.refresh(liveProbe = liveProbe)
        val passport = ProviderPassport.refresh()
        return snapshot(health = health, passport = passport)
    }

    /** Order servers: preferred → manual pins → A/B learned → remainder. */
    fun prioritizeServers(
        provider: Provider?,
        servers: List<Video.Server>,
        preferredServerName: String?,
    ): List<Video.Server> {
        if (servers.size <= 1 || provider == null) return servers
        if (!ProviderServerAb.shouldTrack(provider)) {
            return ProviderServerPriority.applyPins(
                providerName = provider.name,
                servers = servers,
                preferredServerName = preferredServerName,
            )
        }
        val abOrdered = ProviderServerAb.prioritize(
            providerName = provider.name,
            servers = servers,
            preferredServerName = null,
        )
        return ProviderServerPriority.applyPins(
            providerName = provider.name,
            servers = abOrdered,
            preferredServerName = preferredServerName,
        )
    }

    /** Match Health dashboard title-casing for learned A/B keys (e.g. voe → Voe). */
    internal fun titleCaseServerName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return trimmed
            .split(Regex("[\\s_\\-]+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                }
            }
    }
}
