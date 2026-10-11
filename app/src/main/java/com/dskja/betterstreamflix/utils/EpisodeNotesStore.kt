package com.dskja.betterstreamflix.utils

import android.content.Context
import androidx.core.content.edit
import com.dskja.betterstreamflix.profiles.ProfileManager

/**
 * Short spoiler-free “where was I?” notes per episode.
 * Stored locally and keyed by active profile so Kids / adult profiles never share notes.
 */
object EpisodeNotesStore {

    const val MAX_CHARS: Int = 160
    const val PREVIEW_CHARS: Int = 56

    private const val PREFS = "episode_notes_prefs"
    private const val KEY_PREFIX = "EPISODE_NOTE_"

    fun get(context: Context, episodeId: String): String? {
        val id = episodeId.trim()
        if (id.isEmpty()) return null
        return prefs(context).getString(prefKey(id), null)
            ?.takeIf { it.isNotBlank() }
    }

    fun has(context: Context, episodeId: String): Boolean =
        !get(context, episodeId).isNullOrBlank()

    /**
     * Saves a sanitized note. Empty / whitespace clears the note.
     * @return the stored text, or null when cleared.
     */
    fun set(context: Context, episodeId: String, raw: String?): String? {
        val id = episodeId.trim()
        if (id.isEmpty()) return null
        val cleaned = sanitize(raw)
        if (cleaned.isEmpty()) {
            clear(context, id)
            return null
        }
        prefs(context).edit { putString(prefKey(id), cleaned) }
        return cleaned
    }

    fun clear(context: Context, episodeId: String) {
        val id = episodeId.trim()
        if (id.isEmpty()) return
        prefs(context).edit { remove(prefKey(id)) }
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
     */
    fun listForActiveProfile(context: Context): List<Pair<String, String>> {
        val profileId = ProfileManager.activeProfileId
        val isDefault = profileId == ProfileManager.DEFAULT_PROFILE_ID
        val suffix = "_p_$profileId"
        return prefs(context).all.mapNotNull { (key, value) ->
            val text = (value as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
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
            episodeId to text
        }.sortedBy { it.first }
    }

    private fun prefKey(episodeId: String): String =
        ProfileManager.scopedPrefKey("$KEY_PREFIX$episodeId")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
