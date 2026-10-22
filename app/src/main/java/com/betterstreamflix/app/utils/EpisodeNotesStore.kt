package com.betterstreamflix.app.utils

import android.content.Context
import androidx.core.content.edit
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.sync.CloudSyncHooks
import org.json.JSONObject

/**
 * Short spoiler-free “where was I?” notes per episode.
 * Stored locally and keyed by active profile so Kids / adult profiles never share notes.
 *
 * Value shape is JSON `{text, updatedAtMillis}` for conflict-safe cloud sync.
 * Plain-string legacy values are dual-read and migrated on first clocked access/write.
 */
object EpisodeNotesStore {

    const val MAX_CHARS: Int = 160
    const val PREVIEW_CHARS: Int = 56

    private const val PREFS = "episode_notes_prefs"
    private const val KEY_PREFIX = "EPISODE_NOTE_"
    private const val JSON_TEXT = "text"
    private const val JSON_UPDATED = "updatedAtMillis"
    private const val JSON_PROVIDER = "providerName"
    private const val JSON_SHOW = "showId"

    data class NoteRecord(
        val text: String,
        val updatedAtMillis: Long,
        val providerName: String = "",
        val showId: String = "",
    )

    fun get(context: Context, episodeId: String): String? =
        getRecord(context, episodeId)?.text?.takeIf { it.isNotBlank() }

    fun getRecord(context: Context, episodeId: String): NoteRecord? {
        val id = episodeId.trim()
        if (id.isEmpty()) return null
        val raw = prefs(context).getString(prefKey(id), null) ?: return null
        val decoded = decode(raw) ?: return null
        // Lazy-migrate plain strings so cloud sync has a clock (local primary).
        if (!isJsonEnvelope(raw) && decoded.text.isNotBlank()) {
            val stamped = decoded.copy(updatedAtMillis = System.currentTimeMillis())
            writeRecord(context, id, stamped, enqueueCloud = false)
            return stamped
        }
        return decoded
    }

    fun has(context: Context, episodeId: String): Boolean =
        !get(context, episodeId).isNullOrBlank()

    /**
     * Saves a sanitized note. Empty / whitespace clears the note (tombstone with clock).
     * @return the stored text, or null when cleared.
     */
    fun set(
        context: Context,
        episodeId: String,
        raw: String?,
        providerName: String = "",
        showId: String = "",
    ): String? {
        val id = episodeId.trim()
        if (id.isEmpty()) return null
        val cleaned = sanitize(raw)
        val now = System.currentTimeMillis()
        val existing = getRecord(context, id)
        val provider = providerName.trim().ifEmpty { existing?.providerName.orEmpty() }
        val show = showId.trim().ifEmpty { existing?.showId.orEmpty() }
        if (cleaned.isEmpty()) {
            clear(context, id, providerName = provider, showId = show)
            return null
        }
        writeRecord(
            context,
            id,
            NoteRecord(cleaned, now, provider, show),
            enqueueCloud = true,
        )
        return cleaned
    }

    /**
     * Clears note text but keeps a tombstone clock so clears propagate via cloud LWW.
     */
    fun clear(
        context: Context,
        episodeId: String,
        providerName: String = "",
        showId: String = "",
    ) {
        val id = episodeId.trim()
        if (id.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = getRecord(context, id)
        writeRecord(
            context,
            id,
            NoteRecord(
                text = "",
                updatedAtMillis = now,
                providerName = providerName.trim().ifEmpty { existing?.providerName.orEmpty() },
                showId = showId.trim().ifEmpty { existing?.showId.orEmpty() },
            ),
            enqueueCloud = true,
        )
    }

    /** Apply a remote note (or tombstone) without enqueueing a cloud mutation. */
    fun applyRemote(
        context: Context,
        episodeId: String,
        text: String,
        updatedAtMillis: Long,
        providerName: String = "",
        showId: String = "",
    ) {
        val id = episodeId.trim()
        if (id.isEmpty()) return
        val cleaned = sanitize(text)
        val existing = getRecord(context, id)
        writeRecord(
            context,
            id,
            NoteRecord(
                text = cleaned,
                updatedAtMillis = updatedAtMillis.coerceAtLeast(0L),
                providerName = providerName.trim().ifEmpty { existing?.providerName.orEmpty() },
                showId = showId.trim().ifEmpty { existing?.showId.orEmpty() },
            ),
            enqueueCloud = false,
        )
    }

    fun preview(text: String?, maxChars: Int = PREVIEW_CHARS): String {
        val cleaned = text?.trim().orEmpty()
        if (cleaned.isEmpty()) return ""
        if (cleaned.length <= maxChars) return cleaned
        return cleaned.take(maxChars.coerceAtLeast(1)).trimEnd() + "…"
    }

    fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val collapsed = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (collapsed.length <= MAX_CHARS) collapsed else collapsed.take(MAX_CHARS).trimEnd()
    }

    /** Drop every note for [profileId] (profile delete / wipe). */
    fun wipeProfile(context: Context, profileId: String) {
        val prefs = prefs(context)
        val suffix = "_p_$profileId"
        val keys = prefs.all.keys.filter { it.endsWith(suffix) }
        if (keys.isEmpty()) return
        prefs.edit {
            keys.forEach { remove(it) }
        }
    }

    /**
     * All notes for the active profile (episodeId → text).
     * Used by profile export — never leaks other profiles' notes.
     * Skips tombstones (empty text).
     */
    fun listForActiveProfile(context: Context): List<Pair<String, String>> =
        listRecordsForActiveProfile(context)
            .filter { it.second.text.isNotBlank() }
            .map { it.first to it.second.text }

    /**
     * All note records (including empty tombstones) for the active profile.
     */
    fun listRecordsForActiveProfile(context: Context): List<Pair<String, NoteRecord>> {
        val profileId = ProfileManager.activeProfileId
        val isDefault = profileId == ProfileManager.DEFAULT_PROFILE_ID
        val suffix = "_p_$profileId"
        val prefs = prefs(context)
        return prefs.all.mapNotNull { (key, value) ->
            val raw = value as? String ?: return@mapNotNull null
            if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
            val rest = key.removePrefix(KEY_PREFIX)
            val episodeId = if (isDefault) {
                if (rest.contains("_p_")) return@mapNotNull null
                rest
            } else {
                if (!rest.endsWith(suffix)) return@mapNotNull null
                rest.removeSuffix(suffix)
            }
            if (episodeId.isBlank()) return@mapNotNull null
            var record = decode(raw) ?: return@mapNotNull null
            // Stamp legacy plain strings before cloud collect.
            if (!isJsonEnvelope(raw) && record.text.isNotBlank()) {
                record = record.copy(updatedAtMillis = System.currentTimeMillis())
                writeRecord(context, episodeId, record, enqueueCloud = false)
            }
            episodeId to record
        }.sortedBy { it.first }
    }

    internal fun encode(record: NoteRecord): String =
        JSONObject()
            .put(JSON_TEXT, record.text)
            .put(JSON_UPDATED, record.updatedAtMillis)
            .put(JSON_PROVIDER, record.providerName)
            .put(JSON_SHOW, record.showId)
            .toString()

    internal fun decode(raw: String?): NoteRecord? {
        if (raw.isNullOrBlank()) return null
        if (isJsonEnvelope(raw)) {
            return runCatching {
                val obj = JSONObject(raw)
                NoteRecord(
                    text = obj.optString(JSON_TEXT, ""),
                    updatedAtMillis = obj.optLong(JSON_UPDATED, 0L),
                    providerName = obj.optString(JSON_PROVIDER, ""),
                    showId = obj.optString(JSON_SHOW, ""),
                )
            }.getOrNull()
        }
        // Legacy plain string.
        return NoteRecord(text = raw, updatedAtMillis = 0L)
    }

    internal fun isJsonEnvelope(raw: String): Boolean {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
        return runCatching {
            val obj = JSONObject(trimmed)
            obj.has(JSON_TEXT) && obj.has(JSON_UPDATED)
        }.getOrDefault(false)
    }

    private fun writeRecord(
        context: Context,
        episodeId: String,
        record: NoteRecord,
        enqueueCloud: Boolean,
    ) {
        prefs(context).edit { putString(prefKey(episodeId), encode(record)) }
        if (enqueueCloud) {
            CloudSyncHooks.episodeNote(
                context,
                episodeId = episodeId,
                text = record.text,
                updatedAtMillis = record.updatedAtMillis,
                providerName = record.providerName,
                showId = record.showId,
            )
        }
    }

    private fun prefKey(episodeId: String): String =
        ProfileManager.scopedPrefKey("$KEY_PREFIX$episodeId")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
