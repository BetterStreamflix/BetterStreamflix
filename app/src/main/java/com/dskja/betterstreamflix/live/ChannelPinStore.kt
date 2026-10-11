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

    fun list(context: Context): List<Pin> {
        val raw = prefs(context).getString(prefKey(), null).orEmpty()
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
        val current = list(context).toMutableList()
        current.removeAll { it.key == pin.key }
        current.add(0, pin)
        while (current.size > MAX_PINS) {
            current.removeAt(current.lastIndex)
        }
        write(context, current)
        return true
    }

    fun unpin(context: Context, show: TvShow): Boolean =
        unpinKey(context, LiveHubCatalog.identityKey(show))

    fun unpinKey(context: Context, key: String): Boolean {
        val current = list(context)
        val next = current.filterNot { it.key == key }
        if (next.size == current.size) return false
        write(context, next)
        return true
    }

    fun toggle(context: Context, show: TvShow): Boolean {
        return if (isPinned(context, show)) {
            unpin(context, show)
            false
        } else {
            pin(context, show)
            true
        }
    }

    /**
     * When the strip is empty, seed from live favorites so first-run feels populated.
     * Once the prefs key exists (including intentional empty `[]` after unpin-all),
     * never reseed — unpin-all must stick.
     */
    fun seedFromFavoritesIfEmpty(context: Context, favorites: List<TvShow>): List<Pin> {
        val prefs = prefs(context)
        if (prefs.contains(prefKey())) {
            return list(context)
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
            write(context, seeded)
        }
        return seeded
    }

    fun clear(context: Context) {
        write(context, emptyList())
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

    private fun write(context: Context, pins: List<Pin>) {
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
