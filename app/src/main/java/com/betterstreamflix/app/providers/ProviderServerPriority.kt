package com.betterstreamflix.app.providers

import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.utils.UserPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manual server priority pins per provider (profile-scoped).
 * Pinned hosters win over A/B learning; preferredServerName still wins first.
 */
object ProviderServerPriority {

    /** Unit-test override. */
    @Volatile
    internal var memoryStore: MutableMap<String, MutableList<String>>? = null

    fun isPinned(providerName: String, server: Video.Server): Boolean {
        val key = ProviderServerAb.serverKey(server)
        if (key.isEmpty()) return false
        return pinsFor(providerName).any { it.equals(key, ignoreCase = true) }
    }

    fun isPinnedName(providerName: String, serverName: String): Boolean {
        val key = serverName.trim().lowercase()
        if (key.isEmpty()) return false
        return pinsFor(providerName).any { it.equals(key, ignoreCase = true) }
    }

    fun pinsFor(providerName: String): List<String> =
        loadAll()[providerName].orEmpty()

    fun pin(providerName: String, serverName: String) {
        val key = serverName.trim().lowercase()
        if (providerName.isBlank() || key.isEmpty()) return
        mutate(providerName) { list ->
            list.removeAll { it.equals(key, ignoreCase = true) }
            list.add(0, key)
            while (list.size > 8) list.removeAt(list.lastIndex)
        }
    }

    fun unpin(providerName: String, serverName: String) {
        val key = serverName.trim().lowercase()
        if (providerName.isBlank() || key.isEmpty()) return
        mutate(providerName) { list ->
            list.removeAll { it.equals(key, ignoreCase = true) }
        }
    }

    fun toggle(providerName: String, serverName: String): Boolean {
        return if (isPinnedName(providerName, serverName)) {
            unpin(providerName, serverName)
            false
        } else {
            pin(providerName, serverName)
            true
        }
    }

    fun clear(providerName: String) {
        mutate(providerName) { it.clear() }
    }

    /**
     * Reorder [servers] so manual pins rise after an optional preferred name,
     * then leave remaining order to the caller (typically A/B).
     */
    fun applyPins(
        providerName: String,
        servers: List<Video.Server>,
        preferredServerName: String? = null,
    ): List<Video.Server> {
        if (servers.size <= 1) return servers
        val pins = pinsFor(providerName)
        if (pins.isEmpty() && preferredServerName.isNullOrBlank()) return servers

        val remaining = servers.toMutableList()
        val head = mutableListOf<Video.Server>()

        val preferred = preferredServerName?.trim().orEmpty()
        if (preferred.isNotEmpty()) {
            val hit = remaining.firstOrNull { it.name.equals(preferred, ignoreCase = true) }
            if (hit != null) {
                remaining.remove(hit)
                head.add(hit)
            }
        }
        for (pin in pins) {
            val hit = remaining.firstOrNull {
                ProviderServerAb.serverKey(it).equals(pin, ignoreCase = true)
            } ?: continue
            remaining.remove(hit)
            head.add(hit)
        }
        return head + remaining
    }

    private fun mutate(providerName: String, block: (MutableList<String>) -> Unit) {
        val root = loadAll()
        val list = root.getOrPut(providerName) { mutableListOf() }
        block(list)
        if (list.isEmpty()) root.remove(providerName)
        saveAll(root)
    }

    private fun loadAll(): MutableMap<String, MutableList<String>> {
        memoryStore?.let { return it }
        val raw = runCatching { UserPreferences.providerServerPriorityJson }.getOrDefault("")
        return parse(raw)
    }

    private fun saveAll(root: Map<String, List<String>>) {
        memoryStore?.let { store ->
            if (root !== store) {
                store.clear()
                root.forEach { (p, pins) -> store[p] = pins.toMutableList() }
            }
            return
        }
        runCatching {
            UserPreferences.providerServerPriorityJson = serialize(root)
        }
    }

    internal fun parse(raw: String): MutableMap<String, MutableList<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        if (raw.isBlank()) return out
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return out
        val keys = root.keys()
        while (keys.hasNext()) {
            val provider = keys.next()
            val arr = root.optJSONArray(provider) ?: continue
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim().lowercase()
                if (v.isNotEmpty() && v !in list) list.add(v)
            }
            if (list.isNotEmpty()) out[provider] = list
        }
        return out
    }

    internal fun serialize(root: Map<String, List<String>>): String {
        val json = JSONObject()
        root.forEach { (provider, pins) ->
            if (pins.isEmpty()) return@forEach
            val arr = JSONArray()
            pins.forEach { arr.put(it) }
            json.put(provider, arr)
        }
        return json.toString()
    }
}
