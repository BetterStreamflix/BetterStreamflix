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
        emptyCta?.visibility = if (exp) View.VISIBLE else View.GONE

        if (exp && emptyCta != null) {
            emptyCta.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
            with(ExpPressEffects) { emptyCta.applyExpPress() }
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
