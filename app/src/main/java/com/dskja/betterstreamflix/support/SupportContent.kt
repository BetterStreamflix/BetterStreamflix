package com.dskja.betterstreamflix.support

import androidx.annotation.StringRes
import com.dskja.betterstreamflix.R

/**
 * Editorial content for the Support hub: funding impact goals + FAQ.
 * Pure data — no network; honest about optional/outbound donations.
 */
object SupportContent {

    data class ImpactGoal(
        @StringRes val titleRes: Int,
        @StringRes val bodyRes: Int,
        val progressPercent: Int,
    )

    data class FaqItem(
        @StringRes val questionRes: Int,
        @StringRes val answerRes: Int,
    )

    val impactGoals: List<ImpactGoal> = listOf(
        ImpactGoal(
            titleRes = R.string.support_impact_trakt_title,
            bodyRes = R.string.support_impact_trakt_body,
            // Editorial: first community coffee toward VIP coverage.
            progressPercent = 5,
        ),
        ImpactGoal(
            titleRes = R.string.support_impact_infra_title,
            bodyRes = R.string.support_impact_infra_body,
            progressPercent = 0,
        ),
        ImpactGoal(
            titleRes = R.string.support_impact_tools_title,
            bodyRes = R.string.support_impact_tools_body,
            progressPercent = 0,
        ),
    )

    val faq: List<FaqItem> = listOf(
        FaqItem(R.string.support_faq_optional_q, R.string.support_faq_optional_a),
        FaqItem(R.string.support_faq_payment_q, R.string.support_faq_payment_a),
        FaqItem(R.string.support_faq_unlock_q, R.string.support_faq_unlock_a),
        FaqItem(R.string.support_faq_where_q, R.string.support_faq_where_a),
        FaqItem(R.string.support_faq_community_q, R.string.support_faq_community_a),
    )
}
