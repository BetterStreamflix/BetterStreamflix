package com.betterstreamflix.app.logo

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Persisted channel-logo store: last-good URL per channel + decode-fail
 * blacklist (same TTL semantics as [TmdbLogoCache], own prefs file).
 *
 * `hit|<channelId>` → `url|cachedAtMs` (30d TTL)
 * `bl|<url>`        → blacklistedAtMs (30min TTL)
 */
object ChannelLogoStore {

    private const val PREFS = "channel_logo_cache_v1"
    private const val HIT_PREFIX = "hit|"
    private const val BL_PREFIX = "bl|"

    private val hits = ConcurrentHashMap<String, Pair<String, Long>>()
    private val blacklist = ConcurrentHashMap<String, Long>()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val now = System.currentTimeMillis()
        val prune = mutableListOf<String>()
        p.all.forEach { (k, v) ->
            when {
                k.startsWith(HIT_PREFIX) && v is String -> {
                    val sep = v.lastIndexOf('|')
                    val at = v.substring(sep + 1).toLongOrNull()
                    val url = v.substring(0, sep.coerceAtLeast(0))
                    when {
                        sep <= 0 || at == null -> prune.add(k)
                        now - at > LogoConstants.CHANNEL_LOGO_HIT_TTL_MS -> prune.add(k)
                        else -> hits[k.removePrefix(HIT_PREFIX)] = url to at
                    }
                }
                k.startsWith(BL_PREFIX) -> {
                    val at = v as? Long
                    if (at == null || now - at > LogoConstants.FAIL_BLACKLIST_TTL_MS) {
                        prune.add(k)
                    } else {
                        blacklist[k.removePrefix(BL_PREFIX)] = at
                    }
                }
            }
        }
        if (prune.isNotEmpty()) {
            val e = p.edit()
            prune.forEach { e.remove(it) }
            e.apply()
        }
    }

    /**
     * Effective URL for a channel: the incoming URL unless blacklisted, else the
     * last-known-good URL, else null (lettermark path).
     */
    fun effectiveUrl(channelId: String, url: String?): String? {
        val clean = normalize(url)
        if (clean != null && !isBlacklisted(clean)) return clean
        val (cached, at) = hits[channelId] ?: return null
        if (System.currentTimeMillis() - at > LogoConstants.CHANNEL_LOGO_HIT_TTL_MS) {
            evictHit(channelId)
            return null
        }
        return cached.takeUnless { isBlacklisted(it) }
    }

    /** Record the URL that actually decoded for this channel. */
    fun recordSuccess(channelId: String, url: String?) {
        val clean = normalize(url) ?: return
        val now = System.currentTimeMillis()
        hits[channelId] = clean to now
        prefs?.edit()?.putString("$HIT_PREFIX$channelId", "$clean|$now")?.apply()
    }

    /** A trusted decode/load failure — suppress the URL for the blacklist TTL. */
    fun recordFailure(url: String?) {
        val clean = normalize(url) ?: return
        val now = System.currentTimeMillis()
        blacklist[clean] = now
        prefs?.edit()?.putLong("$BL_PREFIX$clean", now)?.apply()
        // Drop hits pointing at the now-broken URL.
        hits.forEach { (id, pair) ->
            if (pair.first == clean) evictHit(id)
        }
    }

    fun isBlacklisted(url: String?): Boolean {
        val clean = normalize(url) ?: return false
        val at = blacklist[clean] ?: return false
        if (System.currentTimeMillis() - at > LogoConstants.FAIL_BLACKLIST_TTL_MS) {
            blacklist.remove(clean)
            prefs?.edit()?.remove("$BL_PREFIX$clean")?.apply()
            return false
        }
        return true
    }

    fun clearAll() {
        hits.clear()
        blacklist.clear()
        prefs?.edit()?.clear()?.apply()
    }

    private fun evictHit(channelId: String) {
        hits.remove(channelId)
        prefs?.edit()?.remove("$HIT_PREFIX$channelId")?.apply()
    }

    /** Cleartext is blocked on modern targets — upgrade http → https upfront. */
    fun normalize(url: String?): String? {
        val clean = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (clean.startsWith("http://")) "https://" + clean.removePrefix("http://") else clean
    }
}
