package com.betterstreamflix.app.logo

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * File-backed disk layer for [TmdbLogoCache].
 *
 * Why not SharedPreferences: prefs keeps every entry in memory forever and
 * each apply() rewrites the whole XML — a scroll storm writes megabytes.
 * Here the store is a single tab-separated journal file, hydrated once at
 * init, mutated through the in-memory shadow maps, and flushed with a short
 * debounce so bursts collapse into one write.
 *
 * Format per line: `L<tab>key<tab>value<tab>cachedAtMs` / `B<tab>identity<tab>atMs`.
 */
object LogoDiskStore {

    private const val FILE_NAME = "tmdb_logo_cache_v3.txt"
    private const val FLUSH_DELAY_MS = 450L
    private const val MAX_FLUSH_DELAY_MS = 2_500L
    private const val MIGRATION_PREFS_DONE = "logo_disk_migrated_v3"

    private val entries = ConcurrentHashMap<String, Pair<String, Long>>()
    private val blacklist = ConcurrentHashMap<String, Long>()

    @Volatile
    private var file: File? = null

    @Volatile
    private var legacyPrefs: android.content.SharedPreferences? = null

    // Nullable: plain JVM unit tests have no main looper — the in-memory
    // maps still serve reads, scheduling just becomes a no-op there.
    private val handler: Handler? by lazy {
        runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }
    private val flushScheduled = AtomicBoolean()
    private val dirty = AtomicBoolean()

    @Volatile
    private var dirtySinceMs = 0L

    private val flushRunnable = Runnable { flushNow() }

    fun init(context: Context) {
        val app = context.applicationContext
        file = File(app.filesDir, FILE_NAME)
        loadFromDisk()
        migrateFromLegacyPrefs(app)
    }

    // ---------------- reads ----------------

    fun get(key: String): Pair<String, Long>? = entries[key]

    fun getBlacklist(identity: String): Long? = blacklist[identity]

    fun forEachEntry(block: (String, String, Long) -> Unit) {
        entries.forEach { (k, v) -> block(k, v.first, v.second) }
    }

    fun forEachBlacklist(block: (String, Long) -> Unit) {
        blacklist.forEach { (k, v) -> block(k, v) }
    }

    fun containsKey(key: String): Boolean = entries.containsKey(key)

    // ---------------- writes (batched) ----------------

    fun put(key: String, value: String, atMs: Long) {
        entries[key] = value to atMs
        scheduleFlush()
    }

    fun remove(key: String) {
        if (entries.remove(key) != null) scheduleFlush()
    }

    fun putBlacklist(identity: String, atMs: Long) {
        blacklist[identity] = atMs
        scheduleFlush()
    }

    fun removeBlacklist(identity: String) {
        if (blacklist.remove(identity) != null) scheduleFlush()
    }

    fun clearAll() {
        entries.clear()
        blacklist.clear()
        handler?.removeCallbacks(flushRunnable)
        flushScheduled.set(false)
        dirty.set(false)
        runCatching { file?.writeText("") }
    }

    // ---------------- flush machinery ----------------

    private fun scheduleFlush() {
        val now = System.currentTimeMillis()
        if (!dirty.getAndSet(true)) dirtySinceMs = now
        // Hard cap: never delay past MAX_FLUSH_DELAY_MS once dirty.
        val delay = if (now - dirtySinceMs > MAX_FLUSH_DELAY_MS) 0L else FLUSH_DELAY_MS
        if (flushScheduled.compareAndSet(false, true)) {
            if (handler?.postDelayed(flushRunnable, delay) != true) {
                flushScheduled.set(false)
            }
        }
    }

    /** Write-through — call on app pause / trim so nothing sits dirty. */
    fun flushNow() {
        flushScheduled.set(false)
        handler?.removeCallbacks(flushRunnable)
        if (!dirty.getAndSet(false)) return
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                entries.forEach { (k, v) ->
                    w.append('L').append('\t')
                        .append(k).append('\t')
                        .append(v.first).append('\t')
                        .append(v.second.toString()).append('\n')
                }
                blacklist.forEach { (id, at) ->
                    w.append('B').append('\t')
                        .append(id).append('\t')
                        .append(at.toString()).append('\n')
                }
            }
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }

    private fun loadFromDisk() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            f.readLines(Charsets.UTF_8).forEach { line ->
                val parts = line.split('\t')
                when (parts.getOrNull(0)) {
                    "L" -> {
                        val key = parts.getOrNull(1) ?: return@forEach
                        val value = parts.getOrNull(2) ?: ""
                        val at = parts.getOrNull(3)?.toLongOrNull() ?: return@forEach
                        entries[key] = value to at
                    }
                    "B" -> {
                        val id = parts.getOrNull(1) ?: return@forEach
                        val at = parts.getOrNull(2)?.toLongOrNull() ?: return@forEach
                        blacklist[id] = at
                    }
                }
            }
        }
    }

    /** One-time import of the legacy SharedPreferences store, then wiped. */
    private fun migrateFromLegacyPrefs(context: Context) {
        val marker = context.applicationContext.getSharedPreferences(
            "logo_disk_meta", Context.MODE_PRIVATE,
        )
        if (marker.getBoolean(MIGRATION_PREFS_DONE, false)) return
        val p = context.applicationContext.getSharedPreferences(
            LogoConstants.PREFS_FILE, Context.MODE_PRIVATE,
        )
        legacyPrefs = p
        var imported = 0
        p.all.forEach { (k, v) ->
            when {
                k.startsWith("logo|") && v is String -> {
                    val sep = v.lastIndexOf('|')
                    val at = if (sep >= 0) v.substring(sep + 1).toLongOrNull() else null
                    if (at != null) {
                        entries[k.removePrefix("logo|")] = v.substring(0, sep) to at
                        imported++
                    }
                }
                k.startsWith("bl|") && v is Long -> {
                    blacklist[k.removePrefix("bl|")] = v
                    imported++
                }
            }
        }
        if (imported > 0) flushNow()
        // Clear only the logo rows — keep the meta_version flag write below.
        val e = p.edit()
        p.all.keys.filter { it.startsWith("logo|") || it.startsWith("bl|") }
            .forEach { e.remove(it) }
        e.apply()
        marker.edit().putBoolean(MIGRATION_PREFS_DONE, true).apply()
    }
}
