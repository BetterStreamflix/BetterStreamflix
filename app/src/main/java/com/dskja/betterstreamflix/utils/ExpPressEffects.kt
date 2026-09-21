package com.dskja.betterstreamflix.utils

import android.annotation.SuppressLint
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import com.dskja.betterstreamflix.R

/**
 * Experimental shell press feedback: subtle scale-down on touch, springy
 * release. Respects the system animator-duration scale (reduced motion).
 */
object ExpPressEffects {

    @SuppressLint("ClickableViewAccessibility")
    fun View.applyExpPress() {
        if (Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        ) return
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate()
                    .scaleX(0.96f).scaleY(0.96f)
                    .setDuration(110)
                    .start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .start()
            }
            false
        }
    }

    /** Wire ExpPress + haptic on loading-error CTAs when Experimental design is on. */
    @SuppressLint("ClickableViewAccessibility")
    fun wireLoadingRetry(root: View?) {
        if (root == null || !ExperimentalMobileDesign.enabled()) return
        val reducedMotion = Settings.Global.getFloat(
            root.context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
        listOf(
            R.id.btn_is_loading_retry,
            R.id.btn_is_loading_clear_cache,
            R.id.btn_is_loading_error_details,
        ).forEach { id ->
            root.findViewById<View>(id)?.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        ExpMotion.hapticTap(v)
                        if (!reducedMotion) {
                            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(110).start()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!reducedMotion) {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                        }
                    }
                }
                false
            }
        }
    }

    /** Call when the loading-error group becomes visible. */
    fun animateLoadingError(root: View?) {
        if (root == null || !ExperimentalMobileDesign.enabled()) return
        root.findViewById<View>(R.id.tv_is_loading_error)?.setBackgroundResource(
            ExperimentalMobileDesign.glassCardBackground(),
        )
        val already = root.getTag(R.id.exp_enter_animated_tag) == true
        if (!already) {
            root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(root.findViewById(R.id.tv_is_loading_error))
        }
        listOf(
            R.id.btn_is_loading_retry,
            R.id.btn_is_loading_clear_cache,
            R.id.btn_is_loading_error_details,
        ).forEachIndexed { index, id ->
            root.findViewById<View>(id)?.let { btn ->
                val wasVisible = btn.visibility == View.VISIBLE
                btn.visibility = View.VISIBLE
                btn.setBackgroundResource(
                    when (id) {
                        R.id.btn_is_loading_retry -> ExperimentalMobileDesign.primaryButtonBackground()
                        else -> ExperimentalMobileDesign.chipBackground()
                    },
                )
                if (!already || !wasVisible) {
                    btn.postDelayed({ ExpMotion.popIn(btn) }, 40L * index)
                }
            }
        }
    }

    /**
     * Experimental loading: shimmer skeleton instead of a lone spinner.
     * On error/retry, hide both so CTA chrome owns the surface.
     */
    fun showLoadingSkeleton(root: View?, skeleton: Boolean) {
        if (root == null) return
        val shimmer = root.findViewById<View>(R.id.sh_is_loading)
        val spinner = root.findViewById<View>(R.id.pb_is_loading)
        if (!ExperimentalMobileDesign.enabled()) {
            shimmer?.visibility = View.GONE
            spinner?.visibility = if (skeleton) View.VISIBLE else View.GONE
            return
        }
        if (skeleton) {
            shimmer?.visibility = View.VISIBLE
            spinner?.visibility = View.GONE
            root.setTag(R.id.exp_enter_animated_tag, null)
            (shimmer as? com.facebook.shimmer.ShimmerFrameLayout)?.startShimmer()
            shimmer?.let { ExpMotion.fadeInAndShow(it) }
        } else {
            (shimmer as? com.facebook.shimmer.ShimmerFrameLayout)?.stopShimmer()
            shimmer?.visibility = View.GONE
            spinner?.visibility = View.GONE
        }
    }
}
