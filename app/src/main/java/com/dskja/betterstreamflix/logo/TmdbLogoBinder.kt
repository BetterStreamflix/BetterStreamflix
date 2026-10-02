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
                if (logo.isNullOrBlank()) return@launch
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
    ) {
        titleView?.text = title
        // Never paint a hard plate onto the ImageView — that boxes/crushes TMDb logos.
        // Contrast lives on the art vignette / optional parent slot plate (XML).
        TitleLogoPresentation.clearImagePlate(imageView)
        when (TitleLogoSlot.state(logoUrl, hideUntilReady)) {
            TitleLogoSlot.State.SHOW_TITLE -> {
                TmdbLogoGlide.clear(imageView)
                // INVISIBLE (not GONE) when parent is a fixed-height slot — no layout shift.
                imageView.visibility = View.INVISIBLE
                imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                titleView?.alpha = 1f
                titleView?.visibility = View.VISIBLE
                TitleLogoPresentation.applySlotPlate(imageView, showPlate = applyContrastScrim)
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
                imageView.visibility = View.INVISIBLE
                imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                titleView?.alpha = 1f
                titleView?.visibility = View.VISIBLE
                TitleLogoPresentation.applySlotPlate(imageView, showPlate = applyContrastScrim)
                onLoadFailed?.invoke()
            },
            onReady = {
                TitleLogoPresentation.clearImagePlate(imageView)
                imageView.visibility = View.VISIBLE
                imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                titleView?.alpha = 1f
                titleView?.visibility = View.GONE
            },
        )
    }
}
