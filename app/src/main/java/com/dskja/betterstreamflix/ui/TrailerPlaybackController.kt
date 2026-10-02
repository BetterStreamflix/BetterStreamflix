package com.dskja.betterstreamflix.ui

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Unified trailer playback for Movie/TV detail pages.
 * Supports in-app YouTube embed, YouTube app, and SmartTube.
 * On leanback/Fire TV, prefers native apps over the in-app WebView.
 *
 * In-app WebView sessions must pause/release on navigate-away so Home stays silent.
 */
object TrailerPlaybackController {
    private fun alertBuilder(context: Context) =
        if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(context)
        } else {
            AlertDialog.Builder(context)
        }

    private const val TAG = "TrailerPlayback"
    const val KEY_PREFERRED_PLAYER = "preferred_player"
    const val KEY_SMARTTUBE_PACKAGE = "preferred_smarttube_package"

    /**
     * Active in-app trailer surfaces (detail tab WebView, dialog embed).
     * Weak set so recycled/destroyed players drop out without explicit unregister races.
     */
    fun interface ActiveSession {
        /** Pause media and blank the WebView so audio cannot leak. */
        fun silence()
    }

    private val activeSessions =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ActiveSession, Boolean>())

    fun registerActiveSession(session: ActiveSession) {
        synchronized(activeSessions) { activeSessions.add(session) }
    }

    fun unregisterActiveSession(session: ActiveSession) {
        synchronized(activeSessions) { activeSessions.remove(session) }
    }

    /** Pause/blank every registered in-app trailer. Safe from Home / detail onPause. */
    fun silenceAllActive() {
        val snapshot: List<ActiveSession>
        synchronized(activeSessions) {
            snapshot = activeSessions.toList()
        }
        snapshot.forEach { session ->
            runCatching { session.silence() }
        }
    }

    /** Pause a YouTube IFrame API player inside [web] without tearing the document down. */
    fun pauseTrailerMedia(web: WebView?) {
        web ?: return
        runCatching {
            web.evaluateJavascript(
                "try{if(window.AndroidTrailerPlayer){window.AndroidTrailerPlayer.pauseVideo();" +
                    "window.AndroidTrailerPlayer.mute();}}" +
                    "catch(e){}" +
                    "try{document.querySelectorAll('video,audio').forEach(function(m){" +
                    "try{m.pause();m.muted=true;}catch(x){}});}catch(e){}",
                null,
            )
        }
    }

    const val PLAYER_ASK = "ask"
    const val PLAYER_YOUTUBE = "youtube"
    const val PLAYER_IN_APP = "in_app"
    const val PLAYER_SMARTTUBE = "smarttube"
    const val PLAYER_SMARTTUBE_STABLE = "smarttube_stable"
    const val PLAYER_SMARTTUBE_BETA = "smarttube_beta"

    const val SMARTTUBE_STABLE_PACKAGE = "org.smarttube.stable"
    const val SMARTTUBE_BETA_PACKAGE = "org.smarttube.beta"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    /** Common YouTube TV package ids across OEM builds. */
    const val YOUTUBE_TV_PACKAGE = "com.google.android.tv.youtube"
    const val YOUTUBE_TV_PACKAGE_ALT = "com.google.android.youtube.tv"

    private fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    fun play(fragment: Fragment, trailerUrl: String) {
        val context = fragment.requireContext()
        play(context, fragment.activity, trailerUrl, fragment.childFragmentManager)
    }

    fun play(
        context: Context,
        activity: FragmentActivity?,
        trailerUrl: String,
        fragmentManager: androidx.fragment.app.FragmentManager? = activity?.supportFragmentManager,
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getString(KEY_PREFERRED_PLAYER, null)
        val preferred = resolvePreferredPlayer(context, stored)
        when (preferred) {
            PLAYER_IN_APP -> openInApp(context, activity, fragmentManager, trailerUrl)
            PLAYER_YOUTUBE -> openYoutube(context, trailerUrl)
            PLAYER_SMARTTUBE_STABLE -> launchSmartTube(context, SMARTTUBE_STABLE_PACKAGE, trailerUrl)
            PLAYER_SMARTTUBE_BETA -> launchSmartTube(context, SMARTTUBE_BETA_PACKAGE, trailerUrl)
            PLAYER_SMARTTUBE -> handleSmartTube(context, trailerUrl)
            PLAYER_ASK -> showChooser(context, activity, fragmentManager, trailerUrl)
            else -> openInApp(context, activity, fragmentManager, trailerUrl)
        }
    }

    /**
     * Leanback / Fire TV: never silently open the in-app WebView when the user
     * has not chosen a player — prefer SmartTube-stable → YouTube app → Ask.
     */
    fun resolvePreferredPlayer(context: Context, stored: String?): String {
        val leanback = DeviceCapabilities.isLeanbackDevice(context) ||
            DeviceCapabilities.isAmazonFireTv(context)
        val st = installedSmartTube(context)
        val youtubeInstalled = isPackageInstalled(context, YOUTUBE_TV_PACKAGE) ||
            isPackageInstalled(context, YOUTUBE_TV_PACKAGE_ALT) ||
            isPackageInstalled(context, YOUTUBE_PACKAGE)
        return resolvePreferredPlayer(
            stored = stored,
            leanback = leanback,
            smartTubeStableInstalled = st.contains(SMARTTUBE_STABLE_PACKAGE),
            smartTubeAnyInstalled = st.isNotEmpty(),
            youtubeInstalled = youtubeInstalled,
        )
    }

    /**
     * Pure preferred-player resolution (unit-testable).
     * Mobile / non-leanback keeps today's default ([PLAYER_IN_APP] when unset).
     * Leanback overrides unset/[PLAYER_IN_APP] so first play never silently WebViews.
     */
    fun resolvePreferredPlayer(
        stored: String?,
        leanback: Boolean,
        smartTubeStableInstalled: Boolean,
        smartTubeAnyInstalled: Boolean,
        youtubeInstalled: Boolean,
    ): String {
        if (!leanback) {
            return stored?.takeIf { it.isNotBlank() } ?: PLAYER_IN_APP
        }
        if (!stored.isNullOrBlank() && stored != PLAYER_IN_APP) {
            return stored
        }
        return when {
            smartTubeStableInstalled -> PLAYER_SMARTTUBE_STABLE
            smartTubeAnyInstalled -> PLAYER_SMARTTUBE
            youtubeInstalled -> PLAYER_YOUTUBE
            else -> PLAYER_ASK
        }
    }

    /** Shared YouTube-nocookie embed HTML for dialog + in-tab players. */
    fun embedHtml(
        videoId: String,
        autoplay: Boolean = true,
        muted: Boolean = false,
        useIframeApi: Boolean = false,
    ): String {
        if (!useIframeApi) {
            val muteParam = if (muted) "&amp;mute=1" else ""
            val autoParam = if (autoplay) "1" else "0"
            return """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1"/>
            <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden;}
            .wrap{position:absolute;inset:0;width:100%;height:100%;}
            iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0;}</style>
            </head><body><div class="wrap">
            <iframe
              src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=$autoParam&amp;rel=0&amp;modestbranding=1&amp;playsinline=1&amp;fs=1$muteParam"
              allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
              allowfullscreen
              referrerpolicy="strict-origin-when-cross-origin"></iframe>
            </div></body></html>
            """.trimIndent()
        }
        val auto = if (autoplay) 1 else 0
        val mute = if (muted) 1 else 0
        return """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1"/>
            <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden;}
            #player{position:absolute;inset:0;width:100%;height:100%;}</style>
            </head><body>
            <div id="player"></div>
            <script>
            var AndroidTrailerPlayer=null;
            function notify(fn,arg){try{if(window.AndroidTrailer&&AndroidTrailer[fn]){if(arg===undefined)AndroidTrailer[fn]();else AndroidTrailer[fn](arg);}}catch(e){}}
            function onYouTubeIframeAPIReady(){
              AndroidTrailerPlayer=new YT.Player('player',{
                width:'100%',height:'100%',
                videoId:'$videoId',
                host:'https://www.youtube-nocookie.com',
                playerVars:{autoplay:$auto,mute:$mute,rel:0,modestbranding:1,playsinline:1,fs:1,controls:1},
                events:{
                  onReady:function(e){try{if($mute){e.target.mute();}else{e.target.unMute();}}catch(x){}notify('onReady');},
                  onStateChange:function(e){
                    if(e.data===YT.PlayerState.PLAYING)notify('onPlaying');
                    else if(e.data===YT.PlayerState.PAUSED)notify('onPaused');
                    else if(e.data===YT.PlayerState.ENDED)notify('onEnded');
                  },
                  onError:function(e){notify('onError',e.data||0);}
                }
              });
            }
            var tag=document.createElement('script');
            tag.src='https://www.youtube.com/iframe_api';
            document.head.appendChild(tag);
            </script>
            </body></html>
        """.trimIndent()
    }

    fun configureTrailerWebView(web: WebView) {
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = true
            allowFileAccess = false
        }
    }

    fun loadTrailerEmbed(
        web: WebView,
        videoId: String,
        autoplay: Boolean = true,
        muted: Boolean = false,
        useIframeApi: Boolean = false,
    ) {
        web.loadDataWithBaseURL(
            "https://www.youtube-nocookie.com",
            embedHtml(videoId, autoplay = autoplay, muted = muted, useIframeApi = useIframeApi),
            "text/html",
            "utf-8",
            null,
        )
    }

    fun destroyTrailerWebView(web: WebView?) {
        web ?: return
        web.stopLoading()
        web.loadUrl("about:blank")
        runCatching { web.clearHistory() }
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
    }

    fun openExternalYoutube(context: Context, trailerUrl: String) {
        openYoutube(context, trailerUrl)
    }

    fun youtubeVideoId(trailerUrl: String): String? {
        if (trailerUrl.isBlank()) return null
        Regex("""(?:v=|youtu\.be/|embed/|shorts/)([A-Za-z0-9_-]{6,})""")
            .find(trailerUrl)?.groupValues?.getOrNull(1)
            ?.let { return it }
        // Fallback for short paths / odd hosts when Android Uri is available.
        return runCatching {
            val uri = Uri.parse(trailerUrl)
            val host = uri.host.orEmpty().lowercase()
            when {
                host.contains("youtu.be") -> uri.lastPathSegment?.takeIf { it.length >= 6 }
                host.contains("youtube") -> uri.getQueryParameter("v")
                    ?: uri.pathSegments?.let { segs ->
                        val embedIdx = segs.indexOf("embed")
                        if (embedIdx >= 0 && embedIdx + 1 < segs.size) return@let segs[embedIdx + 1]
                        val shortsIdx = segs.indexOf("shorts")
                        if (shortsIdx >= 0 && shortsIdx + 1 < segs.size) return@let segs[shortsIdx + 1]
                        null
                    }
                else -> null
            }
        }.getOrNull()
    }

    private fun showChooser(
        context: Context,
        activity: FragmentActivity?,
        fragmentManager: androidx.fragment.app.FragmentManager?,
        trailerUrl: String,
    ) {
        val leanback = DeviceCapabilities.isLeanbackDevice(context) ||
            DeviceCapabilities.isAmazonFireTv(context)
        val st = installedSmartTube(context)
        val items = buildList {
            // Leanback: never offer the phone WebView dialog — native apps only.
            if (!leanback) add(context.getString(R.string.trailer_player_in_app))
            add(context.getString(R.string.youtube))
            if (st.isNotEmpty()) add(context.getString(R.string.smarttube))
        }
        alertBuilder(context)
            .setTitle(R.string.watch_trailer_with)
            .setItems(items.toTypedArray()) { _, which ->
                if (leanback) {
                    when (which) {
                        0 -> openYoutube(context, trailerUrl)
                        else -> {
                            if (st.size > 1) {
                                showSmartTubeVersionDialog(context, st, trailerUrl, save = false)
                            } else {
                                launchSmartTube(context, st.first(), trailerUrl)
                            }
                        }
                    }
                } else {
                    when (which) {
                        0 -> openInApp(context, activity, fragmentManager, trailerUrl)
                        1 -> openYoutube(context, trailerUrl)
                        else -> {
                            if (st.size > 1) {
                                showSmartTubeVersionDialog(context, st, trailerUrl, save = false)
                            } else {
                                launchSmartTube(context, st.first(), trailerUrl)
                            }
                        }
                    }
                }
            }
            .create()
            .also { polishListChooser(it) }
    }

    private fun openInApp(
        context: Context,
        activity: FragmentActivity?,
        fragmentManager: androidx.fragment.app.FragmentManager?,
        trailerUrl: String,
    ) {
        // Leanback / Fire TV: WebView trailers are fragile — prefer native apps.
        if (DeviceCapabilities.isLeanbackDevice(context) ||
            DeviceCapabilities.isAmazonFireTv(context)
        ) {
            val st = installedSmartTube(context)
            when {
                st.contains(SMARTTUBE_STABLE_PACKAGE) ->
                    launchSmartTube(context, SMARTTUBE_STABLE_PACKAGE, trailerUrl)
                st.isNotEmpty() -> launchSmartTube(context, st.first(), trailerUrl)
                else -> openYoutube(context, trailerUrl)
            }
            return
        }
        val id = youtubeVideoId(trailerUrl)
        if (id.isNullOrBlank() || fragmentManager == null || activity == null) {
            openYoutube(context, trailerUrl)
            return
        }
        if (fragmentManager.isStateSaved) {
            openYoutube(context, trailerUrl)
            return
        }
        InAppTrailerDialog.newInstance(id, trailerUrl)
            .show(fragmentManager, "in_app_trailer")
    }

    private fun openYoutube(context: Context, trailerUrl: String) {
        val uri = Uri.parse(trailerUrl)
        val candidates = buildList {
            if (DeviceCapabilities.isLeanbackDevice(context) ||
                DeviceCapabilities.isAmazonFireTv(context)
            ) {
                add(YOUTUBE_TV_PACKAGE)
                add(YOUTUBE_TV_PACKAGE_ALT)
            }
            add(YOUTUBE_PACKAGE)
        }.distinct().filter { isPackageInstalled(context, it) }

        for (pkg in candidates) {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage(pkg) }
            val launched = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return
        }

        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            ExpDialogChrome.notify(
                context,
                R.string.trailer_player_unavailable,
                R.string.settings_trailer_player_title,
            )
        }
    }

    /** Prefer YouTube TV packages on Leanback; null when none installed. */
    fun preferredYoutubePackage(context: Context): String? {
        val leanback = DeviceCapabilities.isLeanbackDevice(context) ||
            DeviceCapabilities.isAmazonFireTv(context)
        val order = if (leanback) {
            listOf(YOUTUBE_TV_PACKAGE, YOUTUBE_TV_PACKAGE_ALT, YOUTUBE_PACKAGE)
        } else {
            listOf(YOUTUBE_PACKAGE, YOUTUBE_TV_PACKAGE, YOUTUBE_TV_PACKAGE_ALT)
        }
        return order.firstOrNull { isPackageInstalled(context, it) }
    }

    private fun handleSmartTube(context: Context, trailerUrl: String) {
        val installed = installedSmartTube(context)
        when {
            installed.isEmpty() -> openYoutube(context, trailerUrl)
            installed.size == 1 -> launchSmartTube(context, installed[0], trailerUrl)
            else -> {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val saved = prefs.getString(KEY_SMARTTUBE_PACKAGE, null)
                if (saved != null && installed.contains(saved)) {
                    launchSmartTube(context, saved, trailerUrl)
                } else {
                    showSmartTubeVersionDialog(context, installed, trailerUrl, save = true)
                }
            }
        }
    }

    private fun showSmartTubeVersionDialog(
        context: Context,
        packages: List<String>,
        trailerUrl: String,
        save: Boolean,
    ) {
        val labels = packages.map { pkg ->
            if (pkg == SMARTTUBE_STABLE_PACKAGE) context.getString(R.string.smarttube_stable)
            else context.getString(R.string.smarttube_beta)
        }.toTypedArray()
        alertBuilder(context)
            .setTitle(R.string.smarttube)
            .setItems(labels) { _, which ->
                val pkg = packages[which]
                if (save) {
                    PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putString(KEY_SMARTTUBE_PACKAGE, pkg)
                        .apply()
                }
                launchSmartTube(context, pkg, trailerUrl)
            }
            .create()
            .also { polishListChooser(it) }
    }

    /** Shared list-chooser polish (accent rule + row ExpPress) for trailer ASK/SmartTube dialogs. */
    fun polishChooserDialog(dialog: AlertDialog) = polishListChooser(dialog)

    private fun polishListChooser(dialog: AlertDialog) {
        dialog.setOnShowListener {
            ExpDialogChrome.polishShown(dialog)
            if (!ExperimentalMobileDesign.enabled()) return@setOnShowListener
            val list = dialog.listView ?: return@setOnShowListener
            list.divider = null
            list.dividerHeight = 0
            list.setPadding(
                list.paddingLeft,
                (8 * list.resources.displayMetrics.density).toInt(),
                list.paddingRight,
                list.paddingBottom,
            )
            list.post {
                for (i in 0 until list.childCount) {
                    val row = list.getChildAt(i) ?: continue
                    with(ExpPressEffects) { row.applyExpPress() }
                    row.postDelayed({ ExpMotion.popIn(row) }, 28L * i)
                }
            }
            // Accent rule above the list when Exp is on
            val parent = list.parent as? ViewGroup ?: return@setOnShowListener
            if (parent.findViewWithTag<View>("exp_trailer_rule") != null) return@setOnShowListener
            val density = list.resources.displayMetrics.density
            val rule = View(list.context).apply {
                tag = "exp_trailer_rule"
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(),
                    (3 * density).toInt(),
                ).also {
                    it.marginStart = (24 * density).toInt()
                    it.bottomMargin = (4 * density).toInt()
                }
                setBackgroundResource(R.drawable.bg_exp_accent_rule)
            }
            val index = parent.indexOfChild(list).coerceAtLeast(0)
            parent.addView(rule, index)
            ExpMotion.pulseAccentRule(rule)
        }
        dialog.show()
    }

    private fun launchSmartTube(context: Context, packageName: String, trailerUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trailerUrl)).apply {
            setPackage(packageName)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "SmartTube launch failed: ${e.message}")
            openYoutube(context, trailerUrl)
        }
    }

    private fun installedSmartTube(context: Context): List<String> {
        val pm = context.packageManager
        return listOf(SMARTTUBE_STABLE_PACKAGE, SMARTTUBE_BETA_PACKAGE).filter { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
    }

    class InAppTrailerDialog : androidx.fragment.app.DialogFragment() {
        private var webView: WebView? = null
        private var videoId: String = ""
        private var watchUrl: String = ""

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            videoId = requireArguments().getString(ARG_ID).orEmpty()
            val fallback = requireArguments().getString(ARG_URL).orEmpty()
            watchUrl = fallback.ifBlank { "https://www.youtube.com/watch?v=$videoId" }

            val root = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_in_app_trailer, null, false)
            val web = root.findViewById<WebView>(R.id.wv_trailer)
            val loading = root.findViewById<ProgressBar>(R.id.pb_trailer_loading)
            val errorPanel = root.findViewById<View>(R.id.ll_trailer_error)
            webView = web

            val metrics = resources.displayMetrics
            // Near full-width immersive plane with side inset; keep 16:9.
            val dialogWidth = (metrics.widthPixels * 0.96f).toInt()
            val playerHeight = (dialogWidth * 9 / 16).coerceIn(
                (200 * metrics.density).toInt(),
                (metrics.heightPixels * 0.72f).toInt(),
            )
            val playerParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                playerHeight,
            )
            web.layoutParams = playerParams
            root.findViewById<FrameLayout>(R.id.fl_trailer_player).minimumHeight = playerHeight

            configureTrailerWebView(web)
            web.webChromeClient = WebChromeClient()
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (errorPanel.visibility != View.VISIBLE) {
                        loading.visibility = View.GONE
                        web.visibility = View.VISIBLE
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    resourceError: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        showError(loading, web, errorPanel)
                    }
                }
            }
            loadEmbed(web, loading, errorPanel)

            fun openExternal() {
                openYoutube(requireContext(), watchUrl)
            }

            root.findViewById<ImageButton>(R.id.btn_trailer_close).setOnClickListener {
                ExpMotion.hapticTap(it)
                dismissAllowingStateLoss()
            }
            root.findViewById<ImageButton>(R.id.btn_trailer_youtube).setOnClickListener {
                ExpMotion.hapticTap(it)
                openExternal()
            }
            root.findViewById<TextView>(R.id.btn_trailer_open_external).setOnClickListener {
                ExpMotion.hapticTap(it)
                openExternal()
                dismissAllowingStateLoss()
            }
            root.findViewById<TextView>(R.id.btn_trailer_retry).setOnClickListener {
                ExpMotion.hapticTap(it)
                loadEmbed(web, loading, errorPanel)
            }
            root.findViewById<TextView>(R.id.btn_trailer_error_youtube).setOnClickListener {
                ExpMotion.hapticTap(it)
                openExternal()
                dismissAllowingStateLoss()
            }

            if (ExperimentalMobileDesign.enabled()) {
                root.findViewById<View>(R.id.v_trailer_accent_rule)?.visibility = View.VISIBLE
                with(ExpPressEffects) {
                    root.findViewById<TextView>(R.id.btn_trailer_open_external)?.applyExpPress()
                    root.findViewById<TextView>(R.id.btn_trailer_retry)?.applyExpPress()
                    root.findViewById<TextView>(R.id.btn_trailer_error_youtube)?.applyExpPress()
                    root.findViewById<ImageButton>(R.id.btn_trailer_close)?.applyExpPress()
                    root.findViewById<ImageButton>(R.id.btn_trailer_youtube)?.applyExpPress()
                }
            }

            return alertBuilder(requireContext())
                .setView(root)
                .create()
                .also { dialog ->
                    dialog.setOnDismissListener { destroyWeb() }
                    dialog.setOnShowListener {
                        dialog.window?.apply {
                            setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                            setBackgroundDrawableResource(android.R.color.transparent)
                            setDimAmount(0.88f)
                        }
                        if (ExperimentalMobileDesign.enabled()) {
                            ExpMotion.enterScreen(root)
                            ExpMotion.revealHeader(
                                root.findViewById(R.id.tv_trailer_title),
                                root.findViewById(R.id.v_trailer_accent_rule),
                            )
                            ExpMotion.pulseAccentRule(
                                root.findViewById(R.id.v_trailer_accent_rule),
                            )
                            ExpMotion.popIn(root.findViewById(R.id.btn_trailer_close))
                            ExpMotion.popIn(root.findViewById(R.id.btn_trailer_open_external))
                        }
                    }
                }
        }

        private fun loadEmbed(web: WebView, loading: ProgressBar, errorPanel: View) {
            errorPanel.visibility = View.GONE
            web.visibility = View.VISIBLE
            loading.visibility = View.VISIBLE
            loadTrailerEmbed(web, videoId)
        }

        private fun showError(loading: ProgressBar, web: WebView, errorPanel: View) {
            loading.visibility = View.GONE
            web.visibility = View.INVISIBLE
            val wasVisible = errorPanel.visibility == View.VISIBLE
            errorPanel.visibility = View.VISIBLE
            if (ExperimentalMobileDesign.enabled() && !wasVisible) {
                ExpMotion.popIn(errorPanel)
            }
        }

        private fun destroyWeb() {
            pauseTrailerMedia(webView)
            destroyTrailerWebView(webView)
            webView = null
            unregisterActiveSession(dialogSession)
        }

        private val dialogSession = ActiveSession {
            pauseTrailerMedia(webView)
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
        }

        override fun onStart() {
            super.onStart()
            registerActiveSession(dialogSession)
        }

        override fun onPause() {
            pauseTrailerMedia(webView)
            super.onPause()
        }

        override fun onStop() {
            // Leaving the dialog (home, back, overlay) must kill audio immediately.
            destroyWeb()
            super.onStop()
        }

        override fun onDestroyView() {
            destroyWeb()
            super.onDestroyView()
        }

        companion object {
            private const val ARG_ID = "id"
            private const val ARG_URL = "url"
            fun newInstance(videoId: String, trailerUrl: String) = InAppTrailerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_ID, videoId)
                    putString(ARG_URL, trailerUrl)
                }
            }
        }
    }
}

