package com.betterstreamflix.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Facade over the settings storage layer for the new shell.
 *
 * XML-driven preference rows persist into the platform default
 * SharedPreferences file; this object adds the shell-level capabilities the
 * old flat screens never had:
 *
 *  - observable change events ([changes]) so rows/cards live-update when a
 *    value changes elsewhere (provider switch, profile swap, sync import)
 *  - a "recently touched" trail ([recentKeys]) powering the Recents strip
 *  - changed-from-default detection ([isUserModified]) and per-key reset
 *    ([resetToDefault]) — removing the persisted entry restores whatever
 *    `android:defaultValue` the row declares
 *  - a compact settings-only export/import JSON ([exportJson]/[importJson])
 *    distinct from the full BackupRestoreManager archives
 */
object SettingsRepository {

    private const val SHELL_PREFS = "settings_shell_v1"
    private const val KEY_RECENTS = "recent_touched_rows"
    private const val MAX_RECENTS = 5
    private const val EXPORT_VERSION = 1

    private lateinit var defaultPrefs: SharedPreferences
    private lateinit var shellPrefs: SharedPreferences

    private val _changes = MutableStateFlow("")
    val changes: StateFlow<String> = _changes

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) _changes.value = key
        }

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        defaultPrefs = PreferenceManager.getDefaultSharedPreferences(app)
        shellPrefs = app.getSharedPreferences(SHELL_PREFS, Context.MODE_PRIVATE)
        defaultPrefs.registerOnSharedPreferenceChangeListener(listener)
        initialized = true
    }

    // ---- Recents --------------------------------------------------------

    fun recentKeys(): List<String> {
        if (!initialized) return emptyList()
        return runCatching {
            val arr = JSONArray(shellPrefs.getString(KEY_RECENTS, "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    /** Record that the user interacted with [key] (row click, search pick). */
    fun markTouched(key: String) {
        if (!initialized || key.isBlank()) return
        val next = (listOf(key) + recentKeys().filter { it != key }).take(MAX_RECENTS)
        shellPrefs.edit().putString(KEY_RECENTS, JSONArray(next).toString()).apply()
    }

    // ---- Changed-from-default / reset -----------------------------------

    /** true when the row has a persisted value, i.e. differs from XML default. */
    fun isUserModified(key: String): Boolean =
        initialized && defaultPrefs.contains(key)

    /**
     * Restore a row to its declared `android:defaultValue` by dropping the
     * persisted entry. The row re-reads its default on next bind.
     */
    fun resetToDefault(key: String) {
        if (!initialized) return
        defaultPrefs.edit().remove(key).apply()
        _changes.value = key
    }

    /** Reset every [keys] entry (section-level reset). Returns count reset. */
    fun resetSection(keys: Collection<String>): Int {
        if (!initialized) return 0
        val editor = defaultPrefs.edit()
        var count = 0
        keys.forEach { key ->
            if (defaultPrefs.contains(key)) {
                editor.remove(key)
                count++
            }
        }
        editor.apply()
        _changes.value = "section_reset"
        return count
    }

    // ---- Settings-only export / import ----------------------------------

    /** Snapshot of every persisted row value plus metadata. */
    fun exportJson(context: Context): JSONObject {
        init(context)
        val values = JSONObject()
        defaultPrefs.all.forEach { (key, value) -> values.put(key, value) }
        return JSONObject()
            .put("kind", "betterstreamflix.settings")
            .put("version", EXPORT_VERSION)
            .put("device", android.os.Build.MODEL ?: "")
            .put("exportedAt", System.currentTimeMillis())
            .put("values", values)
    }

    /**
     * Preview of an import: how many keys would be written, how many change
     * an existing value, how many are new.
     */
    data class ImportPreview(
        val total: Int,
        val changed: Int,
        val added: Int,
    )

    fun previewImport(context: Context, json: JSONObject): ImportPreview? {
        init(context)
        val values = json.optJSONObject("values") ?: return null
        var changed = 0
        var added = 0
        var total = 0
        values.keys().forEach { key ->
            total++
            val incoming = values.opt(key)
            if (defaultPrefs.contains(key)) {
                if (defaultPrefs.all[key] != incoming) changed++
            } else {
                added++
            }
        }
        return ImportPreview(total, changed, added)
    }

    /** Write all values from a previously exported [json]. Returns count. */
    fun importJson(context: Context, json: JSONObject): Int {
        init(context)
        val values = json.optJSONObject("values") ?: return 0
        val editor = defaultPrefs.edit()
        var count = 0
        values.keys().forEach { key ->
            when (val v = values.opt(key)) {
                is Boolean -> editor.putBoolean(key, v)
                is Int -> editor.putInt(key, v)
                is Long -> editor.putLong(key, v)
                is Float -> editor.putFloat(key, v)
                is String -> editor.putString(key, v)
                else -> return@forEach
            }
            count++
        }
        editor.apply()
        _changes.value = "import"
        return count
    }
}
