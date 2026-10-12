package com.dskja.betterstreamflix.live

import android.content.Context
import androidx.core.content.edit
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.LiveHubCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Profile-scoped ordered pins for Meine Sender-Leiste (Stories-style live strip).
 * Keys match [LiveHubCatalog.identityKey].
 */
object ChannelPinStore {

    const val MAX_PINS = 12
    private const val PREFS = "channel_pin_strip"
    private const val KEY_PINS = "CHANNEL_PINS_JSON"

    private val lock = Any()

    data class Pin(
        val id: String,
        val provider: String,
        val title: String,
        val poster: String? = null,
    ) {
        val key: String get() = "$provider|$id"

        fun toTvShow(): TvShow = TvShow(
            id = id,
            title = title,
            poster = poster,
        ).apply {
            providerName = provider
            isFavorite = true
        }
    }

    fun list(context: Context): List<Pin> = synchronized(lock) {
        val raw = prefs(context).getString(prefKey(), null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching { parse(raw) }.getOrDefault(emptyList())
    }

    fun pinCount(context: Context): Int = list(context).size

    fun isPinned(context: Context, show: TvShow): Boolean {
        val key = LiveHubCatalog.identityKey(show)
        return list(context).any { it.key == key }
    }

    fun isPinnedKey(context: Context, key: String): Boolean =
        list(context).any { it.key == key }

    /**
     * @return true when the pin was stored. When at [MAX_PINS] and [show] is new,
     * still pins (evicts oldest) and returns true; callers may toast that the
     * strip was at capacity (SL-006).
     */
    fun pin(context: Context, show: TvShow): Boolean {
        val provider = show.providerName?.trim().orEmpty()
        val id = show.id.trim()
        if (provider.isEmpty() || id.isEmpty()) return false
        val pin = Pin(
            id = id,
            provider = provider,
            title = show.title.ifBlank { id },
            poster = show.poster,
        )
        synchronized(lock) {
            val current = listUnlocked(context).toMutableList()
            current.removeAll { it.key == pin.key }
            current.add(0, pin)
            while (current.size > MAX_PINS) {
                current.removeAt(current.lastIndex)
            }
            writeUnlocked(context, current)
        }
        return true
    }

    fun unpin(context: Context, show: TvShow): Boolean =
        unpinKey(context, LiveHubCatalog.identityKey(show))

    fun unpinKey(context: Context, key: String): Boolean = synchronized(lock) {
        val current = listUnlocked(context)
        val next = current.filterNot { it.key == key }
        if (next.size == current.size) return false
        writeUnlocked(context, next)
        return true
    }

    /** @return true when pinned after toggle; false when unpinned or pin failed (SL-005). */
    fun toggle(context: Context, show: TvShow): Boolean {
        return if (isPinned(context, show)) {
            unpin(context, show)
            false
        } else {
            pin(context, show)
        }
    }

    /**
     * Seed from live favorites only when the prefs key is **absent** (first run).
     * An intentional empty strip stores `"[]"` and must not re-seed (SL-001).
     */
    fun seedFromFavoritesIfEmpty(context: Context, favorites: List<TvShow>): List<Pin> =
        synchronized(lock) {
            if (prefs(context).contains(prefKey())) {
                return listUnlocked(context)
            }
            val seeded = favorites
                .filter { LiveHubCatalog.isLiveFavorite(it) }
                .distinctBy { LiveHubCatalog.identityKey(it) }
                .take(MAX_PINS)
                .map {
                    Pin(
                        id = it.id,
                        provider = it.providerName.orEmpty(),
                        title = it.title.ifBlank { it.id },
                        poster = it.poster,
                    )
                }
                .filter { it.provider.isNotBlank() && it.id.isNotBlank() }
            if (seeded.isNotEmpty()) {
                writeUnlocked(context, seeded)
                return seeded
            }
            // Leave key absent when there is nothing to seed so a later favorites
            // load can still populate on first run.
            return emptyList()
        }

    fun clear(context: Context) = synchronized(lock) {
        writeUnlocked(context, emptyList())
    }

    /** Ordered shows for the strip — pins first, enrich from [catalog] when present. */
    fun resolveShows(context: Context, catalog: List<TvShow> = emptyList()): List<TvShow> {
        val pins = list(context)
        if (pins.isEmpty()) return emptyList()
        val byKey = catalog.associateBy { LiveHubCatalog.identityKey(it) }
        return pins.map { pin ->
            byKey[pin.key]?.also { show ->
                show.isFavorite = true
                if (show.poster.isNullOrBlank()) show.poster = pin.poster
            } ?: pin.toTvShow()
        }
    }

    private fun listUnlocked(context: Context): List<Pin> {
        val raw = prefs(context).getString(prefKey(), null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching { parse(raw) }.getOrDefault(emptyList())
    }

    private fun writeUnlocked(context: Context, pins: List<Pin>) {
        val arr = JSONArray()
        pins.forEach { pin ->
            arr.put(
                JSONObject()
                    .put("id", pin.id)
                    .put("provider", pin.provider)
                    .put("title", pin.title)
                    .put("poster", pin.poster ?: ""),
            )
        }
        prefs(context).edit { putString(prefKey(), arr.toString()) }
    }

    private fun parse(raw: String): List<Pin> {
        val arr = JSONArray(raw)
        val out = ArrayList<Pin>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            val provider = o.optString("provider").trim()
            if (id.isEmpty() || provider.isEmpty()) continue
            out += Pin(
                id = id,
                provider = provider,
                title = o.optString("title").ifBlank { id },
                poster = o.optString("poster").takeIf { it.isNotBlank() },
            )
        }
        return out.take(MAX_PINS)
    }

    private fun prefKey(): String = ProfileManager.scopedPrefKey(KEY_PINS)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
