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
 * First-class AniWorld account / CF session management — parity with
 * [SerienStreamAuthManager] (same Laravel / CF cookie model, separate jar).
 */
object AniWorldAuthManager {

    private const val TAG = "AniWorldAuth"
    private const val DEFAULT_HOST = "aniworld.to"

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

    data class CredentialLoginResult(
        val ok: Boolean,
        val displayName: String? = null,
        val challengeActive: Boolean = false,
        val error: String? = null,
    )

    fun isLoggedIn(): Boolean {
        val cookies = SerienStreamBypassHelper.sanitizeSessionCookies(
            UserPreferences.aniWorldSessionCookies,
        )
        if (SerienStreamBypassHelper.looksLikeAccountSession(cookies)) return true
        return UserPreferences.aniWorldAccountConfirmed &&
            SerienStreamBypassHelper.hasWebSessionCookie(cookies)
    }

    fun snapshot(): SessionSnapshot {
        val cookies = SerienStreamBypassHelper.sanitizeSessionCookies(
            UserPreferences.aniWorldSessionCookies,
        )
        val loggedIn = isLoggedIn()
        val names = SerienStreamAuthManager.authCookieNames(cookies)
        return SessionSnapshot(
            cookies = cookies,
            isLoggedIn = loggedIn,
            cookieCount = names.size,
            authCookieNames = names,
            displayName = UserPreferences.aniWorldSessionDisplayName.takeIf { it.isNotBlank() },
            domain = hostFromBaseUrl(),
            lastValidatedAtMs = UserPreferences.aniWorldSessionValidatedAtMs
                .takeIf { it > 0L },
            lastValidatedOk = when {
                UserPreferences.aniWorldSessionValidatedAtMs <= 0L -> null
                else -> UserPreferences.aniWorldSessionValidatedOk
            },
        )
    }

    fun seedRuntimeCookies() {
        runCatching {
            val cookies = SerienStreamBypassHelper.sanitizeSessionCookies(
                UserPreferences.aniWorldSessionCookies,
            )
            if (cookies.isBlank()) return
            origins().forEach { origin ->
                // Do not use applyCookies — that merges SerienStream prefs onto AniWorld.
                SerienStreamBypassHelper.seedCookiesToManager(origin, cookies)
            }
        }.onFailure {
            Log.w(TAG, "Failed to seed AniWorld cookies: ${it.message}")
        }
    }

    /** Cookie header OkHttp interceptors should attach on AniWorld hosts. */
    fun cookieHeaderForRequests(): String {
        return SerienStreamBypassHelper.sanitizeSessionCookies(
            UserPreferences.aniWorldSessionCookies,
        )
    }

    /**
     * Skip interactive captcha when clearance **or** a confirmed AniWorld
     * login/session token is present.
     */
    fun canSkipInteractiveBypass(): Boolean {
        return SerienStreamBypassHelper.shouldSkipStreamCaptcha(
            UserPreferences.aniWorldSessionCookies,
        )
    }

    fun persist(cookieHeader: String, displayName: String? = null): Boolean {
        val cleaned = SerienStreamBypassHelper.sanitizeSessionCookies(cookieHeader)
        if (cleaned.isBlank()) return false
        if (!SerienStreamBypassHelper.looksLikeBypassSolved(cleaned) &&
            !SerienStreamBypassHelper.looksLikeAccountSession(cleaned) &&
            !SerienStreamBypassHelper.hasWebSessionCookie(cleaned)
        ) {
            return false
        }
        UserPreferences.aniWorldSessionCookies = mergeCookieHeaders(
            UserPreferences.aniWorldSessionCookies,
            cleaned,
        )
        if (!displayName.isNullOrBlank()) {
            UserPreferences.aniWorldSessionDisplayName = displayName.trim()
        }
        UserPreferences.aniWorldSessionValidatedAtMs = 0L
        UserPreferences.aniWorldSessionValidatedOk = false
        seedRuntimeCookies()
        return true
    }

    fun persistAccountLogin(
        cookieHeader: String,
        displayName: String? = null,
        htmlProof: String? = null,
        leftLoginAfterVisit: Boolean = false,
        pageUrl: String? = null,
    ): Boolean {
        val cleaned = SerienStreamBypassHelper.sanitizeSessionCookies(cookieHeader)
        if (cleaned.isBlank()) return false
        val ok = SerienStreamBypassHelper.looksLikeAccountSession(cleaned) ||
            SerienStreamBypassHelper.looksLikeAuthenticatedSession(
                cookieHeader = cleaned,
                html = htmlProof,
                leftLoginAfterVisit = leftLoginAfterVisit,
                pageUrl = pageUrl,
            )
        if (!ok) return false
        UserPreferences.aniWorldSessionCookies = mergeCookieHeaders(
            UserPreferences.aniWorldSessionCookies,
            cleaned,
        )
        UserPreferences.aniWorldAccountConfirmed = true
        if (!displayName.isNullOrBlank()) {
            UserPreferences.aniWorldSessionDisplayName = displayName.trim()
        } else if (!htmlProof.isNullOrBlank()) {
            SerienStreamAuthManager.parseDisplayName(htmlProof)?.let {
                UserPreferences.aniWorldSessionDisplayName = it
            }
        }
        UserPreferences.aniWorldSessionValidatedAtMs = 0L
        UserPreferences.aniWorldSessionValidatedOk = false
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
        if (saved && (
                SerienStreamBypassHelper.looksLikeAccountSession(cleaned) ||
                    (
                        SerienStreamBypassHelper.looksLikeClearanceSolved(cleaned) &&
                            SerienStreamBypassHelper.hasWebSessionCookie(cleaned)
                        )
                )
        ) {
            UserPreferences.aniWorldAccountConfirmed = true
        }
        return saved
    }

    fun exportCookieHeader(): String {
        return SerienStreamBypassHelper.sanitizeSessionCookies(
            UserPreferences.aniWorldSessionCookies,
        )
    }

    fun logout() {
        val existing = exportCookieHeader()
        UserPreferences.aniWorldSessionCookies = ""
        UserPreferences.aniWorldSessionDisplayName = ""
        UserPreferences.aniWorldAccountConfirmed = false
        UserPreferences.aniWorldSessionValidatedAtMs = 0L
        UserPreferences.aniWorldSessionValidatedOk = false
        clearCookieManager(existing)
    }

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
        val base = AniWorldProvider.baseUrl.trimEnd('/').ifBlank { "https://$DEFAULT_HOST" }
        val clients = listOf(NetworkClient.default, NetworkClient.trustAll)
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
                    when (
                        val verdict = SerienStreamAuthManager.evaluateAccountProbe(
                            finalUrl,
                            body,
                            resp.isSuccessful,
                        )
                    ) {
                        SerienStreamAuthManager.AccountProbeVerdict.CHALLENGE -> {
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
                        SerienStreamAuthManager.AccountProbeVerdict.LOGIN -> {
                            login = true
                            lastDetail = "login"
                            UserPreferences.aniWorldAccountConfirmed = false
                            UserPreferences.aniWorldSessionValidatedOk = false
                            UserPreferences.aniWorldSessionValidatedAtMs =
                                System.currentTimeMillis()
                            return@withContext ValidationResult(
                                ok = false,
                                displayName = displayName,
                                challengeActive = false,
                                redirectedToLogin = true,
                                detail = "login",
                            )
                        }
                        SerienStreamAuthManager.AccountProbeVerdict.OK -> {
                            val scraped = SerienStreamAuthManager.parseDisplayName(body)
                            if (!scraped.isNullOrBlank()) {
                                displayName = scraped
                                UserPreferences.aniWorldSessionDisplayName = scraped
                            }
                            UserPreferences.aniWorldSessionValidatedAtMs =
                                System.currentTimeMillis()
                            UserPreferences.aniWorldSessionValidatedOk = true
                            UserPreferences.aniWorldAccountConfirmed = true
                            return@withContext ValidationResult(
                                ok = true,
                                displayName = displayName,
                                challengeActive = false,
                                redirectedToLogin = false,
                                detail = "ok",
                            )
                        }
                        SerienStreamAuthManager.AccountProbeVerdict.NOT_AUTHENTICATED -> {
                            lastDetail = "not_authenticated"
                        }
                        SerienStreamAuthManager.AccountProbeVerdict.HTTP_ERROR -> {
                            lastDetail = "http_${resp.code}"
                        }
                    }
                }
            }
        }

        UserPreferences.aniWorldSessionValidatedAtMs = System.currentTimeMillis()
        UserPreferences.aniWorldSessionValidatedOk = false
        if (login || lastDetail == "not_authenticated") {
            UserPreferences.aniWorldAccountConfirmed = false
        }
        ValidationResult(
            ok = false,
            displayName = displayName,
            challengeActive = challenge,
            redirectedToLogin = login,
            detail = lastDetail,
        )
    }

    suspend fun loginWithCredentials(
        email: String,
        password: String,
    ): CredentialLoginResult = withContext(Dispatchers.IO) {
        val trimmed = email.trim()
        if (trimmed.length < 3 || password.length < 4) {
            return@withContext CredentialLoginResult(ok = false, error = "invalid")
        }
        seedRuntimeCookies()
        val base = AniWorldProvider.baseUrl.trimEnd('/').ifBlank { "https://$DEFAULT_HOST" }
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
            val token = SerienStreamAuthManager.parseLoginCsrfToken(getBody)
            val form = okhttp3.FormBody.Builder()
                .add("email", trimmed)
                .add("password", password)
                .add("autoLogin", "on")
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
                val cm = CookieManager.getInstance()
                listOf(
                    cm.getCookie("$base/").orEmpty(),
                    cm.getCookie(loginUrl).orEmpty(),
                    cm.getCookie("$base/account").orEmpty(),
                ).filter { it.isNotBlank() }.joinToString("; ")
            }.getOrNull().orEmpty()
            val cookieHeader = SerienStreamBypassHelper.mergeCookieHeaders(
                SerienStreamBypassHelper.mergeCookieHeaders(managerCookies, setCookies),
                UserPreferences.aniWorldSessionCookies,
            )
            val htmlOk = SerienStreamBypassHelper.looksLikeLoggedInHtml(postBody) ||
                (!WatchlistImporter.looksLikeLoginPage(postBody, finalUrl) &&
                    SerienStreamBypassHelper.hasWebSessionCookie(cookieHeader))
            if (htmlOk) {
                val name = SerienStreamAuthManager.parseDisplayName(postBody)
                persistAccountLogin(
                    cookieHeader = cookieHeader,
                    displayName = name,
                    htmlProof = postBody,
                    leftLoginAfterVisit = true,
                    pageUrl = finalUrl,
                )
                seedRuntimeCookies()
                return@withContext CredentialLoginResult(ok = true, displayName = name)
            }
            lastError = "login"
        }
        CredentialLoginResult(ok = false, error = lastError)
    }

    private fun hostFromBaseUrl(): String {
        val base = AniWorldProvider.baseUrl.trimEnd('/').ifBlank { "https://$DEFAULT_HOST" }
        return runCatching { Uri.parse(base).host }.getOrNull()?.ifBlank { null } ?: DEFAULT_HOST
    }

    private fun origins(): LinkedHashSet<String> = linkedSetOf<String>().apply {
        add("https://$DEFAULT_HOST/")
        add("http://$DEFAULT_HOST/")
        runCatching {
            val base = AniWorldProvider.baseUrl.trimEnd('/')
            if (base.isNotBlank()) add("$base/")
        }
    }

    private fun mergeCookieHeaders(existing: String, incoming: String): String {
        val map = linkedMapOf<String, String>()
        fun absorb(header: String) {
            SerienStreamBypassHelper.sanitizeSessionCookies(header)
                .split(";")
                .map { it.trim() }
                .filter { it.contains("=") }
                .forEach { part ->
                    val key = part.substringBefore("=").trim().lowercase(Locale.US)
                    if (key.isNotBlank()) map[key] = part
                }
        }
        absorb(existing)
        absorb(incoming)
        return map.values.joinToString("; ")
    }

    private fun clearCookieManager(cookieHeader: String) {
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        val names = SerienStreamAuthManager.authCookieNames(cookieHeader).ifEmpty {
            listOf(
                "cf_clearance",
                "ddos_token",
                "PHPSESSID",
                "phpsessid",
                "laravel_session",
                "aniworld_session",
                "XSRF-TOKEN",
                "xsrf-token",
                "altcha",
            )
        }
        origins().forEach { target ->
            val host = runCatching { Uri.parse(target).host }.getOrNull()
            names.forEach { name ->
                runCatching {
                    cookieManager.setCookie(target, "$name=; Max-Age=0; Path=/")
                    if (!host.isNullOrBlank()) {
                        cookieManager.setCookie(target, "$name=; Max-Age=0; Path=/; Domain=$host")
                    }
                }
            }
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
        val flush = Runnable { runCatching { cookieManager.flush() } }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            flush.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(flush)
        }
    }
}
