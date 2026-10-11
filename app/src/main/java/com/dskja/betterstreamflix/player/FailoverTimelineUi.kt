package com.dskja.betterstreamflix.player

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.dskja.betterstreamflix.R

/**
 * Formats and binds the failover mini-timeline for Mobile + TV.
 */
object FailoverTimelineUi {
    const val SUCCESS_CUE_MS = 3_800L

    fun formatLine(context: Context, attempts: List<FailoverAttempt>): String? {
        if (attempts.isEmpty()) return null
        val steps = FailoverTimelineBuilder.buildSteps(attempts)
        if (steps.isEmpty()) return null
        val arrow = context.getString(R.string.player_failover_timeline_arrow)
        return steps.joinToString(separator = " $arrow ") { stepLabel(context, it) }
    }

    fun formatContentDescription(context: Context, attempts: List<FailoverAttempt>): String {
        val line = formatLine(context, attempts) ?: return ""
        return context.getString(R.string.player_failover_timeline_a11y, line)
    }

    fun bindErrorTimeline(root: View?, attempts: List<FailoverAttempt>) {
        val tv = root?.findViewById<TextView>(R.id.tv_failover_timeline) ?: return
        if (!FailoverTimelineBuilder.shouldShowOnFailure(attempts) &&
            !FailoverTimelineBuilder.shouldShowAfterSuccess(attempts)
        ) {
            tv.isGone = true
            tv.text = null
            return
        }
        // On give-up, show even without OK when there were multiple failures.
        if (!FailoverTimelineBuilder.shouldShowOnFailure(attempts)) {
            tv.isGone = true
            return
        }
        val line = formatLine(context = root.context, attempts = attempts)
        if (line.isNullOrBlank()) {
            tv.isGone = true
            return
        }
        tv.text = line
        tv.contentDescription = formatContentDescription(root.context, attempts)
        tv.isVisible = true
    }

    fun clearErrorTimeline(root: View?) {
        val tv = root?.findViewById<TextView>(R.id.tv_failover_timeline) ?: return
        tv.isGone = true
        tv.text = null
    }

    private fun stepLabel(context: Context, step: FailoverTimelineStep): String {
        if (step.ellipsis) {
            return context.getString(R.string.player_failover_timeline_ellipsis)
        }
        val reason = context.getString(reasonRes(step.kind))
        return when (step.kind) {
            FailoverStepKind.SOFTWARE_DECODER,
            FailoverStepKind.EXTERNAL_PLAYER,
            -> reason
            else -> context.getString(
                R.string.player_failover_timeline_step,
                step.serverOrdinal.coerceAtLeast(1),
                reason,
            )
        }
    }

    private fun reasonRes(kind: FailoverStepKind): Int = when (kind) {
        FailoverStepKind.TIMEOUT -> R.string.player_failover_reason_timeout
        FailoverStepKind.CAPTCHA -> R.string.player_failover_reason_captcha
        FailoverStepKind.GEO -> R.string.player_failover_reason_geo
        FailoverStepKind.DOMAIN -> R.string.player_failover_reason_domain
        FailoverStepKind.GENERIC -> R.string.player_failover_reason_failed
        FailoverStepKind.SOFTWARE_DECODER -> R.string.player_failover_reason_software
        FailoverStepKind.EXTERNAL_PLAYER -> R.string.player_failover_reason_external
        FailoverStepKind.OK -> R.string.player_failover_reason_ok
    }
}
