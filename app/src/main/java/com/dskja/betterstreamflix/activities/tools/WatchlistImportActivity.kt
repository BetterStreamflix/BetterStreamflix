package com.dskja.betterstreamflix.activities.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.providers.AniWorldProvider
import com.dskja.betterstreamflix.providers.SerienStreamProvider
import com.dskja.betterstreamflix.player.SerienStreamBypassHelper
import com.dskja.betterstreamflix.utils.AppLanguageManager
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.NetworkClient
import com.dskja.betterstreamflix.utils.ThemeManager
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.WebViewDohBridge
import com.dskja.betterstreamflix.watchlist.WatchlistImporter
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * WebView login + scrape flow for SerienStream / AniWorld watchlists.
 *
 * After login, Import navigates the same WebView to watchlist pages (so DDoS /
 * Cloudflare challenges stay solved), scrolls to load lazy cards, extracts HTML,
 * then persists Favorites. OkHttp is only used as a fallback.
 */
class WatchlistImportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_SAVE_SESSION_ONLY = "extra_save_session_only"
        const val SOURCE_SERIENSTREAM = "serienstream"
        const val SOURCE_ANIWORLD = "aniworld"
        private const val TAG = "WatchlistImport"
        private const val PAGE_SETTLE_MS = 1_200L
        private const val SCROLL_ROUNDS = 8
    }

    private lateinit var webView: WebView
    /** Cached on main thread — never read WebView.settings from shouldInterceptRequest. */
    private var webViewUserAgent: String = NetworkClient.USER_AGENT
    private var isCleaningUp = false
    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var importButton: Button
    private lateinit var cancelButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private var importing = false
    private var sessionAutoSaved = false
    private var lastPageHtml: String = ""
    private var pageFinishedCallback: ((String?) -> Unit)? = null
    private var lastLoadError: String? = null
    private var warmDomainIndex: Int = 0
    /** True once the WebView has actually shown `/login` during this session. */
    private var sawLoginPage = false
    /** Set after the user submits credentials and leaves `/login`. */
    private var leftLoginAfterVisit = false
    /** Last main-frame URL observed by login-state detection. */
    private var lastPageUrl: String? = null
    /** Avoid hammering /account while waiting for SSR auth chrome after login. */
    private var accountVerifyRequested = false

    private val saveSessionOnly: Boolean by lazy {
        intent.getBooleanExtra(EXTRA_SAVE_SESSION_ONLY, false)
    }

    private val source: WatchlistImporter.Source by lazy {
        when (intent.getStringExtra(EXTRA_SOURCE)) {
            SOURCE_ANIWORLD -> WatchlistImporter.Source.ANIWORLD
            else -> WatchlistImporter.Source.SERIENSTREAM
        }
    }

    private var hostBase: String = ""

    private val startUrl: String
        get() = "$hostBase/login"

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            if (ExperimentalMobileDesign.enabled()) {
                ExperimentalMobileDesign.themeRes()
            } else {
                ThemeManager.mobileThemeRes(UserPreferences.selectedTheme)
            },
        )
        if (ExperimentalMobileDesign.enabled()) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        super.onCreate(savedInstanceState)
        setContentView(
            R.layout.activity_watchlist_import,
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        webView = findViewById(R.id.watchlist_webview)
        progressBar = findViewById(R.id.watchlist_progress)
        statusView = findViewById(R.id.watchlist_status)
        importButton = findViewById(R.id.watchlist_import)
        cancelButton = findViewById(R.id.watchlist_cancel)

        if (ExperimentalMobileDesign.enabled()) {
            val content = findViewById<android.view.View>(android.R.id.content)
            com.dskja.betterstreamflix.utils.ExpMotion.enterScreen(content)
            ExperimentalMobileDesign.applyReducedGlass(content)
            statusView.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                importButton.applyExpPress()
                cancelButton.applyExpPress()
            }
            importButton.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
            cancelButton.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
        }

        statusView.setText(R.string.watchlist_import_login_hint)
        val screenTitle = if (saveSessionOnly) {
            getString(R.string.settings_serienstream_session_login)
        } else {
            getString(R.string.settings_watchlist_import_title)
        }
        title = screenTitle
        findViewById<android.widget.TextView>(R.id.tv_watchlist_brand)?.text = screenTitle
        if (ExperimentalMobileDesign.enabled()) {
            com.dskja.betterstreamflix.utils.ExpMotion.revealHeader(
                findViewById(R.id.tv_watchlist_eyebrow),
                findViewById(R.id.tv_watchlist_brand),
                findViewById(R.id.v_watchlist_rule),
                statusView,
            )
            com.dskja.betterstreamflix.utils.ExpMotion.pulseAccentRule(
                findViewById(R.id.v_watchlist_rule),
            )
            com.dskja.betterstreamflix.utils.ExpMotion.popIn(importButton)
            com.dskja.betterstreamflix.utils.ExpMotion.popIn(cancelButton)
        }
        importButton.setText(
            if (saveSessionOnly) {
                R.string.settings_serienstream_session_login
            } else {
                R.string.watchlist_import_action
            },
        )

        cancelButton.setOnClickListener {
            if (importing) return@setOnClickListener
            com.dskja.betterstreamflix.utils.ExpMotion.hapticTap(it)
            finish()
        }
        importButton.setOnClickListener {
            com.dskja.betterstreamflix.utils.ExpMotion.hapticTap(it)
            if (saveSessionOnly) {
                saveSessionAndFinish()
            } else {
                runImport()
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        // Restore any previously pasted session cookies for this source.
        when (source) {
            WatchlistImporter.Source.SERIENSTREAM -> {
                val pasted = UserPreferences.serienStreamSessionCookies.trim()
                if (pasted.isNotBlank()) {
                    SerienStreamBypassHelper.applyStoredSessionCookies()
                }
            }
            WatchlistImporter.Source.ANIWORLD -> {
                val pasted = UserPreferences.aniWorldSessionCookies.trim()
                if (pasted.isNotBlank()) {
                    com.dskja.betterstreamflix.providers.AniWorldAuthManager.seedRuntimeCookies()
                }
            }
        }

        hostBase = resolveHostBase()
        setupWebView()
        if (saveSessionOnly) {
            // Login-only: never warm the homepage (trailers / YouTube links).
            statusView.setText(R.string.watchlist_import_login_hint)
            webView.loadUrl(startUrl)
        } else {
            // Warm the site first (challenge cookies), then open login.
            warmAndOpenLogin()
        }
    }

    private fun warmAndOpenLogin(domainIndex: Int = 0) {
        val candidates = when (source) {
            WatchlistImporter.Source.SERIENSTREAM -> {
                SerienStreamProvider.candidateDomains()
            }
            WatchlistImporter.Source.ANIWORLD -> listOf(
                AniWorldProvider.baseUrl.trimEnd('/').removePrefix("https://").removePrefix("http://")
                    .ifBlank { "aniworld.to" },
                "aniworld.to",
            )
        }.map { it.trim().lowercase().removePrefix("www.") }.distinct()
        if (domainIndex >= candidates.size) {
            statusView.setText(R.string.watchlist_import_login_hint)
            webView.loadUrl(startUrl)
            return
        }
        warmDomainIndex = domainIndex
        hostBase = if (source == WatchlistImporter.Source.SERIENSTREAM) {
            SerienStreamProvider.originFor(candidates[domainIndex]).trimEnd('/')
        } else {
            "https://${candidates[domainIndex]}"
        }
        lastLoadError = null
        webView.loadUrl(hostBase)
        mainHandler.postDelayed({
            if (isFinishing || importing) return@postDelayed
            // Only fail over when the main frame failed hard — challenge pages still "load".
            if (lastLoadError != null && domainIndex + 1 < candidates.size) {
                Log.w(TAG, "Warm failed on ${candidates[domainIndex]} ($lastLoadError) — trying next")
                warmAndOpenLogin(domainIndex + 1)
            } else if (lastLoadError == null) {
                webView.loadUrl(startUrl)
            }
        }, 1_400L)
    }

    private fun resolveHostBase(): String {
        return when (source) {
            WatchlistImporter.Source.SERIENSTREAM -> {
                val preferred = SerienStreamProvider.baseUrl.trimEnd('/')
                preferred.ifBlank {
                    SerienStreamProvider.originFor(SerienStreamProvider.candidateDomains().first())
                        .trimEnd('/')
                }
            }
            WatchlistImporter.Source.ANIWORLD ->
                AniWorldProvider.baseUrl.trimEnd('/').ifBlank { "https://aniworld.to" }
        }
    }

    private fun isAllowedLoginNavigation(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("javascript:")) {
            return true
        }
        if (lower.startsWith("intent:") || lower.startsWith("market:") || lower.startsWith("vnd.")) {
            return false
        }
        if (lower.contains("youtube.com") || lower.contains("youtu.be") ||
            lower.contains("youtube-nocookie.com") || lower.contains("googlevideo.com") ||
            lower.contains("ytimg.com")
        ) {
            return false
        }
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
            ?.lowercase()
            ?.removePrefix("www.")
            .orEmpty()
        if (host.isBlank()) return false
        if (host == "challenges.cloudflare.com" || host.endsWith(".cloudflare.com")) return true
        if (host.contains("ddos-guard")) return true
        return when (source) {
            WatchlistImporter.Source.SERIENSTREAM ->
                SerienStreamProvider.isSerienStreamHost(url)
            WatchlistImporter.Source.ANIWORLD ->
                host == "aniworld.to" || host.endsWith(".aniworld.to")
        }
    }

    private fun setupWebView() {
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Desktop UA helps SerienStream CF challenges that blank mobile WebViews.
            userAgentString = WatchlistImporter.userAgentFor(source)
                .ifBlank { NetworkClient.USER_AGENT }
            webViewUserAgent = userAgentString ?: NetworkClient.USER_AGENT
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                val show = newProgress in 1..99
                if (ExperimentalMobileDesign.enabled()) {
                    if (show && progressBar.visibility != android.view.View.VISIBLE) {
                        com.dskja.betterstreamflix.utils.ExpMotion.fadeInAndShow(progressBar)
                    } else if (!show && progressBar.visibility == android.view.View.VISIBLE) {
                        com.dskja.betterstreamflix.utils.ExpMotion.fadeOutAndHide(progressBar)
                    }
                } else {
                    progressBar.visibility =
                        if (show) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (url.isBlank()) return false
                if (isAllowedLoginNavigation(url)) return false
                Log.w(TAG, "blocked off-site navigation: $url")
                return true
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url.orEmpty()
                if (target.isBlank()) return false
                if (isAllowedLoginNavigation(target)) return false
                Log.w(TAG, "blocked off-site navigation (legacy): $target")
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): android.webkit.WebResourceResponse? {
                val host = request?.url?.host?.lowercase()?.removePrefix("www.").orEmpty()
                if (host.contains("youtube") || host.contains("youtu.be") ||
                    host.contains("googlevideo") || host.contains("ytimg")
                ) {
                    return android.webkit.WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        204,
                        "No Content",
                        emptyMap(),
                        java.io.ByteArrayInputStream(ByteArray(0)),
                    )
                }
                // Must not touch WebView APIs here (off main thread) — BETTERSTREAMFLIX-T.
                val bridged = WebViewDohBridge.interceptMainDocument(
                    request,
                    webViewUserAgent,
                )
                if (bridged != null) return bridged
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                lastLoadError = null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                detectCopyrightBlockThenContinue(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                lastLoadError = error?.description?.toString() ?: "load error"
                if (!importing) {
                    statusView.text = getString(
                        R.string.watchlist_import_failed,
                        lastLoadError ?: "error",
                    )
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?,
            ) {
                // Some SerienStream mirrors present odd intermediate certs on WebView.
                Log.w(TAG, "SSL warning on ${error?.url}: ${error?.primaryError}")
                handler?.proceed()
            }
        }
    }

    private fun detectCopyrightBlockThenContinue(url: String?) {
        webView.evaluateJavascript(
            "(function(){try{return document.documentElement.outerHTML||'';}catch(e){return ''}})();"
        ) { raw ->
            val html = decodeJavascriptValue(raw)
            if (WebViewDohBridge.isCopyrightBlockPage(html)) {
                lastLoadError = "isp_dns_block"
                statusView.setText(R.string.watchlist_import_isp_block)
                importButton.isEnabled = false
                notifyUser(R.string.watchlist_import_isp_block_toast)
                // Prefer the serien.domains proxy (or next mirror) over a sinkholed hostname.
                if (source == WatchlistImporter.Source.SERIENSTREAM) {
                    val next = warmDomainIndex + 1
                    if (next < SerienStreamProvider.candidateDomains().size) {
                        Log.w(TAG, "CUII block on $hostBase — trying next SerienStream endpoint")
                        warmAndOpenLogin(next)
                        return@evaluateJavascript
                    }
                }
                return@evaluateJavascript
            }
            updateLoginState(url, html.orEmpty())
            pageFinishedCallback?.invoke(url)
        }
    }

    private fun updateLoginState(url: String?, html: String = lastPageHtml) {
        if (importing) return
        if (html.isNotBlank()) lastPageHtml = html
        if (!url.isNullOrBlank()) lastPageUrl = url
        val cookies = cookieHeader()
        if (source == WatchlistImporter.Source.SERIENSTREAM && cookies.isNotBlank()) {
            SerienStreamBypassHelper.applyCookies("$hostBase/", cookies)
            url?.takeIf { it.isNotBlank() }?.let {
                SerienStreamBypassHelper.applyCookies(it, cookies)
            }
        } else if (source == WatchlistImporter.Source.ANIWORLD && cookies.isNotBlank()) {
            SerienStreamBypassHelper.applyCookies("$hostBase/", cookies)
            url?.takeIf { it.isNotBlank() }?.let {
                SerienStreamBypassHelper.applyCookies(it, cookies)
            }
        }
        val onLogin = url != null && url.contains("/login", ignoreCase = true)
        if (onLogin) {
            sawLoginPage = true
            leftLoginAfterVisit = false
            accountVerifyRequested = false
        } else if (sawLoginPage && url != null) {
            leftLoginAfterVisit = true
        }
        val leftLogin = url != null && !onLogin
        val hasSession = looksLoggedIn(cookies, lastPageHtml, url)
        val bypassSolved = when (source) {
            WatchlistImporter.Source.SERIENSTREAM,
            WatchlistImporter.Source.ANIWORLD,
            -> SerienStreamBypassHelper.looksLikeBypassSolved(cookies)
        }
        val wasEnabled = importButton.isEnabled
        // Never enable Import / auto-save from warm homepage cookies alone.
        importButton.isEnabled = when {
            saveSessionOnly && (
                source == WatchlistImporter.Source.SERIENSTREAM ||
                    source == WatchlistImporter.Source.ANIWORLD
                ) -> hasSession && leftLoginAfterVisit
            else -> leftLogin || hasSession || (cookies.isNotBlank() && bypassSolved && sawLoginPage)
        }
        if (ExperimentalMobileDesign.enabled() && importButton.isEnabled && !wasEnabled) {
            com.dskja.betterstreamflix.utils.ExpMotion.popIn(importButton)
        }
        if (lastLoadError != null && !leftLogin && !hasSession) {
            statusView.text = getString(R.string.watchlist_import_failed, lastLoadError!!)
            return
        }
        when {
            (source == WatchlistImporter.Source.SERIENSTREAM ||
                source == WatchlistImporter.Source.ANIWORLD) &&
                cookies.isNotBlank() &&
                !bypassSolved &&
                !hasSession -> {
                statusView.setText(R.string.bypass_status_challenge_pending)
            }
            leftLogin && hasSession && leftLoginAfterVisit -> {
                statusView.setText(R.string.watchlist_import_ready)
                if (ExperimentalMobileDesign.enabled() &&
                    statusView.getTag(R.id.exp_enter_animated_tag) != "ready"
                ) {
                    statusView.setTag(R.id.exp_enter_animated_tag, "ready")
                    com.dskja.betterstreamflix.utils.ExpMotion.revealHeader(statusView)
                }
                maybeAutoSaveSession()
            }
            leftLogin && sawLoginPage &&
                (source == WatchlistImporter.Source.SERIENSTREAM ||
                    source == WatchlistImporter.Source.ANIWORLD) &&
                SerienStreamBypassHelper.hasWebSessionCookie(cookies) &&
                !hasSession -> {
                // Login redirect landed but SSR chrome not proven yet — probe /account.
                statusView.setText(R.string.watchlist_import_ready_soft)
                maybeVerifyAccountAfterLogin(url)
            }
            leftLogin && sawLoginPage -> {
                statusView.setText(R.string.watchlist_import_ready_soft)
            }
            onLogin -> {
                statusView.setText(R.string.watchlist_import_login_hint)
            }
            else -> {
                // Warm homepage / challenge — keep the login hint, never claim ready.
                statusView.setText(R.string.watchlist_import_login_hint)
            }
        }
    }

    private fun looksLoggedIn(
        cookies: String,
        html: String = lastPageHtml,
        pageUrl: String? = lastPageUrl,
    ): Boolean {
        if (cookies.isBlank()) return false
        return SerienStreamBypassHelper.looksLikeAuthenticatedSession(
            cookieHeader = cookies,
            html = html,
            leftLoginAfterVisit = leftLoginAfterVisit,
            pageUrl = pageUrl,
        )
    }

    /**
     * After a credential submit, SerienStream may land on `/` before auth chrome
     * is obvious. One soft navigation to `/account` confirms the Laravel session.
     */
    private fun maybeVerifyAccountAfterLogin(currentUrl: String?) {
        if (!saveSessionOnly || accountVerifyRequested || sessionAutoSaved) return
        if (!leftLoginAfterVisit) return
        val alreadyOnAccount = currentUrl.orEmpty().contains("/account", ignoreCase = true)
        if (alreadyOnAccount) return
        accountVerifyRequested = true
        webView.postDelayed({
            if (isFinishing || sessionAutoSaved) return@postDelayed
            if (!leftLoginAfterVisit) return@postDelayed
            runCatching { webView.loadUrl("$hostBase/account") }
        }, 700L)
    }

    private fun cookieHeader(): String {
        val hosts = linkedSetOf(
            hostBase,
            "$hostBase/",
            hostBase.replace("https://", "http://"),
        )
        when (source) {
            WatchlistImporter.Source.SERIENSTREAM -> {
                SerienStreamProvider.candidateDomains().forEach { domain ->
                    hosts += "https://$domain/"
                    hosts += "http://$domain/"
                }
            }
            WatchlistImporter.Source.ANIWORLD -> {
                hosts += listOf("https://aniworld.to/", "http://aniworld.to/")
            }
        }
        val merged = linkedMapOf<String, String>()
        for (host in hosts) {
            CookieManager.getInstance().getCookie(host)
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.contains('=') }
                ?.forEach { part ->
                    val key = part.substringBefore('=').trim().lowercase()
                    if (key.isNotBlank()) merged[key] = part
                }
        }
        return merged.values.joinToString("; ")
    }

    private fun saveSessionAndFinish() {
        val cookies = cookieHeader()
        if (cookies.isBlank()) {
            sessionAutoSaved = false
            notifyUser(R.string.watchlist_import_login_hint)
            return
        }
        SerienStreamBypassHelper.applyCookies("$hostBase/", cookies)
        val saved = when (source) {
            WatchlistImporter.Source.SERIENSTREAM -> {
                if (saveSessionOnly) {
                    com.dskja.betterstreamflix.providers.SerienStreamAuthManager.persistAccountLogin(
                        cookieHeader = cookies,
                        htmlProof = lastPageHtml,
                        leftLoginAfterVisit = leftLoginAfterVisit,
                        pageUrl = lastPageUrl ?: webView.url,
                    )
                } else {
                    com.dskja.betterstreamflix.providers.SerienStreamAuthManager.persist(cookies)
                }
            }
            WatchlistImporter.Source.ANIWORLD -> {
                if (saveSessionOnly) {
                    com.dskja.betterstreamflix.providers.AniWorldAuthManager.persistAccountLogin(
                        cookieHeader = cookies,
                        htmlProof = lastPageHtml,
                        leftLoginAfterVisit = leftLoginAfterVisit,
                        pageUrl = lastPageUrl ?: webView.url,
                    )
                } else {
                    com.dskja.betterstreamflix.providers.AniWorldAuthManager.persist(cookies)
                }
            }
        }
        if (!saved) {
            sessionAutoSaved = false
            notifyUser(R.string.watchlist_import_not_logged_in)
            return
        }
        sessionAutoSaved = true
        val savedMsg = when (source) {
            WatchlistImporter.Source.SERIENSTREAM ->
                R.string.settings_serienstream_session_login_saved
            WatchlistImporter.Source.ANIWORLD ->
                R.string.settings_aniworld_session_login_saved
        }
        notifyUserAndFinish(savedMsg)
    }

    /**
     * After a successful WebView login the page redirects away from /login with
     * account cookies. Persist automatically in session-only mode so the user
     * does not have to tap Save again — but never after a mere warm homepage.
     */
    private fun maybeAutoSaveSession() {
        if (!saveSessionOnly || sessionAutoSaved || importing) return
        if (!leftLoginAfterVisit) return
        val cookies = cookieHeader()
        if (!SerienStreamBypassHelper.looksLikeAuthenticatedSession(
                cookieHeader = cookies,
                html = lastPageHtml,
                leftLoginAfterVisit = true,
                pageUrl = lastPageUrl ?: webView.url,
            )
        ) {
            return
        }
        sessionAutoSaved = true
        webView.postDelayed({
            if (isFinishing) return@postDelayed
            saveSessionAndFinish()
        }, 450L)
    }

    private fun runImport() {
        if (importing) return
        val cookies = cookieHeader()
        if (cookies.isBlank()) {
            notifyUser(R.string.watchlist_import_login_hint)
            return
        }
        // Persist a working cookie jar for later sessions.
        when (source) {
            WatchlistImporter.Source.SERIENSTREAM -> {
                SerienStreamBypassHelper.applyCookies("$hostBase/", cookies)
                webView.url?.takeIf { it.isNotBlank() }?.let {
                    SerienStreamBypassHelper.applyCookies(it, cookies)
                }
                com.dskja.betterstreamflix.providers.SerienStreamAuthManager.persist(cookies)
            }
            WatchlistImporter.Source.ANIWORLD -> {
                SerienStreamBypassHelper.applyCookies("$hostBase/", cookies)
                webView.url?.takeIf { it.isNotBlank() }?.let {
                    SerienStreamBypassHelper.applyCookies(it, cookies)
                }
                com.dskja.betterstreamflix.providers.AniWorldAuthManager.persist(cookies)
            }
        }

        importing = true
        importButton.isEnabled = false
        cancelButton.isEnabled = false
        statusView.setText(R.string.watchlist_import_progress)

        lifecycleScope.launch {
            val collected = linkedMapOf<String, WatchlistImporter.ImportedItem>()
            val errors = mutableListOf<String>()
            var pagesScraped = 0
            val baseUrl = "$hostBase/"

            try {
                val startUrls = WatchlistImporter.watchlistUrls(source, 1, hostBase)
                var nextUrl: String? = startUrls.first()
                var page = 1
                val visited = linkedSetOf<String>()
                var startUrlIndex = 0

                while (nextUrl != null && page <= WatchlistImporter.MAX_PAGES) {
                    if (!visited.add(normalizeVisitKey(nextUrl))) {
                        // Try alternate start URL patterns on page 1
                        if (page == 1 && startUrlIndex + 1 < startUrls.size) {
                            startUrlIndex++
                            nextUrl = startUrls[startUrlIndex]
                            continue
                        }
                        break
                    }
                    statusView.text = getString(R.string.watchlist_import_progress_page, page)
                    val pageResult = loadHtmlInWebView(nextUrl)
                    val html = pageResult.html
                    val finalUrl = pageResult.url

                    if (WatchlistImporter.looksLikeChallengePage(html)) {
                        // Give the challenge a chance to finish, then re-extract once.
                        delay(2_500)
                        val retried = extractHtmlNow(finalUrl)
                        if (WatchlistImporter.looksLikeChallengePage(retried.html)) {
                            errors += getString(R.string.watchlist_import_challenge)
                            break
                        }
                        val parsedRetry = withContext(Dispatchers.Default) {
                            WatchlistImporter.parseItems(retried.html, baseUrl, source)
                        }
                        parsedRetry.forEach { collected.putIfAbsent(it.id, it) }
                        pagesScraped++
                        nextUrl = WatchlistImporter.findNextPageUrl(
                            retried.html,
                            retried.url,
                            baseUrl,
                        ) ?: WatchlistImporter.watchlistUrls(source, page + 1, hostBase).firstOrNull()
                        page++
                        continue
                    }
                    if (WatchlistImporter.looksLikeLoginPage(html, finalUrl)) {
                        errors += getString(R.string.watchlist_import_not_logged_in)
                        break
                    }

                    val parsed = withContext(Dispatchers.Default) {
                        WatchlistImporter.parseItems(html, baseUrl, source)
                    }
                    val before = collected.size
                    parsed.forEach { collected.putIfAbsent(it.id, it) }
                    pagesScraped++
                    Log.i(TAG, "Page $page ($finalUrl): +${parsed.size} items (total ${collected.size})")

                    // If page 1 is empty, try alternate watchlist URL shapes before giving up.
                    if (parsed.isEmpty() && page == 1 && startUrlIndex + 1 < startUrls.size) {
                        startUrlIndex++
                        nextUrl = startUrls[startUrlIndex]
                        continue
                    }

                    val discoveredNext = WatchlistImporter.findNextPageUrl(html, finalUrl, baseUrl)
                    nextUrl = when {
                        discoveredNext != null -> discoveredNext
                        parsed.isNotEmpty() && collected.size > before -> {
                            WatchlistImporter.watchlistUrls(source, page + 1, hostBase).firstOrNull()
                        }
                        else -> null
                    }
                    if (parsed.isEmpty() && page > 1) break
                    if (parsed.isNotEmpty() && collected.size == before && discoveredNext == null) break
                    page++
                }

                if (collected.isEmpty()) {
                    statusView.setText(R.string.watchlist_import_progress_fallback)
                    val httpResult = WatchlistImporter.importViaHttp(
                        this@WatchlistImportActivity,
                        source,
                        cookies,
                    )
                    if (httpResult.importedCount > 0) {
                        finishWithSuccess(httpResult.importedCount)
                        return@launch
                    }
                    errors += httpResult.errors
                } else {
                    statusView.setText(R.string.watchlist_import_progress_saving)
                    val result = WatchlistImporter.importParsed(
                        context = this@WatchlistImportActivity,
                        source = source,
                        items = collected.values,
                        pagesScraped = pagesScraped,
                        errors = errors,
                    )
                    if (result.importedCount > 0) {
                        finishWithSuccess(result.importedCount)
                        return@launch
                    }
                    errors += result.errors
                }

                val detail = errors.firstOrNull()
                    ?: getString(R.string.watchlist_import_empty)
                statusView.text = getString(R.string.watchlist_import_failed, detail)
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                statusView.text = getString(
                    R.string.watchlist_import_failed,
                    e.message ?: "error",
                )
            } finally {
                importing = false
                importButton.isEnabled = true
                cancelButton.isEnabled = true
                pageFinishedCallback = null
            }
        }
    }


    private fun notifyUser(message: CharSequence, titleRes: Int = R.string.settings_watchlist_import_title) {
        ExpDialogChrome.notify(this, message, titleRes) { ctx ->
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
        }
    }

    private fun notifyUser(messageRes: Int, titleRes: Int = R.string.settings_watchlist_import_title) {
        notifyUser(getString(messageRes), titleRes)
    }

    private fun notifyUserAndFinish(
        message: CharSequence,
        titleRes: Int = R.string.settings_watchlist_import_title,
    ) {
        if (!ExperimentalMobileDesign.enabled()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val glass = ExpDialogChrome.buildGlassMessage(this, message)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(glass.root)
            .setPositiveButton(android.R.string.ok, null)
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.setOnShowListener { ExpDialogChrome.polishGlassMessageShown(dialog, glass) }
                dialog.setOnDismissListener { finish() }
                dialog.show()
            }
    }

    private fun notifyUserAndFinish(
        messageRes: Int,
        titleRes: Int = R.string.settings_watchlist_import_title,
    ) {
        notifyUserAndFinish(getString(messageRes), titleRes)
    }

    private fun finishWithSuccess(count: Int) {
        statusView.text = getString(R.string.watchlist_import_done, count)
        if (ExperimentalMobileDesign.enabled()) {
            com.dskja.betterstreamflix.utils.ExpMotion.revealHeader(statusView)
            com.dskja.betterstreamflix.utils.ExpMotion.popIn(statusView)
            statusView.postDelayed({
                notifyUserAndFinish(getString(R.string.watchlist_import_done, count))
            }, 280L)
            return
        }
        notifyUserAndFinish(getString(R.string.watchlist_import_done, count))
    }

    private data class PageHtml(val url: String, val html: String)

    private suspend fun loadHtmlInWebView(url: String): PageHtml =
        suspendCancellableCoroutine { cont ->
            var settled = false
            val finish: (String?) -> Unit = finish@{ finishedUrl ->
                if (settled) return@finish
                settled = true
                pageFinishedCallback = null
                mainHandler.post {
                    lifecycleScope.launch {
                        scrollWatchlistToEnd()
                        delay(PAGE_SETTLE_MS)
                        val html = extractDocumentHtml()
                        if (cont.isActive) {
                            cont.resume(PageHtml(finishedUrl ?: url, html))
                        }
                    }
                }
            }
            pageFinishedCallback = finish
            cont.invokeOnCancellation {
                pageFinishedCallback = null
                settled = true
            }
            val current = webView.url.orEmpty()
            if (normalizeVisitKey(current) == normalizeVisitKey(url)) {
                webView.reload()
            } else {
                webView.loadUrl(url)
            }
            mainHandler.postDelayed({
                if (!settled && cont.isActive) {
                    finish(webView.url)
                }
            }, 30_000L)
        }

    private suspend fun extractHtmlNow(fallbackUrl: String): PageHtml {
        scrollWatchlistToEnd()
        delay(PAGE_SETTLE_MS)
        return PageHtml(webView.url ?: fallbackUrl, extractDocumentHtml())
    }

    private suspend fun scrollWatchlistToEnd() {
        repeat(SCROLL_ROUNDS) { round ->
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                    """
                    (function(){
                      try {
                        var h = Math.max(
                          document.body ? document.body.scrollHeight : 0,
                          document.documentElement ? document.documentElement.scrollHeight : 0
                        );
                        window.scrollTo(0, h);
                        var btn = document.querySelector(
                          'a[rel=next], .pagination .next a, button.load-more, .loadMore, #loadMore'
                        );
                        if (btn) { try { btn.click(); } catch (e) {} }
                        return h;
                      } catch (e) { return 0; }
                    })();
                    """.trimIndent(),
                    null,
                )
            }
            delay(350L + round * 40L)
        }
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript("window.scrollTo(0, 0);", null)
        }
        delay(200L)
    }

    private suspend fun extractDocumentHtml(): String =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(EXTRACT_HTML_JS) { raw ->
                val html = decodeJavascriptValue(raw).orEmpty()
                if (cont.isActive) cont.resume(html)
            }
        }

    private fun normalizeVisitKey(url: String): String =
        url.substringBefore('#').trimEnd('/').lowercase()

    private fun decodeJavascriptValue(value: String?): String? {
        val encoded = value?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return when (val decoded = runCatching { JSONTokener(encoded).nextValue() }.getOrNull()) {
            is String -> decoded
            null -> encoded.removeSurrounding("\"")
            else -> decoded.toString()
        }
    }

    override fun onDestroy() {
        isCleaningUp = true
        pageFinishedCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            runCatching { webView.stopLoading() }
            runCatching { webView.loadUrl("about:blank") }
            runCatching {
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            }
            runCatching { webView.destroy() }
        }
        super.onDestroy()
    }
}

private const val EXTRACT_HTML_JS =
    "(function(){return document.documentElement ? document.documentElement.outerHTML : (document.body ? document.body.outerHTML : '');})();"
