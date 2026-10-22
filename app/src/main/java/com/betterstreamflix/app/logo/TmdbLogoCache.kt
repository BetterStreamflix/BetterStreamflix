package com.betterstreamflix.app.logo

import android.content.Context
import com.betterstreamflix.app.utils.ArtworkUrls
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Memory LRU + file-backed disk ([LogoDiskStore]) logo URL cache with TTLs,
 * fail blacklist, and single-flight inflight map.
 */
object TmdbLogoCache {

    @Volatile
    private var memoryCapacity = LogoConstants.MEMORY_CACHE_MAX_ENTRIES

    private val memory = object : LinkedHashMap<String, TmdbLogoPicker.CacheEntry>(
        LogoConstants.MEMORY_CACHE_MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, TmdbLogoPicker.CacheEntry>?,
        ): Boolean = size > memoryCapacity
    }

    private val inflight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    /** Identity → blacklistedAtMs (identity strips TMDb size tier). */
    private val failBlacklist = ConcurrentHashMap<String, Long>()

    /** Basename → identity set, so file-path exclusion is O(1) per candidate. */
    private val failByBasename = ConcurrentHashMap<String, MutableSet<String>>()

    /** URL-identity → cache keys holding it, so decode-fail eviction is O(1). */
    private val keysByIdentity = ConcurrentHashMap<String, MutableSet<String>>()

    /** Reverse edge of [keysByIdentity] for cheap de-indexing on overwrite. */
    private val identityByKey = ConcurrentHashMap<String, String>()

    @Volatile
    private var missTtlMs: Long = LogoConstants.DEFAULT_MISS_TTL_MS

    @Volatile
    private var hitTtlMs: Long = LogoConstants.DEFAULT_HIT_TTL_MS

    fun init(context: Context) {
        // Memory LRU sized by RAM class: sticks keep a small hot set, flagships
        // hold a whole Featured/detail session in memory.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager
        memoryCapacity = when {
            am == null || am.isLowRamDevice -> LogoConstants.MEMORY_CACHE_MAX_ENTRIES_LOW
            am.memoryClass >= 192 -> LogoConstants.MEMORY_CACHE_MAX_ENTRIES_HIGH
            else -> LogoConstants.MEMORY_CACHE_MAX_ENTRIES
        }
        LogoDiskStore.init(context)
        // Hydrate the persisted decode-fail blacklist so trusted-broken logo URLs
        // stay suppressed across restarts, and bound disk growth by dropping
        // entries older than the maximum configurable TTL (per-entry TTLs are
        // only applied at read time, since configureTtls() runs after init).
        val now = System.currentTimeMillis()
        LogoDiskStore.forEachBlacklist { identity, at ->
            if (now - at > LogoConstants.FAIL_BLACKLIST_TTL_MS) {
                LogoDiskStore.removeBlacklist(identity)
            } else {
                failBlacklist[identity] = at
                indexBlacklist(identity)
            }
        }
        LogoDiskStore.forEachEntry { cacheKey, value, at ->
            if (now - at > LogoConstants.MAX_DISK_ENTRY_AGE_MS) {
                LogoDiskStore.remove(cacheKey)
            } else {
                indexUrl(cacheKey, value)
            }
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
        keysByIdentity.clear()
        identityByKey.clear()
        LogoDiskStore.clearAll()
    }

    /** Logo-only clear — same as clearAll for this cache. */
    fun clearLogoOnly() = clearAll()

    fun blacklistUrl(url: String?, nowMs: Long = System.currentTimeMillis()) {
        val identity = ArtworkUrls.logoFileIdentity(url)
        if (identity.isEmpty()) return
        failBlacklist[identity] = nowMs
        indexBlacklist(identity)
        LogoDiskStore.putBlacklist(identity, nowMs)
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
        LogoDiskStore.removeBlacklist(identity)
    }

    private fun indexUrl(cacheKey: String, url: String?) {
        val identity = ArtworkUrls.logoFileIdentity(url)
        if (identity.isEmpty()) return
        // Move the key between identity buckets when the stored URL changes.
        identityByKey[cacheKey]?.let { old ->
            keysByIdentity[old]?.remove(cacheKey)
        }
        identityByKey[cacheKey] = identity
        keysByIdentity.getOrPut(identity) { ConcurrentHashMap.newKeySet() }.add(cacheKey)
    }

    private fun deindexUrl(cacheKey: String) {
        val identity = identityByKey.remove(cacheKey) ?: return
        keysByIdentity[identity]?.remove(cacheKey)
    }

    fun invalidateKey(cacheKey: String) {
        synchronized(memory) { memory.remove(cacheKey) }
        deindexUrl(cacheKey)
        LogoDiskStore.remove(cacheKey)
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
        // Index-driven eviction: only cache keys that stored this URL identity —
        // no full-memory/disk scan per decode failure.
        val candidates = mutableSetOf<String>().apply {
            keysByIdentity[identity]?.let { addAll(it) }
            // Keys written before this session's index existed still carry the
            // exact-URL disk value — sweep memory for a direct match.
            synchronized(memory) {
                memory.forEach { (k, v) ->
                    if (v.value == target ||
                        ArtworkUrls.logoFileIdentity(v.value) == identity
                    ) {
                        add(k)
                    }
                }
            }
        }
        if (candidates.isEmpty()) return
        candidates.forEach { key ->
            synchronized(memory) { memory.remove(key) }
            deindexUrl(key)
            LogoDiskStore.remove(key)
        }
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
        indexUrl(cacheKey, safe)
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
        val row = LogoDiskStore.get(cacheKey) ?: return null
        return TmdbLogoPicker.CacheEntry(row.first, row.second)
    }

    private fun writeDisk(cacheKey: String, entry: TmdbLogoPicker.CacheEntry) {
        LogoDiskStore.put(cacheKey, entry.value, entry.cachedAtMs)
    }

    /** Force-persist pending batched writes (app pause / trim). */
    fun flush() = LogoDiskStore.flushNow()
}
