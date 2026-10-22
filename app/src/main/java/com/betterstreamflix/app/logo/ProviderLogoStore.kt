package com.betterstreamflix.app.logo

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Persisted provider-logo store — same semantics as [ChannelLogoStore], own
 * prefs file: last-good URL per provider + decode-fail blacklist. Provider
 * logo URLs change rarely, so hits share the channel hit TTL.
 *
 * `hit|<providerKey>` → `url|cachedAtMs`
 * `bl|<url>`          → blacklistedAtMs
 */
object ProviderLogoStore {

    private const val PREFS = "provider_logo_cache_v1"
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

    fun effectiveUrl(providerKey: String, url: String?): String? {
        val clean = ChannelLogoStore.normalize(url)
        if (clean != null && !isBlacklisted(clean)) return clean
        val (cached, at) = hits[providerKey] ?: return null
        if (System.currentTimeMillis() - at > LogoConstants.CHANNEL_LOGO_HIT_TTL_MS) {
            evictHit(providerKey)
            return null
        }
        return cached.takeUnless { isBlacklisted(it) }
    }

    fun recordSuccess(providerKey: String, url: String?) {
        val clean = ChannelLogoStore.normalize(url) ?: return
        val now = System.currentTimeMillis()
        hits[providerKey] = clean to now
        prefs?.edit()?.putString("$HIT_PREFIX$providerKey", "$clean|$now")?.apply()
    }

    fun recordFailure(url: String?) {
        val clean = ChannelLogoStore.normalize(url) ?: return
        val now = System.currentTimeMillis()
        blacklist[clean] = now
        prefs?.edit()?.putLong("$BL_PREFIX$clean", now)?.apply()
        hits.forEach { (id, pair) ->
            if (pair.first == clean) evictHit(id)
        }
    }

    fun isBlacklisted(url: String?): Boolean {
        val clean = ChannelLogoStore.normalize(url) ?: return false
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

    private fun evictHit(providerKey: String) {
        hits.remove(providerKey)
        prefs?.edit()?.remove("$HIT_PREFIX$providerKey")?.apply()
    }
}
