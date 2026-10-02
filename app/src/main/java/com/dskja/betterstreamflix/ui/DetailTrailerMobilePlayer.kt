package com.dskja.betterstreamflix.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.ContentDetailTrailerMobileBinding
import com.dskja.betterstreamflix.databinding.ItemDetailTrailerRowMobileBinding
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.TmdbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared in-tab trailer stage for Movie/TV mobile detail.
 * Always uses the built-in youtube-nocookie IFrame player — independent of
 * [TrailerPlaybackController.play] preferences used by the hero CTA.
 *
 * Lifecycle: pauses/blanks on fragment pause, detach, and recycle so Home stays silent.
 */
class DetailTrailerMobilePlayer(
    private val binding: ContentDetailTrailerMobileBinding,
) : TrailerPlaybackController.ActiveSession {
    private val context = binding.root.context
    private val web: WebView = binding.wvDetailTrailer
    private val loading = binding.pbDetailTrailerLoading
    private val shimmer = binding.vDetailTrailerShimmer
    private val errorPanel = binding.llDetailTrailerError
    private val stage = binding.flDetailTrailerPlayer
    private val chromeBar = binding.llDetailTrailerChrome
    private val muteBtn: ImageButton = binding.btnDetailTrailerMute
    private val fullscreenBtn: ImageButton = binding.btnDetailTrailerFullscreen

    private var activeUrl: String? = null
    private var activeTitle: String? = null
    private var loadedUrl: String? = null
    private var contentKey: String? = null
    private var muted = true
    private var hasAutoPlayed = false
    private var configured = false
    private var fetchJob: Job? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation: Int? = null
    private var previousSystemUi: Int? = null
    private var boundLifecycleOwner: LifecycleOwner? = null

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) {
            // Detail under Home / leaving Trailer tab / app background — kill audio.
            silence()
        }

        override fun onStop(owner: LifecycleOwner) {
            silence()
        }
    }

    override fun silence() = pauseAndBlank()

    fun bind(
        seedUrl: String?,
        title: String,
        trailerLabel: String,
        tmdbId: String?,
        isTv: Boolean,
        year: Int?,
        imdbId: String?,
        sectionTag: Any?,
    ) {
        binding.root.tag = sectionTag
        ensureConfigured()
        attachLifecycle()
        TrailerPlaybackController.registerActiveSession(this)
        fetchJob?.cancel()

        val key = listOf(tmdbId.orEmpty(), imdbId.orEmpty(), title, seedUrl.orEmpty()).joinToString("|")
        if (contentKey != key) {
            contentKey = key
            hasAutoPlayed = false
            activeUrl = null
            activeTitle = null
            loadedUrl = null
            pauseAndBlank()
        }
        binding.llDetailTrailerList.removeAllViews()
        binding.llDetailTrailerRow.visibility = View.GONE
        binding.tvDetailTrailerEmpty.visibility = View.GONE
        stage.visibility = View.GONE
        chromeBar.visibility = View.GONE
        binding.tvDetailTrailerNowPlaying.visibility = View.GONE
        binding.tvDetailTrailerMoreLabel.visibility = View.GONE
        errorPanel.visibility = View.GONE
        setLoading(false)

        val seed = seedUrl?.takeIf { it.isNotBlank() }?.let { url ->
            listOf(Triple("$title $trailerLabel", url, trailerLabel))
        }.orEmpty()
        if (seed.isNotEmpty()) {
            bindRows(seed, autoplay = !hasAutoPlayed)
        }

        val owner = binding.root.findViewTreeLifecycleOwner() ?: return
        fetchJob = owner.lifecycleScope.launch {
            val remote = withContext(Dispatchers.IO) {
                TmdbUtils.listYoutubeTrailers(
                    tmdbId = tmdbId,
                    isTv = isTv,
                    title = title,
                    year = year,
                    imdbId = imdbId,
                )
            }
            val trailers = (seed + remote).distinctBy { it.second }
            bindRows(trailers, autoplay = !hasAutoPlayed)
        }
    }

    fun pauseAndBlank() {
        exitFullscreen()
        loadedUrl = null
        TrailerPlaybackController.pauseTrailerMedia(web)
        web.stopLoading()
        web.loadUrl("about:blank")
        setLoading(false)
    }

    /** Recycle / destroy path — cancel work and drop registry entry. */
    fun release() {
        fetchJob?.cancel()
        fetchJob = null
        detachLifecycle()
        TrailerPlaybackController.unregisterActiveSession(this)
        pauseAndBlank()
    }

    private fun attachLifecycle() {
        val owner = binding.root.findViewTreeLifecycleOwner() ?: return
        if (boundLifecycleOwner === owner) return
        detachLifecycle()
        boundLifecycleOwner = owner
        owner.lifecycle.addObserver(lifecycleObserver)
    }

    private fun detachLifecycle() {
        boundLifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        boundLifecycleOwner = null
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun ensureConfigured() {
        if (configured) return
        configured = true
        sizeStage()
        TrailerPlaybackController.configureTrailerWebView(web)
        web.addJavascriptInterface(TrailerJsBridge(), JS_BRIDGE)
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (errorPanel.visibility != View.VISIBLE &&
                    url != null &&
                    url != "about:blank"
                ) {
                    // Keep shimmer until IFrame ready/error events.
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                resourceError: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    showError()
                }
            }
        }
        binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                TrailerPlaybackController.registerActiveSession(this@DetailTrailerMobilePlayer)
                attachLifecycle()
            }

            override fun onViewDetachedFromWindow(v: View) {
                // Tab switch / navigate away — stop audio even if VH is cached.
                silence()
                TrailerPlaybackController.unregisterActiveSession(this@DetailTrailerMobilePlayer)
            }
        })
        muteBtn.setOnClickListener {
            ExpMotion.hapticTap(it)
            toggleMute()
        }
        fullscreenBtn.setOnClickListener {
            ExpMotion.hapticTap(it)
            requestFullscreen()
        }
        binding.btnDetailTrailerRetry.setOnClickListener {
            ExpMotion.hapticTap(it)
            val url = activeUrl ?: return@setOnClickListener
            val title = activeTitle.orEmpty()
            hasAutoPlayed = false
            playInline(title, url, forceReload = true)
        }
        muteBtn.applyExpPress()
        fullscreenBtn.applyExpPress()
        binding.btnDetailTrailerRetry.applyExpPress()
        binding.btnDetailTrailerOpenExternal.applyExpPress()
    }

    private fun sizeStage() {
        val metrics = binding.root.resources.displayMetrics
        val playerHeight = ((metrics.widthPixels - (32 * metrics.density)) * 9f / 16f)
            .toInt()
            .coerceIn((180 * metrics.density).toInt(), (metrics.heightPixels * 0.45f).toInt())
        web.layoutParams = web.layoutParams.apply { height = playerHeight }
        stage.minimumHeight = playerHeight
        shimmer.layoutParams = shimmer.layoutParams.apply { height = playerHeight }
        stage.clipToOutline = true
        stage.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    }

    private fun bindRows(trailers: List<Triple<String, String, String>>, autoplay: Boolean) {
        binding.llDetailTrailerList.removeAllViews()
        if (trailers.isEmpty()) {
            binding.tvDetailTrailerEmpty.visibility = View.VISIBLE
            stage.visibility = View.GONE
            chromeBar.visibility = View.GONE
            binding.tvDetailTrailerNowPlaying.visibility = View.GONE
            binding.tvDetailTrailerMoreLabel.visibility = View.GONE
            return
        }
        binding.tvDetailTrailerEmpty.visibility = View.GONE
        binding.tvDetailTrailerMoreLabel.visibility =
            if (trailers.size > 1) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(context)
        trailers.take(5).forEach { (rowTitle, url, type) ->
            val row = ItemDetailTrailerRowMobileBinding.inflate(
                inflater,
                binding.llDetailTrailerList,
                false,
            )
            row.root.setTag(R.id.detail_trailer_row_url_tag, url)
            row.tvDetailTrailerTitle.text = rowTitle
            row.tvDetailTrailerMeta.text = type
            val official = rowTitle.contains("Official", ignoreCase = true) ||
                rowTitle.contains("Offiziell", ignoreCase = true)
            row.tvDetailTrailerBadge.visibility = if (official) View.VISIBLE else View.GONE
            row.tvDetailTrailerDesc.visibility = View.GONE
            val ytId = TrailerPlaybackController.youtubeVideoId(url)
            if (ytId != null) {
                Glide.with(row.ivDetailTrailerThumb)
                    .load("https://img.youtube.com/vi/$ytId/hqdefault.jpg")
                    .centerCrop()
                    .into(row.ivDetailTrailerThumb)
            } else {
                row.ivDetailTrailerThumb.setImageDrawable(null)
            }
            val play = View.OnClickListener {
                ExpMotion.hapticTap(it)
                playInline(rowTitle, url, forceReload = true)
            }
            row.root.setOnClickListener(play)
            row.ivDetailTrailerPlay.setOnClickListener(play)
            binding.llDetailTrailerList.addView(row.root)
        }

        val preferred = activeUrl?.takeIf { url -> trailers.any { it.second == url } }
            ?: trailers.first().second
        val preferredTitle = trailers.first { it.second == preferred }.first
        if (autoplay || activeUrl == preferred) {
            playInline(preferredTitle, preferred, forceReload = autoplay && !hasAutoPlayed)
        } else {
            highlightRow(preferred)
        }
    }

    private fun playInline(title: String, url: String, forceReload: Boolean) {
        val ytId = TrailerPlaybackController.youtubeVideoId(url)
        if (ytId.isNullOrBlank()) {
            TrailerPlaybackController.openExternalYoutube(context, url)
            return
        }
        val alreadyPlaying = !forceReload && loadedUrl == url
        activeUrl = url
        activeTitle = title
        stage.visibility = View.VISIBLE
        chromeBar.visibility = View.VISIBLE
        binding.tvDetailTrailerNowPlaying.visibility = View.VISIBLE
        binding.tvDetailTrailerNowPlaying.text = title
        updateMuteIcon()
        binding.btnDetailTrailerOpenExternal.setOnClickListener {
            ExpMotion.hapticTap(it)
            TrailerPlaybackController.openExternalYoutube(context, url)
        }
        highlightRow(url)
        if (alreadyPlaying) return
        errorPanel.visibility = View.GONE
        web.visibility = View.VISIBLE
        setLoading(true)
        hasAutoPlayed = true
        loadedUrl = url
        TrailerPlaybackController.loadTrailerEmbed(
            web = web,
            videoId = ytId,
            autoplay = true,
            muted = muted,
            useIframeApi = true,
        )
    }

    private fun highlightRow(url: String) {
        val selectedBg = ContextCompat.getDrawable(context, R.drawable.bg_detail_trailer_row_selected)
        val typed = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        val ripple = ContextCompat.getDrawable(context, typed.resourceId)
        for (i in 0 until binding.llDetailTrailerList.childCount) {
            val child = binding.llDetailTrailerList.getChildAt(i)
            val selected = child.getTag(R.id.detail_trailer_row_url_tag) == url
            child.background = if (selected) selectedBg?.constantState?.newDrawable()?.mutate() else ripple?.constantState?.newDrawable()?.mutate()
            child.isSelected = selected
            child.alpha = if (selected) 1f else 0.92f
        }
    }

    private fun toggleMute() {
        muted = !muted
        updateMuteIcon()
        val js = if (muted) {
            "try{if(window.AndroidTrailerPlayer){window.AndroidTrailerPlayer.mute();}}catch(e){}"
        } else {
            "try{if(window.AndroidTrailerPlayer){window.AndroidTrailerPlayer.unMute();}}catch(e){}"
        }
        web.evaluateJavascript(js, null)
    }

    private fun updateMuteIcon() {
        muteBtn.setImageResource(
            if (muted) R.drawable.ic_trailer_muted else R.drawable.ic_trailer_unmuted,
        )
        muteBtn.contentDescription = context.getString(
            if (muted) R.string.detail_trailer_unmute else R.string.detail_trailer_mute,
        )
    }

    private fun setLoading(show: Boolean) {
        loading.visibility = if (show) View.VISIBLE else View.GONE
        shimmer.visibility = if (show) View.VISIBLE else View.GONE
        shimmer.isEnabled = show
    }

    private fun showError() {
        setLoading(false)
        web.visibility = View.INVISIBLE
        errorPanel.visibility = View.VISIBLE
        chromeBar.visibility = View.VISIBLE
    }

    private fun onPlayerReady() {
        binding.root.post {
            setLoading(false)
            web.visibility = View.VISIBLE
            errorPanel.visibility = View.GONE
        }
    }

    private fun onPlayerError() {
        binding.root.post { showError() }
    }

    private fun requestFullscreen() {
        web.evaluateJavascript(
            "try{if(window.AndroidTrailerPlayer&&window.AndroidTrailerPlayer.getIframe){" +
                "var f=window.AndroidTrailerPlayer.getIframe();" +
                "if(f&&f.requestFullscreen){f.requestFullscreen();}" +
                "else if(document.documentElement.requestFullscreen){document.documentElement.requestFullscreen();}" +
                "}}catch(e){}",
            null,
        )
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback?) {
        val activity = context as? Activity ?: return
        customView = view
        customViewCallback = callback
        previousOrientation = activity.requestedOrientation
        previousSystemUi = activity.window.decorView.systemUiVisibility
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val decor = activity.window.decorView as ViewGroup
        val holder = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            tag = FULLSCREEN_TAG
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        decor.addView(
            holder,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun exitFullscreen() {
        val activity = context as? Activity ?: return
        val decor = activity.window.decorView as ViewGroup
        val holder = decor.findViewWithTag<View>(FULLSCREEN_TAG)
        if (holder != null) {
            decor.removeView(holder)
        }
        customView = null
        runCatching { customViewCallback?.onCustomViewHidden() }
        customViewCallback = null
        previousOrientation?.let { activity.requestedOrientation = it }
        previousOrientation = null
        previousSystemUi?.let {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = it
        }
        previousSystemUi = null
    }

    private inner class TrailerJsBridge {
        @JavascriptInterface
        fun onReady() = onPlayerReady()

        @JavascriptInterface
        fun onPlaying() = onPlayerReady()

        @JavascriptInterface
        fun onPaused() = Unit

        @JavascriptInterface
        fun onEnded() = Unit

        @JavascriptInterface
        fun onError(@Suppress("UNUSED_PARAMETER") code: Int) = onPlayerError()
    }

    companion object {
        private const val JS_BRIDGE = "AndroidTrailer"
        private const val FULLSCREEN_TAG = "detail_trailer_fullscreen"
    }
}
