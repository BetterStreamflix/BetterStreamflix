package com.dskja.betterstreamflix.utils

import android.view.View
import com.dskja.betterstreamflix.R

/**
 * Shared empty-state chrome for Cinema-Ink screens.
 * Glass card, accent rule, primary CTA, reveal + pop-in — one call site pattern.
 */
object ExpEmptyChrome {

    fun bind(
        emptyView: View?,
        emptyRule: View? = null,
        emptyCta: View? = null,
        visible: Boolean,
        tintOnSurfaceVariant: Boolean = true,
        onCtaClick: ((View) -> Unit)? = null,
    ) {
        if (emptyView == null) return
        if (!visible) {
            emptyView.visibility = View.GONE
            emptyRule?.visibility = View.GONE
            emptyCta?.visibility = View.GONE
            emptyView.setTag(R.id.exp_enter_animated_tag, null)
            return
        }

        emptyView.visibility = View.VISIBLE
        // Accent rule is Cinema default chrome, not Experimental-only.
        emptyRule?.visibility = View.VISIBLE
        // Always offer a CTA when provided — empty Home must not trap Mobile or Leanback.
        val leanback = DeviceCapabilities.isLeanbackDevice(emptyView.context)
        emptyCta?.visibility = View.VISIBLE

        if (emptyCta != null) {
            emptyCta.isFocusable = true
            emptyCta.isFocusableInTouchMode = leanback
            if (onCtaClick != null) {
                emptyCta.setOnClickListener { view ->
                    ExpMotion.hapticTap(view)
                    onCtaClick(view)
                }
            }
        }
    }
}
