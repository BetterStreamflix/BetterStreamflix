package com.dskja.betterstreamflix.logo

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.TmdbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Shared resolve + bind helper used by Featured, Detail, TV, and row surfaces.
 */
object TmdbLogoBinder {

    private val jobs = java.util.WeakHashMap<View, Job>()

    /**
     * Short-lived negative cache for resolve misses: a title TMDb doesn't know
     * would otherwise re-run the multi-step search on every RecyclerView rebind.
     * 10 min TTL keeps it fresh without permanently blackholing late additions.
     */
    private const val MISS_TTL_MS = 10 * 60 * 1000L
    private val resolveMisses = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Long>?,
        ): Boolean = size > 128
    }

    private fun missKey(request: LogoRequest): String = buildString {
        append(request.title.trim().lowercase())
        append('|').append(request.isTv)
        append('|').append(request.tmdbId.orEmpty())
        append('|').append(request.imdbId.orEmpty())
        append('|').append(request.language.orEmpty())
    }

    fun cancel(anchor: View) {
        jobs.remove(anchor)?.cancel()
    }

    fun resolve(
        anchor: View,
        request: LogoRequest,
        onResolved: (url: String, source: LogoSource) -> Unit,
    ) {
        if (request.title.isBlank() && request.tmdbId.isNullOrBlank() && request.imdbId.isNullOrBlank()) {
            return
        }
        val missKey = missKey(request)
        synchronized(resolveMisses) {
            val missAt = resolveMisses[missKey]
            if (missAt != null && System.currentTimeMillis() - missAt < MISS_TTL_MS) {
                return
            }
        }
        fun start() {
            val owner = anchor.findViewTreeLifecycleOwner()
            if (owner == null) {
                anchor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        anchor.removeOnAttachStateChangeListener(this)
                        start()
                    }
                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
                return
            }
            jobs.remove(anchor)?.cancel()
            val job = owner.lifecycleScope.launch {
                val logo = withContext(Dispatchers.IO) {
                    try {
                        TmdbLogoTelemetry.recordResolve()
                        TmdbUtils.resolveTitleLogo(
                            title = request.title,
                            year = request.year,
                            isTv = request.isTv,
                            tmdbId = request.tmdbId,
                            imdbId = request.imdbId,
                            language = request.language,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                if (logo.isNullOrBlank()) {
                    synchronized(resolveMisses) {
                        resolveMisses[missKey] = System.currentTimeMillis()
                    }
                    return@launch
                }
                synchronized(resolveMisses) { resolveMisses.remove(missKey) }
                if (TmdbLogoPicker.shouldUpgradeLogo(
                        request.existingLogo,
                        storedLang = null,
                        wantedLang = request.language,
                    ) &&
                    TmdbLogoPicker.isTrustedTmdbLogo(logo)
                ) {
                    TmdbLogoTelemetry.recordUpgrade()
                }
                onResolved(logo, LogoSource.TMDB)
            }
            jobs[anchor] = job
        }
        start()
    }

    fun bindImage(
        imageView: ImageView,
        titleView: TextView?,
        logoUrl: String?,
        title: String,
        hideUntilReady: Boolean = true,
        stillCurrent: () -> Boolean = { true },
        applyContrastScrim: Boolean = false,
        onLoadFailed: (() -> Unit)? = null,
        onLogoReady: ((String) -> Unit)? = null,
        onLogoFailed: (() -> Unit)? = null,
    ) {
        titleView?.text = title
        // Never paint a hard plate onto the ImageView — that boxes/crushes TMDb logos.
        // Contrast lives on the art vignette / optional parent slot plate (XML).
        TitleLogoPresentation.clearImagePlate(imageView)
        TitleLogoPresentation.clearSlotPlate(imageView)
        when (TitleLogoSlot.state(logoUrl, hideUntilReady)) {
            TitleLogoSlot.State.SHOW_TITLE -> {
                TmdbLogoGlide.clear(imageView)
                // INVISIBLE (not GONE) when parent is a fixed-height slot — no layout shift.
                imageView.visibility = View.INVISIBLE
                imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                titleView?.alpha = 1f
                titleView?.visibility = View.VISIBLE
                TitleLogoPresentation.applySlotPlate(imageView, showPlate = applyContrastScrim)
                onLogoFailed?.invoke()
                return
            }
            TitleLogoSlot.State.LOADING_LOGO -> {
                // Keep title as a soft placeholder — never leave an empty black plate.
                imageView.visibility = View.INVISIBLE
                imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                titleView?.alpha = 0.62f
                titleView?.visibility = View.VISIBLE
            }
            TitleLogoSlot.State.SHOW_LOGO -> {
                imageView.visibility = View.VISIBLE
                imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                titleView?.alpha = 1f
                titleView?.visibility = View.GONE
            }
        }
        TitleLogoPresentation.polishLogoImage(imageView)
        TmdbLogoGlide.load(
            imageView = imageView,
            logoUrl = logoUrl,
            stillCurrent = stillCurrent,
            hideUntilReady = hideUntilReady,
            contentDescription = title,
            onFailed = {
                TitleLogoPresentation.clearImagePlate(imageView)
                TitleLogoPresentation.clearSlotPlate(imageView)
                imageView.visibility = View.INVISIBLE
                imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                titleView?.alpha = 1f
                titleView?.visibility = View.VISIBLE
                TitleLogoPresentation.applySlotPlate(imageView, showPlate = applyContrastScrim)
                onLogoFailed?.invoke()
                onLoadFailed?.invoke()
            },
            onReady = { readyUrl ->
                TitleLogoPresentation.clearImagePlate(imageView)
                TitleLogoPresentation.clearSlotPlate(imageView)
                imageView.visibility = View.VISIBLE
                imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                titleView?.alpha = 1f
                titleView?.visibility = View.GONE
                onLogoReady?.invoke(readyUrl)
            },
        )
    }
}
