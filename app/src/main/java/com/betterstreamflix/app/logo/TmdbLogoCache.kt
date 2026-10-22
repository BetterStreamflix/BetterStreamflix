package com.betterstreamflix.app.logo

import android.content.Context
import android.content.SharedPreferences
import com.betterstreamflix.app.utils.ArtworkUrls
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Memory LRU + disk (SharedPreferences) logo URL cache with TTLs, fail blacklist,
 * and single-flight inflight map.
 */
object TmdbLogoCache {

    private const val DISK_PREFIX = "logo|"
    private const val BLACKLIST_PREFIX = "bl|"
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

    /** Basename → identity set, so file-path exclusion is O(1) per candidate. */
    private val failByBasename = ConcurrentHashMap<String, MutableSet<String>>()

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
        // Hydrate the persisted decode-fail blacklist so trusted-broken logo URLs
        // stay suppressed across restarts, and bound disk growth by dropping
        // malformed rows plus entries older than the maximum configurable TTL
        // (per-entry TTLs are only applied at read time, since configureTtls()
        // runs after init).
        val now = System.currentTimeMillis()
        val prune = mutableListOf<String>()
        p.all.forEach { (k, v) ->
            when {
                k.startsWith(BLACKLIST_PREFIX) -> {
                    val at = v as? Long
                    if (at == null || now - at > LogoConstants.FAIL_BLACKLIST_TTL_MS) {
                        prune.add(k)
                    } else {
                        val identity = k.removePrefix(BLACKLIST_PREFIX)
                        failBlacklist[identity] = at
                        indexBlacklist(identity)
                    }
                }
                k.startsWith(DISK_PREFIX) -> {
                    val raw = v as? String
                    val sep = raw?.lastIndexOf('|') ?: -1
                    val at = raw?.substring(sep + 1)?.toLongOrNull()
                    if (sep < 0 || at == null || now - at > LogoConstants.MAX_DISK_ENTRY_AGE_MS) {
                        prune.add(k)
                    }
                }
            }
        }
        if (prune.isNotEmpty()) {
            val edits = p.edit()
            prune.forEach { edits.remove(it) }
            edits.apply()
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
        failByBasename.clear()
        prefs?.edit()?.clear()?.putInt(META_VERSION, LogoConstants.DISK_CACHE_VERSION)?.apply()
    }

    /** Logo-only clear — same as clearAll for this cache. */
    fun clearLogoOnly() = clearAll()

    fun blacklistUrl(url: String?, nowMs: Long = System.currentTimeMillis()) {
        val identity = ArtworkUrls.logoFileIdentity(url)
        if (identity.isEmpty()) return
        failBlacklist[identity] = nowMs
        indexBlacklist(identity)
        prefs?.edit()?.putLong(BLACKLIST_PREFIX + identity, nowMs)?.apply()
        TmdbLogoTelemetry.recordFailBlacklist()
    }

    fun isBlacklisted(url: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val identity = ArtworkUrls.logoFileIdentity(url)
        if (identity.isEmpty()) return false
        val at = failBlacklist[identity] ?: return false
        if (nowMs - at > LogoConstants.FAIL_BLACKLIST_TTL_MS) {
            evictBlacklist(identity)
            return false
        }
        return true
    }

    fun isFilePathBlacklisted(filePath: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        // Boundary match on basename so "/1.png" does not poison "/11.png".
        val basename = filePath?.trim()
            ?.substringAfterLast('/')
            ?.lowercase()
            .orEmpty()
        if (basename.isEmpty()) return false
        val bucket = failByBasename[basename] ?: return false
        val expired = mutableListOf<String>()
        var live = false
        for (id in bucket) {
            val at = failBlacklist[id]
            if (at == null || nowMs - at > LogoConstants.FAIL_BLACKLIST_TTL_MS) {
                expired.add(id)
            } else {
                live = true
            }
        }
        expired.forEach { evictBlacklist(it) }
        return live
    }

    private fun indexBlacklist(identity: String) {
        val basename = identity.substringAfterLast('/').lowercase()
        if (basename.isEmpty()) return
        failByBasename.getOrPut(basename) { ConcurrentHashMap.newKeySet() }.add(identity)
    }

    private fun deindexBlacklist(identity: String) {
        val basename = identity.substringAfterLast('/').lowercase()
        val bucket = failByBasename[basename] ?: return
        bucket.remove(identity)
        if (bucket.isEmpty()) failByBasename.remove(basename, bucket)
    }

    private fun evictBlacklist(identity: String) {
        failBlacklist.remove(identity)
        deindexBlacklist(identity)
        prefs?.edit()?.remove(BLACKLIST_PREFIX + identity)?.apply()
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
        // sep == 0 is a persisted miss ("|<ts>"), not a malformed row.
        if (sep < 0) return null
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
