package com.dskja.betterstreamflix.providers

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import com.dskja.betterstreamflix.player.SerienStreamBypassHelper
import com.dskja.betterstreamflix.utils.NetworkClient
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.watchlist.WatchlistImporter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * First-class SerienStream account / CF session management.
 *
 * SerienStream has no OAuth API — sessions are cookie jars (`cf_clearance`,
 * `PHPSESSID`, …) captured from WebView login or TV phone-bypass. This manager
 * owns persist, seed, validate, logout, paste/export, and status snapshots used
 * by Settings and the players.
 */
object SerienStreamAuthManager {

    private const val TAG = "SerienStreamAuth"

    data class SessionSnapshot(
        val cookies: String,
        val isLoggedIn: Boolean,
        val cookieCount: Int,
        val authCookieNames: List<String>,
        val displayName: String?,
        val domain: String,
        val lastValidatedAtMs: Long?,
        val lastValidatedOk: Boolean?,
    )

    data class ValidationResult(
        val ok: Boolean,
        val displayName: String?,
        val challengeActive: Boolean,
        val redirectedToLogin: Boolean,
        val detail: String,
    )

    fun isLoggedIn(): Boolean {
        val cookies = SerienStreamBypassHelper.sanitizeSessionCookies(
            UserPreferences.serienStreamSessionCookies,
        )
        if (SerienStreamBypassHelper.looksLikeAccountSession(cookies)) return true
        // Laravel sessions persisted after HTML-proven WebView login (no remember-me cookie).
        return UserPreferences.serienStreamAccountConfirmed &&
            SerienStreamBypassHelper.hasWebSessionCookie(cookies)
    }

    fun hasBypassSession(): Boolean {
        val cookies = SerienStreamBypassHelper.sanitizeSessionCookies(
            UserPreferences.serienStreamSessionCookies,
        )
        return SerienStreamBypassHelper.looksLikeBypassSolved(cookies) ||
            SerienStreamBypassHelper.looksLikeAccountSession(cookies)
    }

    fun snapshot(): SessionSnapshot {
        val cookies = SerienStreamBypassHelper.sanitizeSessionCookies(
            UserPreferences.serienStreamSessionCookies,
        )
        val loggedIn = isLoggedIn()
        val names = authCookieNames(cookies)
        return SessionSnapshot(
            cookies = cookies,
            isLoggedIn = loggedIn,
            cookieCount = names.size,
            authCookieNames = names,
            displayName = UserPreferences.serienStreamSessionDisplayName.takeIf { it.isNotBlank() },
            domain = SerienStreamEndpoints.normalizeHost(UserPreferences.serienstreamDomain),
            lastValidatedAtMs = UserPreferences.serienStreamSessionValidatedAtMs
                .takeIf { it > 0L },
            lastValidatedOk = when {
                UserPreferences.serienStreamSessionValidatedAtMs <= 0L -> null
                else -> UserPreferences.serienStreamSessionValidatedOk
            },
        )
    }

    /** Seed SharedPreferences cookies into CookieManager for all known SerienStream origins. */
    fun seedRuntimeCookies() {
        runCatching {
            SerienStreamBypassHelper.applyStoredSessionCookies()
        }.onFailure {
            Log.w(TAG, "Failed to seed SerienStream cookies: ${it.message}")
        }
    }

    fun persist(cookieHeader: String, displayName: String? = null): Boolean {
        // Prefer strict account persist; fall back to CF jar only when it is clearly a bypass jar
        // without claiming "account signed in" (caller may still seed for playback).
        val ok = SerienStreamBypassHelper.persistAccountSessionCookiesIfValid(cookieHeader) ||
            SerienStreamBypassHelper.persistSessionCookiesIfValid(cookieHeader)
        if (!ok) return false
        if (!displayName.isNullOrBlank()) {
            UserPreferences.serienStreamSessionDisplayName = displayName.trim()
        }
        // Fresh persist — clear stale validation until next probe.
        UserPreferences.serienStreamSessionValidatedAtMs = 0L
        UserPreferences.serienStreamSessionValidatedOk = false
        seedRuntimeCookies()
        return true
    }

    /** Settings Sign-in: only accept a real account session (cookie and/or HTML proof). */
    fun persistAccountLogin(
        cookieHeader: String,
        displayName: String? = null,
        htmlProof: String? = null,
        leftLoginAfterVisit: Boolean = false,
        pageUrl: String? = null,
    ): Boolean {
        val ok = SerienStreamBypassHelper.persistAccountSessionCookiesIfValid(
            cookieHeader = cookieHeader,
            htmlProof = htmlProof,
            leftLoginAfterVisit = leftLoginAfterVisit,
            pageUrl = pageUrl,
        )
        if (!ok) return false
        UserPreferences.serienStreamAccountConfirmed = true
        if (!displayName.isNullOrBlank()) {
            UserPreferences.serienStreamSessionDisplayName = displayName.trim()
        } else if (!htmlProof.isNullOrBlank()) {
            parseDisplayName(htmlProof)?.let {
                UserPreferences.serienStreamSessionDisplayName = it
            }
        }
        UserPreferences.serienStreamSessionValidatedAtMs = 0L
        UserPreferences.serienStreamSessionValidatedOk = false
        seedRuntimeCookies()
        return true
    }

    fun pasteCookies(raw: String): Boolean {
        val cleaned = SerienStreamBypassHelper.sanitizeSessionCookies(raw)
        if (!SerienStreamBypassHelper.looksLikeBypassSolved(cleaned) &&
            !SerienStreamBypassHelper.looksLikeAccountSession(cleaned) &&
            !SerienStreamBypassHelper.hasWebSessionCookie(cleaned)
        ) {
            return false
        }
        val saved = persist(cleaned)
        // Only remember-me / explicit account cookies confirm sign-in from paste.
        // A bare laravel_session is anonymous until WebView HTML proof or validate.
        if (saved && SerienStreamBypassHelper.looksLikeAccountSession(cleaned)) {
            UserPreferences.serienStreamAccountConfirmed = true
        }
        return saved
    }

    fun exportCookieHeader(): String {
        return SerienStreamBypassHelper.sanitizeSessionCookies(
            UserPreferences.serienStreamSessionCookies,
        )
    }

    /**
     * Full logout: prefs + CookieManager entries for every SerienStream / proxy origin.
     */
    fun logout() {
        val existing = exportCookieHeader()
        UserPreferences.serienStreamSessionCookies = ""
        UserPreferences.serienStreamSessionDisplayName = ""
        UserPreferences.serienStreamAccountConfirmed = false
        UserPreferences.serienStreamSessionValidatedAtMs = 0L
        UserPreferences.serienStreamSessionValidatedOk = false
        clearCookieManager(existing)
    }

    /**
     * Probe `/account` (and watchlist) with the current cookie jar.
     * Updates validation timestamps and optional display name.
     *
     * Never treats the public homepage (`/`) as proof — anonymous visitors
     * always get a 200 there.
     */
    suspend fun validateSession(): ValidationResult = withContext(Dispatchers.IO) {
        val snap = snapshot()
        if (!snap.isLoggedIn) {
            return@withContext ValidationResult(
                ok = false,
                displayName = null,
                challengeActive = false,
                redirectedToLogin = true,
                detail = "not_signed_in",
            )
        }

        seedRuntimeCookies()
        val base = SerienStreamProvider.baseUrl.trimEnd('/')
        val clients = listOf(NetworkClient.default, NetworkClient.trustAll)
        // Account endpoints only — bare `/` is always OK for guests.
        val urls = listOf("$base/account", "$base/account/watchlist")

        var lastDetail = "unreachable"
        var challenge = false
        var login = false
        var displayName: String? = snap.displayName

        for (client in clients) {
            for (url in urls) {
                val response = runCatching {
                    client.newCall(
                        Request.Builder()
                            .url(url)
                            .header(
                                "User-Agent",
                                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                                    "Chrome/124.0.0.0 Mobile Safari/537.36",
                            )
                            .header("Accept", "text/html,application/xhtml+xml")
                            .apply {
                                if (snap.cookies.isNotBlank()) {
                                    header("Cookie", snap.cookies)
                                }
                            }
                            .get()
                            .build(),
                    ).execute()
                }.getOrNull() ?: continue

                response.use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val finalUrl = resp.request.url.toString()
                    when (val verdict = evaluateAccountProbe(finalUrl, body, resp.isSuccessful)) {
                        AccountProbeVerdict.CHALLENGE -> {
                            challenge = true
                            lastDetail = "challenge"
                            return@withContext ValidationResult(
                                ok = false,
                                displayName = displayName,
                                challengeActive = true,
                                redirectedToLogin = false,
                                detail = "challenge",
                            )
                        }
                        AccountProbeVerdict.LOGIN -> {
                            login = true
                            lastDetail = "login"
                            // Dead session — don't keep Settings/UI stuck on "signed in".
                            UserPreferences.serienStreamAccountConfirmed = false
                            UserPreferences.serienStreamSessionValidatedOk = false
                            UserPreferences.serienStreamSessionValidatedAtMs =
                                System.currentTimeMillis()
                            return@withContext ValidationResult(
                                ok = false,
                                displayName = displayName,
                                challengeActive = false,
                                redirectedToLogin = true,
                                detail = "login",
                            )
                        }
                        AccountProbeVerdict.OK -> {
                            val scraped = parseDisplayName(body)
                            if (!scraped.isNullOrBlank()) {
                                displayName = scraped
                                UserPreferences.serienStreamSessionDisplayName = scraped
                            }
                            UserPreferences.serienStreamSessionValidatedAtMs =
                                System.currentTimeMillis()
                            UserPreferences.serienStreamSessionValidatedOk = true
                            UserPreferences.serienStreamAccountConfirmed = true
                            return@withContext ValidationResult(
                                ok = true,
                                displayName = displayName,
                                challengeActive = false,
                                redirectedToLogin = false,
                                detail = "ok",
                            )
                        }
                        AccountProbeVerdict.NOT_AUTHENTICATED -> {
                            lastDetail = "not_authenticated"
                        }
                        AccountProbeVerdict.HTTP_ERROR -> {
                            lastDetail = "http_${resp.code}"
                        }
                    }
                }
            }
        }

        UserPreferences.serienStreamSessionValidatedAtMs = System.currentTimeMillis()
        UserPreferences.serienStreamSessionValidatedOk = false
        if (login || lastDetail == "not_authenticated") {
            UserPreferences.serienStreamAccountConfirmed = false
        }
        ValidationResult(
            ok = false,
            displayName = displayName,
            challengeActive = challenge,
            redirectedToLogin = login,
            detail = lastDetail,
        )
    }

    enum class AccountProbeVerdict {
        OK,
        LOGIN,
        CHALLENGE,
        NOT_AUTHENTICATED,
        HTTP_ERROR,
    }

    /**
     * Pure probe evaluation used by [validateSession] (and unit tests).
     * Success requires clear logged-in HTML chrome. Bare public pages and
     * guest /account shells without auth markers never pass.
     */
    fun evaluateAccountProbe(
        finalUrl: String,
        body: String,
        httpSuccessful: Boolean,
    ): AccountProbeVerdict {
        if (WatchlistImporter.looksLikeChallengePage(body)) {
            return AccountProbeVerdict.CHALLENGE
        }
        // Prefer strong logged-in HTML before login-form heuristics so /account
        // password-change forms do not false-fail a valid session.
        val htmlOk = SerienStreamBypassHelper.looksLikeLoggedInHtml(body)
        if (htmlOk) {
            return AccountProbeVerdict.OK
        }
        if (WatchlistImporter.looksLikeLoginPage(body, finalUrl)) {
            return AccountProbeVerdict.LOGIN
        }
        if (!httpSuccessful || body.isBlank()) {
            return AccountProbeVerdict.HTTP_ERROR
        }
        return AccountProbeVerdict.NOT_AUTHENTICATED
    }

    fun parseDisplayName(html: String): String? {
        if (html.isBlank()) return null
        // Common SerienStream / AniWorld account chrome.
        val patterns = listOf(
            Regex("""class=["'][^"']*user-name[^"']*["'][^>]*>\s*([^<]{2,64})\s*<""", RegexOption.IGNORE_CASE),
            Regex("""id=["']user["'][^>]*>\s*([^<]{2,64})\s*<""", RegexOption.IGNORE_CASE),
            Regex("""Mein\s+Konto[^<]{0,40}</[^>]+>\s*<[^>]+>\s*([^<]{2,64})\s*<""", RegexOption.IGNORE_CASE),
            Regex("""<title>\s*([^|<]{2,64})\s*\|""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            val match = pattern.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (match.length in 2..64 &&
                !match.contains("serienstream", ignoreCase = true) &&
                !match.contains("aniworld", ignoreCase = true) &&
                !match.contains("login", ignoreCase = true) &&
                !match.contains("ddos", ignoreCase = true)
            ) {
                return match
            }
        }
        return null
    }

    fun authCookieNames(cookieHeader: String): List<String> {
        val cleaned = SerienStreamBypassHelper.sanitizeSessionCookies(cookieHeader)
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .map { it.substringBefore("=").trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
    }

    fun needsReauthHint(htmlOrMessage: String?): Boolean {
        val text = htmlOrMessage.orEmpty()
        if (text.isBlank()) return false
        return WatchlistImporter.looksLikeLoginPage(text) ||
            WatchlistImporter.looksLikeChallengePage(text) ||
            text.contains("just a moment", ignoreCase = true) ||
            text.contains("access denied", ignoreCase = true)
    }

    data class CredentialLoginResult(
        val ok: Boolean,
        val displayName: String? = null,
        val challengeActive: Boolean = false,
        val error: String? = null,
    )

    /** Extract Laravel / SerienStream CSRF token from the login HTML. */
    fun parseLoginCsrfToken(html: String): String? {
        if (html.isBlank()) return null
        val patterns = listOf(
            Regex("""<input[^>]+name=["']_token["'][^>]+value=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<input[^>]+value=["']([^"']+)["'][^>]+name=["']_token["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+name=["']csrf-token["']""", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length >= 8 }
        }
    }

    /**
     * GuardaFlix-style in-app email/password login. Falls back to the WebView
     * flow when Cloudflare is in front of `/login`.
     */
    suspend fun loginWithCredentials(
        email: String,
        password: String,
    ): CredentialLoginResult = withContext(Dispatchers.IO) {
        val trimmed = email.trim()
        if (trimmed.length < 3 || password.length < 4) {
            return@withContext CredentialLoginResult(ok = false, error = "invalid")
        }
        seedRuntimeCookies()
        val base = SerienStreamProvider.baseUrl.trimEnd('/')
        val loginUrl = "$base/login"
        val clients = listOf(NetworkClient.default, NetworkClient.trustAll)
        var lastError = "unreachable"
        for (client in clients) {
            val getResp = runCatching {
                client.newCall(
                    Request.Builder()
                        .url(loginUrl)
                        .header("User-Agent", NetworkClient.USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml")
                        .get()
                        .build(),
                ).execute()
            }.getOrNull() ?: continue
            val getBody = getResp.use { it.body?.string().orEmpty() }
            if (WatchlistImporter.looksLikeChallengePage(getBody)) {
                return@withContext CredentialLoginResult(
                    ok = false,
                    challengeActive = true,
                    error = "challenge",
                )
            }
            val token = parseLoginCsrfToken(getBody)
            val form = okhttp3.FormBody.Builder()
                .add("email", trimmed)
                .add("password", password)
                .add("remember", "on")
                .apply {
                    if (!token.isNullOrBlank()) add("_token", token)
                }
                .build()
            val postResp = runCatching {
                client.newCall(
                    Request.Builder()
                        .url(loginUrl)
                        .header("User-Agent", NetworkClient.USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml")
                        .header("Origin", base)
                        .header("Referer", loginUrl)
                        .post(form)
                        .build(),
                ).execute()
            }.getOrNull() ?: continue
            val postSnap = postResp.use { resp ->
                Triple(
                    resp.body?.string().orEmpty(),
                    resp.request.url.toString(),
                    resp.headers("Set-Cookie").joinToString("; ") { it.substringBefore(';') },
                )
            }
            val postBody = postSnap.first
            val finalUrl = postSnap.second
            val setCookies = postSnap.third
            if (WatchlistImporter.looksLikeChallengePage(postBody)) {
                return@withContext CredentialLoginResult(
                    ok = false,
                    challengeActive = true,
                    error = "challenge",
                )
            }
            val managerCookies = runCatching {
                android.webkit.CookieManager.getInstance().getCookie("$base/")
            }.getOrNull().orEmpty()
            val cookieHeader = listOf(managerCookies, setCookies)
                .filter { it.isNotBlank() }
                .joinToString("; ")
            val htmlOk = SerienStreamBypassHelper.looksLikeLoggedInHtml(postBody) ||
                (!WatchlistImporter.looksLikeLoginPage(postBody, finalUrl) &&
                    SerienStreamBypassHelper.hasWebSessionCookie(cookieHeader))
            if (htmlOk) {
                val name = parseDisplayName(postBody)
                persistAccountLogin(
                    cookieHeader = cookieHeader,
                    displayName = name,
                    htmlProof = postBody,
                    leftLoginAfterVisit = true,
                    pageUrl = finalUrl,
                )
                return@withContext CredentialLoginResult(ok = true, displayName = name)
            }
            lastError = "login"
        }
        CredentialLoginResult(ok = false, error = lastError)
    }

    private fun clearCookieManager(cookieHeader: String) {
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        val names = authCookieNames(cookieHeader).ifEmpty {
            listOf(
                "cf_clearance",
                "ddos_token",
                "PHPSESSID",
                "phpsessid",
                "laravel_session",
                "XSRF-TOKEN",
                "xsrf-token",
                "altcha",
            )
        }
        val targets = linkedSetOf<String>().apply {
            add("https://serienstream.to/")
            add("https://serienstream.cx/")
            add(SerienStreamEndpoints.originFor(SerienStreamEndpoints.PROXY_HOST))
            SerienStreamProvider.candidateDomains().forEach { domain ->
                add(SerienStreamEndpoints.originFor(domain))
            }
            runCatching {
                add(SerienStreamProvider.baseUrl.trimEnd('/') + "/")
            }
        }
        targets.forEach { target ->
            val host = runCatching { Uri.parse(target).host }.getOrNull()
            // Expire known names.
            names.forEach { name ->
                runCatching {
                    cookieManager.setCookie(target, "$name=; Max-Age=0; Path=/")
                    if (!host.isNullOrBlank()) {
                        cookieManager.setCookie(target, "$name=; Max-Age=0; Path=/; Domain=$host")
                    }
                }
            }
            // Also expire whatever CookieManager still reports for the URL.
            val existing = runCatching { cookieManager.getCookie(target) }.getOrNull().orEmpty()
            existing.split(";")
                .map { it.trim() }
                .filter { it.contains("=") }
                .forEach { part ->
                    val name = part.substringBefore("=").trim()
                    if (name.isNotBlank()) {
                        runCatching {
                            cookieManager.setCookie(target, "$name=; Max-Age=0; Path=/")
                        }
                    }
                }
        }
        runCatching { cookieManager.flush() }
    }
}
