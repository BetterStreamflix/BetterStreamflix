package com.dskja.betterstreamflix.experimental

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Category
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hosts the Vite/React experimental shell inside a WebView and bridges catalog + navigation
 * back to native fragments.
 */
class ExperimentalReactShell(
    private val fragment: Fragment,
    private val onNavigate: (dest: String) -> Unit,
    private val onOpenShow: (id: String, kind: String) -> Unit,
) {
    private var webView: WebView? = null
    private var loading: ProgressBar? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(root: View) {
        loading = root.findViewById(R.id.experimental_react_loading)
        webView = root.findViewById(R.id.experimental_react_webview)
        val wv = webView ?: return
        wv.setBackgroundColor(Color.parseColor("#07080C"))
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
        }
        wv.addJavascriptInterface(Bridge(), "BetterStreamflixNative")
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loading?.isVisible = newProgress in 1..99
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pushBootstrap()
            }
        }
        wv.loadUrl(ASSET_URL)
    }

    fun detach() {
        webView?.apply {
            removeJavascriptInterface("BetterStreamflixNative")
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        loading = null
    }

    fun pushCatalog(categories: List<Category>) {
        val rails = JSONArray()
        categories.take(10).forEach { category ->
            val items = JSONArray()
            category.list.take(28).forEach { item ->
                when (item) {
                    is Movie -> items.put(
                        JSONObject()
                            .put("id", item.id)
                            .put("title", item.title)
                            .put("subtitle", "Movie")
                            .put("poster", item.poster.orEmpty())
                            .put("kind", "movie"),
                    )
                    is TvShow -> items.put(
                        JSONObject()
                            .put("id", item.id)
                            .put("title", item.title)
                            .put(
                                "subtitle",
                                item.overview?.take(42).orEmpty().ifBlank { "Series" },
                            )
                            .put("poster", item.poster.orEmpty())
                            .put("kind", "tv"),
                    )
                }
            }
            if (items.length() > 0) {
                rails.put(
                    JSONObject()
                        .put("id", category.name)
                        .put("title", category.name)
                        .put("items", items),
                )
            }
        }
        evaluateReceive(JSONObject().put("rails", rails).put("status", ""))
    }

    fun pushStatus(message: String) {
        evaluateReceive(JSONObject().put("status", message))
    }

    fun pushBootstrap() {
        val profile = ProfileManager.activeProfile()
        evaluateReceive(
            JSONObject()
                .put(
                    "profile",
                    JSONObject()
                        .put("name", profile?.displayName ?: "You")
                        .put("accent", ExperimentalMobileDesign.accentCssHex())
                        .put("kids", profile?.isKids == true),
                )
                .put("status", ""),
        )
    }

    private fun evaluateReceive(payload: JSONObject) {
        val wv = webView ?: return
        val js = "window.__luminaReceive && window.__luminaReceive($payload);"
        wv.post {
            runCatching { wv.evaluateJavascript(js, null) }
                .onFailure { Log.w(TAG, "push failed: ${it.message}") }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
            val action = json.optString("action")
            fragment.view?.post {
                if (!fragment.isAdded) return@post
                when (action) {
                    "ready" -> pushBootstrap()
                    "navigate" -> onNavigate(json.optString("dest"))
                    "open" -> onOpenShow(json.optString("id"), json.optString("kind"))
                }
            }
        }
    }

    companion object {
        private const val TAG = "LuminaReact"
        const val ASSET_URL = "file:///android_asset/experimental/index.html"
    }
}
