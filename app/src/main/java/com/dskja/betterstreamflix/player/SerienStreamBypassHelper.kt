package com.dskja.betterstreamflix.player

import android.net.Uri
import android.webkit.CookieManager
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.SerienStreamEndpoints
import com.dskja.betterstreamflix.providers.SerienStreamProvider
import com.dskja.betterstreamflix.providers.TmdbProvider
import com.dskja.betterstreamflix.utils.UserPreferences
import java.util.Locale

/** Shared SerienStream / CF bypass helpers used by mobile WebView and TV QR paths. */
object SerienStreamBypassHelper {

    private const val TMDB_DE_SERIENSTREAM = "tmdbde:serienstream:"

    /** Cookie names that are only browser noise (DuckDuckGo etc.), never a SerienStream login. */
    private val NOISE_COOKIE_NAMES = setOf(
        "__ddg1", "__ddg2", "__ddg3", "__ddg4", "__ddg5",
        "__ddg6", "__ddg7", "__ddg8", "__ddg9", "__ddg10",
        "__ddgid", "__ddgmark", "__ddg_privacy",
    )

    /**
     * Cookie names that indicate a Cloudflare / anti-bot challenge was solved.
     * These alone are NOT an account login — anonymous visitors get PHPSESSID too.
     */
    private val BYPASS_COOKIE_NAME_EXACT = setOf(
        "cf_clearance",
        "ddos_token",
        "altcha",
        "phpsessid",
        "laravel_session",
        "xsrf-token",
        "ci_session",
    )

    /** Cookie names that prove a real SerienStream account sign-in. */
    private val ACCOUNT_COOKIE_NAME_EXACT = setOf(
        "rememberlogin",
        "remember_login",
        "remember_me",
        "rememberme",
        "logged_in",
        "is_logged_in",
        "user_id",
        "userid",
        "serien_user",
        "ss_auth",
    )
    private val ACCOUNT_COOKIE_NAME_PREFIXES = listOf(
        "remember_",
        "remember-",
        "serien_user",
        "login_",
        "auth_",
        "user_",
        "account_",
    )

    /** @deprecated Prefer [BYPASS_COOKIE_NAME_EXACT] / [ACCOUNT_COOKIE_NAME_EXACT]. */
    private val AUTH_COOKIE_NAME_EXACT = BYPASS_COOKIE_NAME_EXACT + ACCOUNT_COOKIE_NAME_EXACT
    private val AUTH_COOKIE_NAME_PREFIXES = ACCOUNT_COOKIE_NAME_PREFIXES

    fun isSerienStreamHost(url: String): Boolean {
        if (url.startsWith(TMDB_DE_SERIENSTREAM, ignoreCase = true)) return true
        if (url.contains("tmdbde:serienstream:", ignoreCase = true)) return true
        return SerienStreamProvider.isSerienStreamHost(url)
    }

    /**
     * Build a SerienStream episode page URL for CF bypass.
     * Works when the current provider is SerienStream OR when TMDb DE routed a
     * `tmdbde:serienstream:…` server (title search bridge).
     */
    fun buildEpisodeBypassUrl(
        videoType: Video.Type,
        servers: List<Video.Server> = emptyList(),
    ): String? {
        val base = SerienStreamProvider.baseUrl.trimEnd('/') + "/"

        val routed = servers.firstOrNull {
            it.id.startsWith(TMDB_DE_SERIENSTREAM, ignoreCase = true) ||
                it.src.contains("serienstream", ignoreCase = true) ||
                SerienStreamProvider.isSerienStreamHost(it.src) ||
                SerienStreamProvider.isSerienStreamHost(it.id)
        }
        if (routed != null) {
            val episodePath = when {
                routed.id.startsWith(TMDB_DE_SERIENSTREAM, ignoreCase = true) ->
                    routed.id.removePrefix(TMDB_DE_SERIENSTREAM).removePrefix("tmdbde:serienstream:")
                else -> null
            }
            if (!episodePath.isNullOrBlank() && !episodePath.startsWith("http", ignoreCase = true)) {
                return "${base}serie/${episodePath.trimStart('/')}"
            }
            val src = routed.src
            if (SerienStreamProvider.isSerienStreamHost(src) && src.contains("/serie/")) {
                return src.substringBefore('?')
            }
        }

        val provider = UserPreferences.currentProvider
        val allowed = provider == SerienStreamProvider ||
            (provider is TmdbProvider && provider.language.lowercase().startsWith("de"))
        if (!allowed) return null

        val episodeId = when (videoType) {
            is Video.Type.Episode -> videoType.id
            is Video.Type.Movie -> return null
        }
        if (provider != SerienStreamProvider) return null
        return "${base}serie/$episodeId"
    }

    fun applyCookies(url: String, cookieHeader: String) {
        val cleaned = sanitizeSessionCookies(cookieHeader)
        val parts = mutableListOf<String>()
        if (cleaned.isNotBlank()) parts += cleaned
        val stored = sanitizeSessionCookies(UserPreferences.serienStreamSessionCookies)
        if (stored.isNotBlank() && stored != cleaned) parts += stored
        seedCookieHeader(url, parts.joinToString("; "))
    }

    /** Apply only the user session cookies (TV / VPN path without QR). */
    fun applyStoredSessionCookies(url: String = SerienStreamProvider.baseUrl) {
        seedCookieHeader(url, sanitizeSessionCookies(UserPreferences.serienStreamSessionCookies))
    }

    /**
     * Keep only SerienStream/CF/session cookies. Drops DuckDuckGo `__ddg*` noise that
     * previously made Settings show "Cookies saved" without a real login.
     */
    fun sanitizeSessionCookies(cookieHeader: String): String {
        if (cookieHeader.isBlank()) return ""
        val byName = linkedMapOf<String, String>()
        cookieHeader.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .forEach { cookie ->
                val name = cookie.substringBefore("=").trim().lowercase(Locale.US)
                if (name.isBlank()) return@forEach
                if (NOISE_COOKIE_NAMES.any { noise ->
                        name == noise || name.startsWith("${noise}_") || name.startsWith(noise)
                    }
                ) {
                    return@forEach
                }
                byName[name] = cookie
            }
        return byName.values.joinToString("; ")
    }

    /** True when the jar contains a CF / challenge cookie (not just browser noise). */
    fun looksLikeBypassSolved(cookieHeader: String): Boolean {
        val cleaned = sanitizeSessionCookies(cookieHeader)
        if (cleaned.isBlank()) return false
        val names = cookieNames(cleaned)
        return names.any { name ->
            name in BYPASS_COOKIE_NAME_EXACT || name in AUTH_COOKIE_NAME_EXACT
        }
    }

    /**
     * Persist for CF bypass playback. Account sign-in must use
     * [persistAccountSessionCookiesIfValid] so anonymous PHPSESSID never
     * pretends to be a saved login.
     */
    fun persistSessionCookiesIfValid(cookieHeader: String): Boolean {
        val cleaned = sanitizeSessionCookies(cookieHeader)
        if (cleaned.isBlank()) return false
        if (!looksLikeBypassSolved(cleaned) && !looksLikeAccountSession(cleaned)) {
            return false
        }
        UserPreferences.serienStreamSessionCookies = cleaned
        applyStoredSessionCookies()
        return true
    }

    /** Persist only when cookies prove a real account login (Settings Sign-in). */
    fun persistAccountSessionCookiesIfValid(cookieHeader: String): Boolean {
        val cleaned = sanitizeSessionCookies(cookieHeader)
        if (cleaned.isBlank()) return false
        if (!looksLikeAccountSession(cleaned)) return false
        UserPreferences.serienStreamSessionCookies = cleaned
        applyStoredSessionCookies()
        return true
    }

    /**
     * Strict account-login check. Anonymous CF/PHPSESSID jars must return false —
     * that was the false "Login gespeichert" bug after opening the warm homepage.
     */
    fun looksLikeAccountSession(cookieHeader: String): Boolean {
        val cleaned = sanitizeSessionCookies(cookieHeader)
        if (cleaned.isBlank()) return false
        val names = cookieNames(cleaned)
        if (names.any { it in ACCOUNT_COOKIE_NAME_EXACT }) return true
        if (names.any { name -> ACCOUNT_COOKIE_NAME_PREFIXES.any { name.startsWith(it) } }) {
            return true
        }
        // Laravel remember cookie variants sometimes use long opaque names with "remember".
        val lower = cleaned.lowercase(Locale.US)
        if (lower.contains("remember_web_") || lower.contains("remember_token")) return true
        return false
    }

    private fun cookieNames(cleaned: String): List<String> =
        cleaned.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .map { it.substringBefore("=").trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }

    fun clearStoredSessionCookies() {
        // Full logout: prefs + CookieManager across SerienStream / proxy origins.
        com.dskja.betterstreamflix.providers.SerienStreamAuthManager.logout()
    }

    private fun seedCookieHeader(url: String, cookieHeader: String) {
        val cleaned = sanitizeSessionCookies(cookieHeader)
        if (cleaned.isBlank()) return
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        val targets = linkedSetOf<String>().apply {
            if (url.isNotBlank()) add(url)
            if (host.isNotBlank()) {
                add("https://$host/")
                add("http://$host/")
            }
            add("https://serienstream.to/")
            add("https://serienstream.cx/")
            SerienStreamProvider.candidateDomains().forEach { domain ->
                add(SerienStreamEndpoints.originFor(domain))
            }
            runCatching {
                add(SerienStreamProvider.baseUrl.trimEnd('/') + "/")
            }
            // Always seed the official proxy origin (HTTP) alongside hostname mirrors.
            add(SerienStreamEndpoints.originFor(SerienStreamEndpoints.PROXY_HOST))
        }
        val cookieManager = CookieManager.getInstance()
        val byName = linkedMapOf<String, String>()
        cleaned.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .forEach { cookie ->
                val name = cookie.substringBefore("=").trim().lowercase(Locale.US)
                if (name.isNotBlank()) byName[name] = cookie
            }
        byName.values.forEach { cookie ->
            targets.forEach { target ->
                runCatching { cookieManager.setCookie(target, cookie) }
            }
        }
        runCatching { cookieManager.flush() }
    }
}
