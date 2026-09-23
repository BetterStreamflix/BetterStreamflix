package com.dskja.betterstreamflix.logo

import android.content.Context
import android.content.SharedPreferences
import com.dskja.betterstreamflix.utils.ArtworkUrls
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Memory LRU + disk (SharedPreferences) logo URL cache with TTLs, fail blacklist,
 * and single-flight inflight map.
 */
object TmdbLogoCache {

    private const val DISK_PREFIX = "logo|"
    private const val META_VERSION = "meta_version"

    private val memory = object : LinkedHashMap<String, TmdbLogoPicker.CacheEntry>(
        LogoConstants.MEMORY_CACHE_MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, TmdbLogoPicker.CacheEntry>?,
        ): Boolean = size > LogoConstants.MEMORY_CACHE_MAX_ENTRIES
    }

    private val inflight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    /** Identity → blacklistedAtMs (identity strips TMDb size tier). */
    private val failBlacklist = ConcurrentHashMap<String, Long>()

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var missTtlMs: Long = LogoConstants.DEFAULT_MISS_TTL_MS

    @Volatile
    private var hitTtlMs: Long = LogoConstants.DEFAULT_HIT_TTL_MS

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(
            LogoConstants.PREFS_FILE,
            Context.MODE_PRIVATE,
        )
        prefs = p
        val stored = p.getInt(META_VERSION, 0)
        if (stored != LogoConstants.DISK_CACHE_VERSION) {
            p.edit().clear().putInt(META_VERSION, LogoConstants.DISK_CACHE_VERSION).apply()
        }
    }

    fun configureTtls(missTtlMs: Long? = null, hitTtlMs: Long? = null) {
        missTtlMs?.let { this.missTtlMs = it.coerceAtLeast(60_000L) }
        hitTtlMs?.let { this.hitTtlMs = it.coerceAtLeast(60_000L) }
    }

    fun clearAll() {
        synchronized(memory) { memory.clear() }
        inflight.clear()
        failBlacklist.clear()
        prefs?.edit()?.clear()?.putInt(META_VERSION, LogoConstants.DISK_CACHE_VERSION)?.apply()
    }

    /** Logo-only clear — same as clearAll for this cache. */
    fun clearLogoOnly() = clearAll()

    fun blacklistUrl(url: String?, nowMs: Long = System.currentTimeMillis()) {
        val identity = ArtworkUrls.logoFileIdentity(url)
        if (identity.isEmpty()) return
        failBlacklist[identity] = nowMs
        TmdbLogoTelemetry.recordFailBlacklist()
    }

    fun isBlacklisted(url: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val identity = ArtworkUrls.logoFileIdentity(url)
        if (identity.isEmpty()) return false
        val at = failBlacklist[identity] ?: return false
        if (nowMs - at > LogoConstants.FAIL_BLACKLIST_TTL_MS) {
            failBlacklist.remove(identity)
            return false
        }
        return true
    }

    fun isFilePathBlacklisted(filePath: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val path = filePath?.trim().orEmpty()
        if (path.isEmpty()) return false
        // Boundary match on basename so "/1.png" does not poison "/11.png".
        val basename = path.substringAfterLast('/').lowercase()
        if (basename.isEmpty()) return false
        val needle = "/$basename"
        val iterator = failBlacklist.entries.iterator()
        while (iterator.hasNext()) {
            val (id, at) = iterator.next()
            if (nowMs - at > LogoConstants.FAIL_BLACKLIST_TTL_MS) {
                iterator.remove()
                continue
            }
            if (id.endsWith(needle)) return true
        }
        return false
    }

    fun invalidateKey(cacheKey: String) {
        synchronized(memory) { memory.remove(cacheKey) }
        prefs?.edit()?.remove(DISK_PREFIX + cacheKey)?.apply()
    }

    /**
     * After a trusted Glide decode failure: blacklist the URL identity and drop matching
     * cache entries so the next resolve can pick an **alternate** logo candidate.
     * Does **not** write a miss sentinel (that would block the alternate pick).
     */
    fun markDecodeFailed(url: String?, nowMs: Long = System.currentTimeMillis()) {
        val target = url?.trim().orEmpty()
        if (target.isEmpty()) return
        blacklistUrl(target, nowMs)
        val identity = ArtworkUrls.logoFileIdentity(target)
        val toRemove = mutableListOf<String>()
        synchronized(memory) {
            memory.forEach { (k, v) ->
                if (ArtworkUrls.logoFileIdentity(v.value) == identity || v.value == target) {
                    toRemove.add(k)
                }
            }
            toRemove.forEach { memory.remove(it) }
        }
        val edits = prefs?.edit() ?: return
        prefs?.all?.forEach { (k, v) ->
            if (!k.startsWith(DISK_PREFIX) || v !is String) return@forEach
            val sep = v.lastIndexOf('|')
            if (sep <= 0) return@forEach
            val storedUrl = v.substring(0, sep)
            if (ArtworkUrls.logoFileIdentity(storedUrl) == identity || storedUrl == target) {
                edits.remove(k)
            }
        }
        edits.apply()
    }

    fun invalidateByUrl(url: String?) = markDecodeFailed(url)

    sealed class Lookup {
        data object Fetch : Lookup()
        data object KnownMiss : Lookup()
        data class Hit(val url: String) : Lookup()
    }

    fun lookup(cacheKey: String, nowMs: Long = System.currentTimeMillis()): Lookup {
        val mem = synchronized(memory) { memory[cacheKey] }
        val fromDisk = mem ?: readDisk(cacheKey)?.also {
            synchronized(memory) { memory[cacheKey] = it }
        }
        val read = TmdbLogoPicker.readCache(fromDisk, nowMs, missTtlMs, hitTtlMs)
        return when (read.status) {
            TmdbLogoPicker.CacheStatus.ABSENT -> Lookup.Fetch
            TmdbLogoPicker.CacheStatus.STALE -> {
                invalidateKey(cacheKey)
                Lookup.Fetch
            }
            TmdbLogoPicker.CacheStatus.MISS -> Lookup.KnownMiss
            TmdbLogoPicker.CacheStatus.HIT -> {
                val url = read.url?.takeIf { it.isNotBlank() }
                when {
                    url == null -> Lookup.KnownMiss
                    isBlacklisted(url, nowMs) -> {
                        // Drop broken entry and allow one alternate fetch.
                        invalidateKey(cacheKey)
                        Lookup.Fetch
                    }
                    else -> {
                        TmdbLogoTelemetry.recordCacheHit()
                        Lookup.Hit(url)
                    }
                }
            }
        }.also {
            if (it is Lookup.Fetch) TmdbLogoTelemetry.recordCacheMiss()
            if (it is Lookup.KnownMiss) TmdbLogoTelemetry.recordKnownMiss()
        }
    }

    fun put(cacheKey: String, resolvedUrl: String?, nowMs: Long = System.currentTimeMillis()) {
        val safe = resolvedUrl?.takeIf { it.isNotBlank() && !isBlacklisted(it, nowMs) }
        val entry = TmdbLogoPicker.storeEntry(safe, nowMs)
        synchronized(memory) { memory[cacheKey] = entry }
        writeDisk(cacheKey, entry)
    }

    fun getInflight(flightKey: String): CompletableDeferred<String?>? = inflight[flightKey]

    fun putInflight(
        flightKey: String,
        deferred: CompletableDeferred<String?>,
    ): CompletableDeferred<String?> {
        val winner = inflight.putIfAbsent(flightKey, deferred)
        return winner ?: deferred
    }

    fun removeInflight(flightKey: String, deferred: CompletableDeferred<String?>) {
        inflight.remove(flightKey, deferred)
    }

    private fun readDisk(cacheKey: String): TmdbLogoPicker.CacheEntry? {
        val raw = prefs?.getString(DISK_PREFIX + cacheKey, null) ?: return null
        val sep = raw.lastIndexOf('|')
        if (sep <= 0) return null
        val at = raw.substring(sep + 1).toLongOrNull() ?: return null
        val value = raw.substring(0, sep)
        return TmdbLogoPicker.CacheEntry(value, at)
    }

    private fun writeDisk(cacheKey: String, entry: TmdbLogoPicker.CacheEntry) {
        prefs?.edit()
            ?.putString(DISK_PREFIX + cacheKey, "${entry.value}|${entry.cachedAtMs}")
            ?.apply()
    }
}
