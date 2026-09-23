package com.dskja.betterstreamflix.logo

import java.util.concurrent.atomic.AtomicLong

/** Lightweight in-process counters for logo pipeline health (point 69). */
object TmdbLogoTelemetry {
    private val cacheHits = AtomicLong()
    private val cacheMisses = AtomicLong()
    private val knownMisses = AtomicLong()
    private val networkSuccess = AtomicLong()
    private val networkFailure = AtomicLong()
    private val upgrades = AtomicLong()
    private val glideFails = AtomicLong()
    private val failBlacklists = AtomicLong()
    private val resolves = AtomicLong()

    fun recordCacheHit() { cacheHits.incrementAndGet() }
    fun recordCacheMiss() { cacheMisses.incrementAndGet() }
    fun recordKnownMiss() { knownMisses.incrementAndGet() }
    fun recordNetworkSuccess() { networkSuccess.incrementAndGet() }
    fun recordNetworkFailure() { networkFailure.incrementAndGet() }
    fun recordUpgrade() { upgrades.incrementAndGet() }
    fun recordGlideFail() { glideFails.incrementAndGet() }
    fun recordFailBlacklist() { failBlacklists.incrementAndGet() }
    fun recordResolve() { resolves.incrementAndGet() }

    data class Snapshot(
        val cacheHits: Long,
        val cacheMisses: Long,
        val knownMisses: Long,
        val networkSuccess: Long,
        val networkFailure: Long,
        val upgrades: Long,
        val glideFails: Long,
        val failBlacklists: Long,
        val resolves: Long,
    )

    fun snapshot(): Snapshot = Snapshot(
        cacheHits = cacheHits.get(),
        cacheMisses = cacheMisses.get(),
        knownMisses = knownMisses.get(),
        networkSuccess = networkSuccess.get(),
        networkFailure = networkFailure.get(),
        upgrades = upgrades.get(),
        glideFails = glideFails.get(),
        failBlacklists = failBlacklists.get(),
        resolves = resolves.get(),
    )

    fun reset() {
        listOf(
            cacheHits, cacheMisses, knownMisses, networkSuccess, networkFailure,
            upgrades, glideFails, failBlacklists, resolves,
        ).forEach { it.set(0) }
    }

    fun debugSummary(): String {
        val s = snapshot()
        return "logos hits=${s.cacheHits} miss=${s.cacheMisses} knownMiss=${s.knownMisses} " +
            "netOk=${s.networkSuccess} netFail=${s.networkFailure} up=${s.upgrades} " +
            "glideFail=${s.glideFails} bl=${s.failBlacklists} resolve=${s.resolves}"
    }
}
