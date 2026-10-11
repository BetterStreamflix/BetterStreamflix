package com.dskja.betterstreamflix.utils

import android.view.View
import com.dskja.betterstreamflix.R
import com.google.android.material.color.MaterialColors

/**
 * Shared empty-state chrome for Experimental / Lumina Ink Lock screens.
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

        val exp = ExperimentalMobileDesign.enabled()
        if (exp) {
            emptyView.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            if (tintOnSurfaceVariant && emptyView is android.widget.TextView) {
                emptyView.setTextColor(
                    MaterialColors.getColor(
                        emptyView,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
            }
        }
        emptyView.visibility = View.VISIBLE
        emptyRule?.visibility = if (exp) View.VISIBLE else View.GONE
        // Always offer a CTA when provided — empty Home must not trap Mobile or Leanback.
        val leanback = DeviceCapabilities.isLeanbackDevice(emptyView.context)
        emptyCta?.visibility = View.VISIBLE

        if (emptyCta != null) {
            if (exp) {
                emptyCta.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                with(ExpPressEffects) { emptyCta.applyExpPress() }
            }
            emptyCta.isFocusable = true
            emptyCta.isFocusableInTouchMode = leanback
            if (onCtaClick != null) {
                emptyCta.setOnClickListener { view ->
                    ExpMotion.hapticTap(view)
                    onCtaClick(view)
                }
            }
        }

        if (exp && emptyView.getTag(R.id.exp_enter_animated_tag) != true) {
            emptyView.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(emptyView, emptyRule)
            ExpMotion.pulseAccentRule(emptyRule)
            emptyCta?.let { ExpMotion.popIn(it) }
        }
    }
}
