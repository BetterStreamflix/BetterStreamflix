package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.UserPreferences
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider A/B — per-user (profile-scoped) hoster stability learning.
 *
 * Failover and first-pick reorder servers so historically stable hosters
 * for this profile + provider rise to the front. Integrates with
 * [ProviderHealth] (display) and [com.dskja.betterstreamflix.player.PlaybackFailover]
 * (list order = hop order).
 */
object ProviderServerAb {

    data class Stats(
        val successes: Int = 0,
        val failures: Int = 0,
        val lastSuccessAtMs: Long = 0L,
    ) {
        val samples: Int get() = successes + failures
    }

    /** Cap per-server samples so ancient history cannot dominate forever. */
    const val MAX_SAMPLES = 40

    /** Minimum samples before a server outranks unknown peers. */
    const val MIN_SAMPLES_TO_LEAD = 2

    /** Process-local de-dupe so one play session does not spam prefs. */
    private val sessionRewarded = ConcurrentHashMap.newKeySet<String>()

    /** Unit-test override — when set, prefs are not read/written. */
    @Volatile
    internal var memoryStore: MutableMap<String, MutableMap<String, Stats>>? = null

    fun clearSession() {
        sessionRewarded.clear()
    }

    /** Convenience for player surfaces — no-ops for IPTV / missing provider. */
    fun notePlaybackStable(server: Video.Server?) {
        val provider = UserPreferences.currentProvider
        if (server == null || !shouldTrack(provider)) return
        recordSuccess(provider!!.name, server)
    }

    fun notePlaybackUnstable(server: Video.Server?) {
        val provider = UserPreferences.currentProvider
        if (server == null || !shouldTrack(provider)) return
        recordFailure(provider!!.name, server)
    }

    fun serverKey(server: Video.Server): String {
        val name = server.name.trim()
        if (name.isNotEmpty()) return name.lowercase()
        val id = server.id.trim()
        return id.lowercase()
    }

    fun shouldTrack(provider: Provider?): Boolean {
        if (provider == null) return false
        if (provider is IptvProvider) return false
        return true
    }

    /**
     * Laplace-smoothed success rate in `0..1`, lightly boosted by recency.
     * Unknown servers score as a neutral prior (0.5).
     */
    fun score(stats: Stats?, nowMs: Long = System.currentTimeMillis()): Double {
        if (stats == null || stats.samples <= 0) return 0.5
        val rate = (stats.successes + 1.0) / (stats.samples + 2.0)
        val ageMs = (nowMs - stats.lastSuccessAtMs).coerceAtLeast(0L)
        val dayMs = 86_400_000.0
        val recency = when {
            stats.lastSuccessAtMs <= 0L -> 0.0
            ageMs < dayMs -> 0.05
            ageMs < 7 * dayMs -> 0.02
            else -> 0.0
        }
        return (rate + recency).coerceIn(0.0, 1.0)
    }

    fun prioritize(
        providerName: String,
        servers: List<Video.Server>,
        preferredServerName: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Video.Server> {
        if (servers.size <= 1) return servers
        val table = loadProvider(providerName)
        val ranked = servers
            .mapIndexed { index, server -> Triple(index, server, score(table[serverKey(server)], nowMs)) }
            .sortedWith(
                compareByDescending<Triple<Int, Video.Server, Double>> { (_, server, sc) ->
                    val samples = table[serverKey(server)]?.samples ?: 0
                    if (samples >= MIN_SAMPLES_TO_LEAD) sc else 0.5
                }.thenBy { it.first },
            )
            .map { it.second }
        val preferred = preferredServerName?.trim().orEmpty()
        if (preferred.isEmpty()) return ranked
        val hit = ranked.firstOrNull { it.name.equals(preferred, ignoreCase = true) }
            ?: return ranked
        return listOf(hit) + ranked.filter { it !== hit }
    }

    fun recordSuccess(providerName: String, server: Video.Server, nowMs: Long = System.currentTimeMillis()) {
        if (providerName.isBlank()) return
        val key = serverKey(server)
        if (key.isEmpty()) return
        val sessionKey = "ok:$providerName:$key"
        if (!sessionRewarded.add(sessionKey)) return
        mutate(providerName, key) { prev ->
            val nextS = (prev.successes + 1).coerceAtMost(MAX_SAMPLES)
            val nextF = prev.failures.coerceAtMost((MAX_SAMPLES - nextS).coerceAtLeast(0))
            Stats(successes = nextS, failures = nextF, lastSuccessAtMs = nowMs)
        }
    }

    fun recordFailure(providerName: String, server: Video.Server) {
        if (providerName.isBlank()) return
        val key = serverKey(server)
        if (key.isEmpty()) return
        val sessionKey = "fail:$providerName:$key"
        if (!sessionRewarded.add(sessionKey)) return
        mutate(providerName, key) { prev ->
            val nextF = (prev.failures + 1).coerceAtMost(MAX_SAMPLES)
            val nextS = prev.successes.coerceAtMost((MAX_SAMPLES - nextF).coerceAtLeast(0))
            Stats(successes = nextS, failures = nextF, lastSuccessAtMs = prev.lastSuccessAtMs)
        }
    }

    /** Top learned hosters for Settings / Provider Health copy. */
    fun topServers(
        providerName: String,
        limit: Int = 3,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Pair<String, Stats>> {
        if (limit <= 0) return emptyList()
        return loadProvider(providerName)
            .entries
            .filter { it.value.samples >= MIN_SAMPLES_TO_LEAD }
            .sortedByDescending { score(it.value, nowMs) }
            .take(limit)
            .map { it.key to it.value }
    }

    /** Lower is better — used by [ProviderHealth.pickerRank]-style sorts when needed. */
    fun rankBonus(providerName: String, serverName: String, nowMs: Long = System.currentTimeMillis()): Int {
        val stats = loadProvider(providerName)[serverName.trim().lowercase()] ?: return 0
        if (stats.samples < MIN_SAMPLES_TO_LEAD) return 0
        // Map score 1.0 → -20, 0.0 → +20 so healthier hosters sort earlier with lower ranks.
        return ((0.5 - score(stats, nowMs)) * 40.0).toInt()
    }

    private val lock = Any()

    private fun mutate(providerName: String, serverKey: String, transform: (Stats) -> Stats) {
        synchronized(lock) {
            val root = loadAll()
            val provider = root.getOrPut(providerName) { linkedMapOf() }
            val prev = provider[serverKey] ?: Stats()
            provider[serverKey] = transform(prev)
            // Bound map size per provider.
            if (provider.size > 48) {
                val weakest = provider.entries
                    .sortedBy { score(it.value) }
                    .take(provider.size - 48)
                    .map { it.key }
                weakest.forEach { provider.remove(it) }
            }
            saveAll(root)
        }
    }

    private fun loadProvider(providerName: String): Map<String, Stats> =
        loadAll()[providerName].orEmpty()

    private fun loadAll(): MutableMap<String, MutableMap<String, Stats>> {
        memoryStore?.let { return it }
        val raw = runCatching { UserPreferences.providerServerAbJson }.getOrDefault("")
        return parse(raw)
    }

    private fun saveAll(root: Map<String, Map<String, Stats>>) {
        memoryStore?.let { store ->
            // [root] may be the same instance as [store] — copy before mutating.
            if (root !== store) {
                store.clear()
                root.forEach { (p, servers) ->
                    store[p] = servers.toMutableMap()
                }
            }
            return
        }
        runCatching {
            UserPreferences.providerServerAbJson = serialize(root)
        }
    }

    internal fun parse(raw: String): MutableMap<String, MutableMap<String, Stats>> {
        val out = linkedMapOf<String, MutableMap<String, Stats>>()
        if (raw.isBlank()) return out
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return out
        val keys = root.keys()
        while (keys.hasNext()) {
            val provider = keys.next()
            val serversJson = root.optJSONObject(provider) ?: continue
            val servers = linkedMapOf<String, Stats>()
            val sKeys = serversJson.keys()
            while (sKeys.hasNext()) {
                val sk = sKeys.next()
                val node = serversJson.optJSONObject(sk) ?: continue
                servers[sk.lowercase()] = Stats(
                    successes = node.optInt("s", 0).coerceAtLeast(0),
                    failures = node.optInt("f", 0).coerceAtLeast(0),
                    lastSuccessAtMs = node.optLong("t", 0L).coerceAtLeast(0L),
                )
            }
            if (servers.isNotEmpty()) out[provider] = servers
        }
        return out
    }

    internal fun serialize(root: Map<String, Map<String, Stats>>): String {
        val json = JSONObject()
        root.forEach { (provider, servers) ->
            val node = JSONObject()
            servers.forEach { (key, stats) ->
                if (stats.samples <= 0) return@forEach
                node.put(
                    key,
                    JSONObject()
                        .put("s", stats.successes)
                        .put("f", stats.failures)
                        .put("t", stats.lastSuccessAtMs),
                )
            }
            if (node.length() > 0) json.put(provider, node)
        }
        return json.toString()
    }

    /** Prefs key helper exposed for diagnostics. */
    fun prefsKeyForActiveProfile(): String =
        runCatching { ProfileManager.scopedPrefKey("PROVIDER_SERVER_AB_JSON") }
            .getOrDefault("PROVIDER_SERVER_AB_JSON")
}
