package com.betterstreamflix.app.player

import android.content.Context
import androidx.core.content.edit
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.utils.DeviceCapabilities
import com.betterstreamflix.app.utils.ProviderAudioLanguage

/**
 * Per-series audio language memory (Netflix-style “always DE”).
 * Keys are profile-scoped so Kids / adult profiles do not share picks.
 */
object SeriesAudioPreference {

    private const val PREFS = "series_audio_prefs"
    private const val KEY_PREFIX = "SERIES_AUDIO_"

    fun get(context: Context, seriesId: String): String? {
        val id = seriesId.trim()
        if (id.isEmpty()) return null
        return prefs(context).getString(prefKey(id), null)
            ?.let { canonical(it) }
    }

    fun set(context: Context, seriesId: String, language: String) {
        val id = seriesId.trim()
        val code = canonical(language) ?: return
        if (id.isEmpty()) return
        prefs(context).edit { putString(prefKey(id), code) }
    }

    fun clear(context: Context, seriesId: String) {
        val id = seriesId.trim()
        if (id.isEmpty()) return
        prefs(context).edit { remove(prefKey(id)) }
    }

    /** ISO-639-1 style code used for storage (`de`, `en`, …). */
    fun canonical(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val token = raw.trim().lowercase().substringBefore('-').substringBefore('_')
        if (token.isEmpty() || token == "und" || token == "unknown") return null
        return when (token) {
            "deu", "ger" -> "de"
            "eng" -> "en"
            "fra", "fre" -> "fr"
            "spa", "esp" -> "es"
            "ita" -> "it"
            "por" -> "pt"
            "jpn" -> "ja"
            "kor" -> "ko"
            "zho", "chi", "cmn" -> "zh"
            "ara" -> "ar"
            "pol" -> "pl"
            "nld", "dut" -> "nl"
            "tur" -> "tr"
            "rus" -> "ru"
            else -> ProviderAudioLanguage.normalizeTmdbLanguage(token).ifBlank { null }
        }
    }

    fun matches(preferred: String?, trackLanguage: String?): Boolean {
        val want = canonical(preferred) ?: return false
        val have = canonical(trackLanguage) ?: return false
        if (want == have) return true
        val wantCodes = DeviceCapabilities.preferredAudioLanguages(want).map { canonical(it) }.toSet()
        val haveCodes = DeviceCapabilities.preferredAudioLanguages(have).map { canonical(it) }.toSet()
        return wantCodes.intersect(haveCodes).isNotEmpty()
    }

    /** ExoPlayer preferred-language tags for [canonical] code. */
    fun exoCodes(language: String): Array<String> {
        val code = canonical(language) ?: return emptyArray()
        val list = DeviceCapabilities.preferredAudioLanguages(code)
        return if (list.isEmpty()) arrayOf(code) else list.toTypedArray()
    }

    /** Short UI badge: `DE`, `EN`, … */
    fun displayCode(language: String?): String {
        val code = canonical(language) ?: return ""
        return code.uppercase()
    }

    private fun prefKey(seriesId: String): String =
        ProfileManager.scopedPrefKey("$KEY_PREFIX$seriesId")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
