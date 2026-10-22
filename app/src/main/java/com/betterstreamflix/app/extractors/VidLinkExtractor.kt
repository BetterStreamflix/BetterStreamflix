package com.betterstreamflix.app.extractors

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.betterstreamflix.app.BetterStreamflixApp
import com.betterstreamflix.app.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VidLinkExtractor : Extractor() {

    override val name = "VidLink"
    override val mainUrl = "https://vidlink.pro"

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Movie -> "$mainUrl/movie/${videoType.id}"
                is Video.Type.Episode -> "$mainUrl/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
            },
        )
    }

    private fun isSafePlaylistUrl(playlist: String): Boolean {
        if (playlist.isBlank()) return false
        val uri = runCatching { java.net.URI(playlist) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank()) return false
        // Reject obvious local / metadata pivots from the JS bridge.
        if (host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1") return false
        if (host == "169.254.169.254" || host == "metadata.google.internal") return false
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("127.")) return false
        return true
    }

    override suspend fun extract(link: String): Video {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(BetterStreamflixApp.instance.applicationContext)

                // Configure WebView settings (SEC-WV-04)
                webView.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                val timeoutHandler = Handler(Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (continuation.isActive) {
                        continuation.resumeWithException(Exception("Timeout waiting for stream"))
                        webView.destroy()
                    }
                }
                // Shorter than the old 30s — EN stubs must not burn VIDEO_RESOLVE_TIMEOUT alone.
                timeoutHandler.postDelayed(timeoutRunnable, 18_000L)

                // Cancel may fire off the main thread — WebView requires main (see 2T).
                continuation.invokeOnCancellation {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    val tearDown = Runnable {
                        runCatching {
                            webView.stopLoading()
                            webView.destroy()
                        }
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        tearDown.run()
                    } else {
                        timeoutHandler.post(tearDown)
                    }
                }

                fun finishWithJson(jsonData: String) {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    if (!continuation.isActive) return
                    try {
                        val json = JSONObject(jsonData)
                        if (!json.has("stream")) {
                            continuation.resumeWithException(Exception("Stream data missing in response"))
                            return
                        }
                        val stream = json.getJSONObject("stream")
                        val playlist = stream.optString("playlist")
                        if (!isSafePlaylistUrl(playlist)) {
                            continuation.resumeWithException(
                                Exception("Rejected playlist URL from WebView bridge"),
                            )
                            return
                        }
                        val captionsList = mutableListOf<Video.Subtitle>()
                        val captions = stream.optJSONArray("captions")
                        if (captions != null) {
                            for (i in 0 until captions.length()) {
                                val cap = captions.getJSONObject(i)
                                captionsList.add(
                                    Video.Subtitle(cap.optString("language"), cap.optString("id")),
                                )
                            }
                        }
                        continuation.resume(
                            Video(
                                source = playlist,
                                subtitles = captionsList,
                                headers = mapOf("Referer" to mainUrl),
                            ),
                        )
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    } finally {
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                    }
                }

                val jsInterceptor = """
                    (function() {
                        if (window.__vidlinkFetchHooked) return;
                        window.__vidlinkFetchHooked = true;
                        const originalFetch = window.fetch;
                        window.fetch = async function(...args) {
                            const response = await originalFetch(...args);
                            const clone = response.clone();
                            const url = response.url;
                            if (url.includes('/api/b/')) {
                                clone.json().then(data => {
                                     window.Android.onStreamFound(JSON.stringify(data));
                                }).catch(err => {});
                            }
                            return response;
                        };
                    })();
                """.trimIndent()

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        // Hook fetch before page scripts run — onPageFinished is too late.
                        view?.evaluateJavascript(jsInterceptor, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Re-inject in case the page replaced window.fetch after start.
                        view?.evaluateJavascript(jsInterceptor, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString().orEmpty()
                        if (reqUrl.contains("/api/b/") && continuation.isActive) {
                            // Best-effort: cannot read body from intercept without proxying;
                            // rely on JS hook for JSON. Still re-inject early if navigations hop.
                            Handler(Looper.getMainLooper()).post {
                                view?.evaluateJavascript(jsInterceptor, null)
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webView.addJavascriptInterface(object : Any() {
                    @JavascriptInterface
                    fun onStreamFound(jsonData: String) {
                        finishWithJson(jsonData)
                    }
                }, "Android")

                webView.loadUrl(link)
            }
        }
    }
}
