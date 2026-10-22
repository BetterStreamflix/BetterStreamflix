package com.betterstreamflix.app.logo

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Lightweight in-process counters for logo pipeline health (point 69). */
object TmdbLogoTelemetry {
    private const val PREFS = "tmdb_logo_telemetry"
    private const val FLUSH_EVERY = 32
    private const val KEEP_DAYS = 14

    @SuppressLint("StaticFieldLeak")
    private var prefs: SharedPreferences? = null
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dirty = AtomicLong()

    /** Call once from Application.onCreate — enables the persisted daily rollup. */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pruneOldDays()
    }
    private val cacheHits = AtomicLong()
    private val cacheMisses = AtomicLong()
    private val knownMisses = AtomicLong()
    private val networkSuccess = AtomicLong()
    private val networkFailure = AtomicLong()
    private val upgrades = AtomicLong()
    private val glideFails = AtomicLong()
    private val failBlacklists = AtomicLong()
    private val resolves = AtomicLong()
    private val rateLimited = AtomicLong()
    private val glideFailNotFound = AtomicLong()
    private val glideFailCorrupt = AtomicLong()
    private val glideFailOther = AtomicLong()

    fun recordCacheHit() { cacheHits.incrementAndGet(); noteEvent() }
    fun recordCacheMiss() { cacheMisses.incrementAndGet(); noteEvent() }
    fun recordKnownMiss() { knownMisses.incrementAndGet(); noteEvent() }
    fun recordNetworkSuccess() { networkSuccess.incrementAndGet(); noteEvent() }
    fun recordNetworkFailure() { networkFailure.incrementAndGet(); noteEvent() }
    fun recordUpgrade() { upgrades.incrementAndGet(); noteEvent() }
    fun recordGlideFail() { glideFails.incrementAndGet(); noteEvent() }
    fun recordFailBlacklist() { failBlacklists.incrementAndGet(); noteEvent() }
    fun recordResolve() { resolves.incrementAndGet(); noteEvent() }
    fun recordResolve(provider: String?) {
        resolves.incrementAndGet()
        if (!provider.isNullOrBlank()) {
            perProviderResolves.getOrPut(provider) { AtomicLong() }.incrementAndGet()
        }
        noteEvent()
    }
    fun recordRateLimited() { rateLimited.incrementAndGet(); noteEvent() }
    fun recordGlideFailNotFound() { glideFailNotFound.incrementAndGet(); noteEvent() }
    fun recordGlideFailCorrupt() { glideFailCorrupt.incrementAndGet(); noteEvent() }
    fun recordGlideFailOther() { glideFailOther.incrementAndGet(); noteEvent() }

    /** Resolves broken down by provider name (active provider at resolve time). */
    val perProviderResolves = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

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
        val rateLimited: Long = 0,
        val glideFailNotFound: Long = 0,
        val glideFailCorrupt: Long = 0,
        val glideFailOther: Long = 0,
        val perProvider: Map<String, Long> = emptyMap(),
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
        rateLimited = rateLimited.get(),
        glideFailNotFound = glideFailNotFound.get(),
        glideFailCorrupt = glideFailCorrupt.get(),
        glideFailOther = glideFailOther.get(),
        perProvider = perProviderResolves.mapValues { it.value.get() },
    )

    fun reset() {
        listOf(
            cacheHits, cacheMisses, knownMisses, networkSuccess, networkFailure,
            upgrades, glideFails, failBlacklists, resolves, rateLimited,
            glideFailNotFound, glideFailCorrupt, glideFailOther,
        ).forEach { it.set(0) }
        perProviderResolves.clear()
        prefs?.edit()?.clear()?.apply()
    }

    // ------------------------------------------------------------------
    // Persisted daily rollup — one compact row per day ("hits,misses,…"),
    // flushed every FLUSH_EVERY events so scroll storms don't write-storm.
    // ------------------------------------------------------------------

    private fun todayKey(): String = dayFormat.format(Date())

    private fun noteEvent() {
        if (dirty.incrementAndGet() % FLUSH_EVERY == 0L) flush()
    }

    /** Persist the current snapshot into today's rollup row. Cheap to call. */
    fun flush() {
        val p = prefs ?: return
        val s = snapshot()
        p.edit().putString(todayKey(), listOf(
            s.cacheHits, s.cacheMisses, s.knownMisses, s.networkSuccess,
            s.networkFailure, s.upgrades, s.glideFails, s.failBlacklists,
            s.resolves, s.rateLimited,
        ).joinToString(",")).apply()
    }

    private fun pruneOldDays() {
        val p = prefs ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60 * 60 * 1000
        val stale = p.all.keys.filter { key ->
            runCatching { dayFormat.parse(key)?.time ?: 0L }
                .getOrDefault(0L) < cutoff
        }
        if (stale.isNotEmpty()) {
            val e = p.edit()
            stale.forEach { e.remove(it) }
            e.apply()
        }
    }

    /** Lifetime rollup across the retained window (last [KEEP_DAYS] days). */
    fun totals(): Map<String, Long> {
        val p = prefs ?: return emptyMap()
        val fields = listOf(
            "cacheHits", "cacheMisses", "knownMisses", "networkSuccess",
            "networkFailure", "upgrades", "glideFails", "failBlacklists",
            "resolves", "rateLimited",
        )
        val sums = LongArray(fields.size)
        p.all.forEach { (key, v) ->
            if (key.length != 10 || v !is String) return@forEach
            v.split(",").forEachIndexed { i, part ->
                if (i < sums.size) sums[i] += part.toLongOrNull() ?: 0L
            }
        }
        return fields.zip(sums.toList()).toMap()
    }

    /** One-line human summary for the settings row. */
    fun settingsSummary(): String {
        val s = snapshot()
        if (s.resolves == 0L && s.cacheHits == 0L) return ""
        val hitRate = if (s.cacheHits + s.cacheMisses > 0)
            (100 * s.cacheHits / (s.cacheHits + s.cacheMisses)) else 0
        val t = totals()
        val lifetime = t["resolves"] ?: 0L
        return "session: ${s.resolves} resolves, ${s.cacheHits} hits " +
            "($hitRate%), ${s.knownMisses} misses, ${s.glideFails} load fails" +
            if (lifetime > s.resolves) " — ${KEEP_DAYS}d total: $lifetime resolves" else ""
    }

    fun debugSummary(): String {
        val s = snapshot()
        return "logos hits=${s.cacheHits} miss=${s.cacheMisses} knownMiss=${s.knownMisses} " +
            "netOk=${s.networkSuccess} netFail=${s.networkFailure} up=${s.upgrades} " +
            "glideFail=${s.glideFails} bl=${s.failBlacklists} resolve=${s.resolves}"
    }
}
