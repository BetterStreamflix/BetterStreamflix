package com.dskja.betterstreamflix.ui

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.View
import android.widget.LinearLayout

/**
 * Unified trailer playback for Movie/TV detail pages.
 * Supports in-app YouTube embed (experimental default), YouTube app, and SmartTube.
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

    const val PLAYER_ASK = "ask"
    const val PLAYER_YOUTUBE = "youtube"
    const val PLAYER_IN_APP = "in_app"
    const val PLAYER_SMARTTUBE = "smarttube"
    const val PLAYER_SMARTTUBE_STABLE = "smarttube_stable"
    const val PLAYER_SMARTTUBE_BETA = "smarttube_beta"

    const val SMARTTUBE_STABLE_PACKAGE = "org.smarttube.stable"
    const val SMARTTUBE_BETA_PACKAGE = "org.smarttube.beta"

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
        val preferred = prefs.getString(KEY_PREFERRED_PLAYER, PLAYER_IN_APP) ?: PLAYER_IN_APP
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
        val st = installedSmartTube(context)
        val items = buildList {
            add(context.getString(R.string.trailer_player_in_app))
            add(context.getString(R.string.youtube))
            if (st.isNotEmpty()) add(context.getString(R.string.smarttube))
        }
        alertBuilder(context)
            .setTitle(R.string.watch_trailer_with)
            .setItems(items.toTypedArray()) { _, which ->
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
            .create()
            .also { polishListChooser(it) }
    }

    private fun openInApp(
        context: Context,
        activity: FragmentActivity?,
        fragmentManager: androidx.fragment.app.FragmentManager?,
        trailerUrl: String,
    ) {
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
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trailerUrl))
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            ExpDialogChrome.notify(
                context,
                R.string.trailer_player_unavailable,
                R.string.settings_trailer_player_title,
            )
        }
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

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val videoId = requireArguments().getString(ARG_ID).orEmpty()
            val fallback = requireArguments().getString(ARG_URL).orEmpty()
            val watchUrl = fallback.ifBlank { "https://www.youtube.com/watch?v=$videoId" }

            val root = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_in_app_trailer, null, false)
            val web = root.findViewById<WebView>(R.id.wv_trailer)
            val loading = root.findViewById<ProgressBar>(R.id.pb_trailer_loading)
            val error = root.findViewById<TextView>(R.id.tv_trailer_error)
            webView = web

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            // Prefer a wide 16:9 plane; cap so dialog still fits on short landscape phones.
            val height = (width * 9 / 16).coerceIn(
                (200 * metrics.density).toInt(),
                (metrics.heightPixels * 0.7f).toInt(),
            )
            web.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height,
            )

            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.mediaPlaybackRequiresUserGesture = false
            web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            web.settings.loadWithOverviewMode = true
            web.settings.useWideViewPort = true
            web.webChromeClient = WebChromeClient()
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loading.visibility = android.view.View.GONE
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    resourceError: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        loading.visibility = android.view.View.GONE
                        val wasVisible = error.visibility == android.view.View.VISIBLE
                        error.visibility = android.view.View.VISIBLE
                        web.visibility = android.view.View.INVISIBLE
                        if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled() && !wasVisible) {
                            com.dskja.betterstreamflix.utils.ExpMotion.popIn(error)
                        }
                    }
                }
            }
            web.loadDataWithBaseURL(
                "https://www.youtube-nocookie.com",
                embedHtml(videoId),
                "text/html",
                "utf-8",
                null,
            )

            root.findViewById<ImageButton>(R.id.btn_trailer_close).setOnClickListener {
                com.dskja.betterstreamflix.utils.ExpMotion.hapticTap(it)
                dismissAllowingStateLoss()
            }
            root.findViewById<ImageButton>(R.id.btn_trailer_youtube).setOnClickListener {
                com.dskja.betterstreamflix.utils.ExpMotion.hapticTap(it)
                openYoutube(requireContext(), watchUrl)
            }
            root.findViewById<TextView>(R.id.btn_trailer_open_external).setOnClickListener {
                com.dskja.betterstreamflix.utils.ExpMotion.hapticTap(it)
                openYoutube(requireContext(), watchUrl)
                dismissAllowingStateLoss()
            }
            if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
            root.findViewById<TextView>(R.id.btn_trailer_open_external)?.setBackgroundResource(
                    com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.primaryButtonBackground(),
                )
                root.findViewById<ImageButton>(R.id.btn_trailer_close)?.setBackgroundResource(
                    com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.iconChipBackground(),
                )
                root.findViewById<ImageButton>(R.id.btn_trailer_youtube)?.setBackgroundResource(
                    com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.iconChipBackground(),
                )
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                    root.findViewById<TextView>(R.id.btn_trailer_open_external)?.applyExpPress()
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
                            setLayout(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            setBackgroundDrawableResource(android.R.color.transparent)
                            setDimAmount(0.82f)
                        }
                        if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
                            root.setBackgroundResource(
                                com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.bottomSheetBackground(),
                            )
                            com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.applyReducedGlass(root)
                            com.dskja.betterstreamflix.utils.ExpMotion.enterScreen(root)
                            com.dskja.betterstreamflix.utils.ExpMotion.revealHeader(
                                root.findViewById(R.id.tv_trailer_title),
                                root.findViewById(R.id.v_trailer_accent_rule),
                            )
                            com.dskja.betterstreamflix.utils.ExpMotion.pulseAccentRule(
                                root.findViewById(R.id.v_trailer_accent_rule),
                            )
                            com.dskja.betterstreamflix.utils.ExpMotion.popIn(
                                root.findViewById(R.id.btn_trailer_close),
                            )
                            com.dskja.betterstreamflix.utils.ExpMotion.popIn(
                                root.findViewById(R.id.btn_trailer_open_external),
                            )
                        }
                    }
                }
        }

        private fun destroyWeb() {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                runCatching { clearHistory() }
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
            webView = null
        }

        override fun onDestroyView() {
            destroyWeb()
            super.onDestroyView()
        }

        private fun embedHtml(videoId: String): String = """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1"/>
            <style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden;}
            .wrap{position:relative;width:100%;height:100%;}
            iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0;}</style>
            </head><body><div class="wrap">
            <iframe src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&rel=0&modestbranding=1&playsinline=1"
              allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe>
            </div></body></html>
        """.trimIndent()

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
