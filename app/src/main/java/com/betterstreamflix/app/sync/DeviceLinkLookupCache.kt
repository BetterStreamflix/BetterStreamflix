package com.betterstreamflix.app.sync

import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived local cache of host email after the first successful
 * [DeviceLinkRepository.lookupByCode]. The server burns the code on lookup
 * (SC-004); password retries on the same device reuse this cache instead of
 * calling the RPC again.
 */
object DeviceLinkLookupCache {

    private data class Entry(
        val email: String,
        val expiresAtMs: Long,
        val shortCode: String,
    )

    private val byCode = ConcurrentHashMap<String, Entry>()

    fun put(code: String, email: String, expiresAtIso: String?) {
        val cleaned = DeviceLinkCodes.normalize(code) ?: return
        if (email.isBlank() || !email.contains('@')) return
        val expiresAtMs = parseExpiresAtMs(expiresAtIso)
            ?: (System.currentTimeMillis() + 15L * 60L * 1000L)
        byCode[cleaned] = Entry(
            email = email.trim(),
            expiresAtMs = expiresAtMs,
            shortCode = cleaned,
        )
    }

    fun get(code: String): DeviceLinkRepository.LookupResult? {
        val cleaned = DeviceLinkCodes.normalize(code) ?: return null
        val entry = byCode[cleaned] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            byCode.remove(cleaned)
            return null
        }
        return DeviceLinkRepository.LookupResult(
            shortCode = entry.shortCode,
            email = entry.email,
            expiresAt = null,
        )
    }

    fun clear(code: String? = null) {
        if (code == null) {
            byCode.clear()
            return
        }
        DeviceLinkCodes.normalize(code)?.let { byCode.remove(it) }
    }

    /** Test helper — wipe all entries. */
    internal fun clearAllForTests() {
        byCode.clear()
    }

    private fun parseExpiresAtMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
    }
}
