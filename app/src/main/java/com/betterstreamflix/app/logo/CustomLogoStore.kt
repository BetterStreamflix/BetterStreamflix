package com.betterstreamflix.app.logo

import android.content.Context
import android.content.SharedPreferences

/**
 * User-supplied custom title-logo overrides (LogoSource.USER).
 *
 * Keys: `m|<movieId>` / `t|<tvShowId>` → logo URL. A present override wins over
 * provider/TMDb/Fanart resolution in TitleLogoSurface until removed.
 */
object CustomLogoStore {

    private const val PREFS = "custom_title_logos_v1"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun key(isTv: Boolean, id: String): String =
        (if (isTv) "t|" else "m|") + id.trim()

    private fun keyFor(isTv: Boolean, id: String?, title: String): String =
        if (!id.isNullOrBlank()) key(isTv, id)
        else (if (isTv) "t|" else "m|") + "title:" + title.trim()

    fun get(isTv: Boolean, id: String?): String? {
        if (id.isNullOrBlank()) return null
        return prefs?.getString(key(isTv, id), null)
            ?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Title-keyed variant for surfaces that carry no stable entity id. */
    fun getByTitle(isTv: Boolean, title: String?): String? {
        if (title.isNullOrBlank()) return null
        return prefs?.getString(keyFor(isTv, null, title), null)
            ?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Returns the stored URL, or null when [url] was blank (treated as clear). */
    fun set(isTv: Boolean, id: String, url: String?): String? {
        val p = prefs ?: return null
        val k = key(isTv, id)
        val clean = url?.trim()?.takeIf { it.isNotEmpty() }
        if (clean == null) {
            p.edit().remove(k).apply()
            return null
        }
        p.edit().putString(k, clean).apply()
        return clean
    }

    fun clear(isTv: Boolean, id: String?) {
        if (id.isNullOrBlank()) return
        prefs?.edit()?.remove(key(isTv, id))?.apply()
    }

    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }
}
