package com.dskja.betterstreamflix.player

import android.net.Uri
import android.webkit.CookieManager
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.SerienStreamEndpoints
import com.dskja.betterstreamflix.providers.SerienStreamProvider
import com.dskja.betterstreamflix.providers.TmdbProvider
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.watchlist.WatchlistImporter
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

    /** Real anti-bot clearance — required to skip the interactive CF WebView/QR. */
    private val CLEARANCE_COOKIE_NAME_EXACT = setOf(
        "cf_clearance",
        "ddos_token",
        "altcha",
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
        seedCookiesToManager(url, parts.joinToString("; "))
    }

    /**
     * Seed an arbitrary cookie jar into CookieManager for [url] (and related
     * SerienStream/AniWorld origins). Does **not** merge SerienStream prefs —
     * use this for AniWorld and for explicit one-shot jars.
     */
    fun seedCookiesToManager(url: String, cookieHeader: String) {
        seedCookieHeader(url, sanitizeSessionCookies(cookieHeader))
    }

    /** Apply only the user session cookies (TV / VPN path without QR). */
    fun applyStoredSessionCookies(url: String = SerienStreamProvider.baseUrl) {
        seedCookieHeader(url, sanitizeSessionCookies(UserPreferences.serienStreamSessionCookies))
    }

    /**
     * True when interactive CF/ALTCHA WebView/QR must not be shown.
     *
     * Real anti-bot clearance **or** a confirmed account login/session token
     * both suppress captcha. Bare anonymous `laravel_session` / `PHPSESSID`
     * alone still require the interactive bypass.
     */
    fun canSkipInteractiveBypass(
        cookieHeader: String,
        accountConfirmed: Boolean = false,
    ): Boolean {
        val cleaned = sanitizeSessionCookies(cookieHeader)
        if (cleaned.isBlank()) return false
        if (looksLikeClearanceSolved(cleaned)) return true
        if (looksLikeAccountSession(cleaned)) return true
        return accountConfirmed && hasWebSessionCookie(cleaned)
    }

    /**
     * The episode `/r?t=` bridge still requires Turnstile/ALTCHA together with
     * the stream token. A login jar is forwarded into that form; it does not
     * replace it. Only a solved clearance cookie may skip the interactive gate.
     */
    fun shouldSkipStreamCaptcha(cookieHeader: String): Boolean {
        val cleaned = sanitizeSessionCookies(cookieHeader)
        if (cleaned.isBlank()) return false
        return looksLikeClearanceSolved(cleaned)
    }

    /** SerienStream iframe bridge that posts `t` back to the captcha form. */
    fun looksLikeStreamTokenGate(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase(Locale.US)
        return lower.contains("framebridge") ||
            lower.contains("player-prepare-token") ||
            lower.contains("episode-redirect-gate")
    }

    /**
     * Stream token carried by `/r?t=` or the bridge page (`var t = "..."`).
     * The captcha form submits this value; an empty `t` always fails.
     */
    fun extractStreamToken(pageOrUrl: String): String? {
        if (pageOrUrl.isBlank()) return null
        val fromQuery = Regex("""[?&]t=([^&"'#\s]+)""", RegexOption.IGNORE_CASE)
            .find(pageOrUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { raw ->
                runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)
            }
            ?.trim()
            ?.takeIf { it.length >= 8 }
        if (!fromQuery.isNullOrBlank()) return fromQuery
        return Regex("""\bt\s*=\s*["']([^"']{8,})["']""")
            .find(pageOrUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Fill `#player-prepare-token` from the first host button before the user
     * submits Turnstile/ALTCHA. Showing the modal alone posts an empty `t`.
     */
    fun playerGateAssistJs(): String = """
        (function(){
          try {
            var btn = document.querySelector('button.link-box[data-play-url], a.link-box[data-play-url]');
            var token = '';
            if (btn) {
              var play = btn.getAttribute('data-play-url') || '';
              var match = play.match(/[?&]t=([^&]+)/);
              if (match) {
                try { token = decodeURIComponent(match[1]); } catch (e) { token = match[1]; }
              }
              try { btn.click(); } catch (e) {}
            }
            var input = document.getElementById('player-prepare-token');
            if (input && token && !input.value) input.value = token;
            var modal = document.querySelector('#playerPrepareModal');
            if (modal) {
              modal.classList.add('show');
              modal.style.display = 'block';
              modal.removeAttribute('aria-hidden');
              document.body.classList.add('modal-open');
            }
          } catch (e) {}
        })();
    """.trimIndent()

    /**
     * Merge [storedHeader] into an outgoing Cookie header. Later names win.
     * Used by OkHttp interceptors so prefs survive CookieManager gaps.
     */
    fun mergeOutgoingCookieHeader(existingHeader: String?, storedHeader: String): String {
        return mergeCookieHeaders(existingHeader.orEmpty(), storedHeader)
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
     * True when a real anti-bot clearance cookie is present.
     * Bare `laravel_session` / `PHPSESSID` must NOT skip the interactive CF bypass.
     */
    fun looksLikeClearanceSolved(cookieHeader: String): Boolean {
        val names = cookieNames(sanitizeSessionCookies(cookieHeader))
        return names.any { it in CLEARANCE_COOKIE_NAME_EXACT }
    }

    /**
     * Merge two cookie jars by name (later values win). Keeps account session
     * cookies when a CF bypass jar only carries clearance tokens.
     */
    fun mergeCookieHeaders(existing: String, incoming: String): String {
        val byName = linkedMapOf<String, String>()
        fun putAll(header: String) {
            sanitizeSessionCookies(header)
                .split(";")
                .map { it.trim() }
                .filter { it.contains("=") }
                .forEach { cookie ->
                    val name = cookie.substringBefore("=").trim().lowercase(Locale.US)
                    if (name.isNotBlank()) byName[name] = cookie
                }
        }
        putAll(existing)
        putAll(incoming)
        return byName.values.joinToString("; ")
    }

    /**
     * Persist for CF bypass playback. Account sign-in must use
     * [persistAccountSessionCookiesIfValid] so anonymous PHPSESSID never
     * pretends to be a saved login.
     *
     * Merges into any existing jar so a player CF bypass cannot wipe a
     * previously confirmed Laravel account session.
     */
    fun persistSessionCookiesIfValid(cookieHeader: String): Boolean {
        val cleaned = sanitizeSessionCookies(cookieHeader)
        if (cleaned.isBlank()) return false
        if (!looksLikeBypassSolved(cleaned) && !looksLikeAccountSession(cleaned)) {
            return false
        }
        val merged = mergeCookieHeaders(UserPreferences.serienStreamSessionCookies, cleaned)
        UserPreferences.serienStreamSessionCookies = merged
        applyStoredSessionCookies()
        return true
    }

    /** Persist only when cookies prove a real account login (Settings Sign-in). */
    fun persistAccountSessionCookiesIfValid(
        cookieHeader: String,
        htmlProof: String? = null,
        leftLoginAfterVisit: Boolean = false,
        pageUrl: String? = null,
    ): Boolean {
        val cleaned = sanitizeSessionCookies(cookieHeader)
        if (cleaned.isBlank()) return false
        val ok = looksLikeAccountSession(cleaned) ||
            looksLikeAuthenticatedSession(
                cookieHeader = cleaned,
                html = htmlProof,
                leftLoginAfterVisit = leftLoginAfterVisit,
                pageUrl = pageUrl,
            )
        if (!ok) return false
        val merged = mergeCookieHeaders(UserPreferences.serienStreamSessionCookies, cleaned)
        UserPreferences.serienStreamSessionCookies = merged
        applyStoredSessionCookies()
        return true
    }

    /**
     * Strict account-login check. Anonymous CF/PHPSESSID jars must return false —
     * that was the false "Login gespeichert" bug after opening the warm homepage.
     *
     * SerienStream is Laravel: a successful sign-in often only sets `laravel_session`
     * (+ `XSRF-TOKEN`) unless "remember me" is checked. Callers that have page HTML
     * after leaving `/login` must also use [looksLikeAuthenticatedSession].
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

    /** True when the jar has a Laravel / PHP session cookie (not proof of account alone). */
    fun hasWebSessionCookie(cookieHeader: String): Boolean {
        val names = cookieNames(sanitizeSessionCookies(cookieHeader))
        return names.any {
            it == "laravel_session" ||
                it == "aniworld_session" ||
                it == "phpsessid" ||
                it == "ci_session" ||
                it.endsWith("_session") ||
                it.endsWith("-session")
        }
    }

    /**
     * HTML proof that the user is signed in.
     *
     * SerienStream SSR exposes auth via `#chat-root data-auth="1"` and nav
     * Abmelden / account links. Do **not** reject on bare `href="/login"` —
     * the public footer (and chat labels) keep login URLs even when signed in.
     * The login form itself has no "remember me", so Laravel only sets
     * `laravel_session`; HTML proof is required for that path.
     */
    fun looksLikeLoggedInHtml(html: String): Boolean {
        if (html.isBlank()) return false
        if (WatchlistImporter.looksLikeChallengePage(html)) return false
        val lower = html.lowercase(Locale.US)
        // Strongest SSR signal — check before password-form heuristics so account
        // settings pages (password change) are not misclassified as login.
        if (lower.contains("data-auth=\"1\"") || lower.contains("data-auth='1'")) {
            return true
        }
        if (Regex("""data-user-id=["'](?!["'])[^"']+["']""").containsMatchIn(lower)) {
            return true
        }
        if (WatchlistImporter.looksLikeLoginPage(html)) return false
        val hasLogout =
            lower.contains("href=\"/logout") ||
                lower.contains("href='/logout") ||
                lower.contains(">abmelden<") ||
                lower.contains(">abmelden <") ||
                lower.contains("abmelden</a>") ||
                lower.contains("/logout\"") ||
                lower.contains("/logout'")
        if (hasLogout) return true
        val hasAccountChrome =
            (lower.contains("href=\"/account") || lower.contains("href='/account")) &&
                (
                    lower.contains("mein konto") ||
                        lower.contains("watchlist") ||
                        lower.contains("merkliste") ||
                        lower.contains("einstellungen")
                    )
        return hasAccountChrome
    }

    /**
     * Account cookies **or** (session cookie + logged-in HTML after a real /login visit).
     * Prevents warm-homepage false positives while accepting Laravel sessions without remember-me.
     *
     * [pageUrl] helps when the WebView lands on `/account` after login but HTML
     * markers are still settling.
     */
    fun looksLikeAuthenticatedSession(
        cookieHeader: String,
        html: String? = null,
        leftLoginAfterVisit: Boolean = false,
        pageUrl: String? = null,
    ): Boolean {
        if (looksLikeAccountSession(cookieHeader)) return true
        if (!leftLoginAfterVisit) return false
        if (!hasWebSessionCookie(cookieHeader)) return false
        val url = pageUrl.orEmpty().lowercase(Locale.US)
        if (url.contains("/account") && !url.contains("/login")) {
            return true
        }
        if (html.isNullOrBlank()) return false
        return looksLikeLoggedInHtml(html)
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
            // Only mirror across SerienStream origins when the target is a SerienStream host.
            // AniWorld (and other sites) must not inherit SerienStream cookie fan-out.
            if (url.isBlank() || isSerienStreamHost(url) || SerienStreamProvider.isSerienStreamHost(host)) {
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
            // Force Path=/ so CookieManager returns the jar for Home/Search/Detail/Play paths.
            val withPath = if (cookie.contains("Path=", ignoreCase = true)) {
                cookie
            } else {
                "$cookie; Path=/"
            }
            targets.forEach { target ->
                runCatching { cookieManager.setCookie(target, withPath) }
                val targetHost = runCatching { Uri.parse(target).host }.getOrNull()
                if (!targetHost.isNullOrBlank() && !SerienStreamEndpoints.isProxyHost(targetHost)) {
                    // Explicit Domain helps hostname mirrors; skip for bare proxy IPs.
                    runCatching {
                        cookieManager.setCookie(target, "$withPath; Domain=$targetHost")
                    }
                }
            }
        }
        flushCookieManager(cookieManager)
    }

    private fun flushCookieManager(cookieManager: CookieManager) {
        val flush = Runnable { runCatching { cookieManager.flush() } }
        val looper = android.os.Looper.myLooper()
        if (looper != null && looper == android.os.Looper.getMainLooper()) {
            flush.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(flush)
        }
    }
}
