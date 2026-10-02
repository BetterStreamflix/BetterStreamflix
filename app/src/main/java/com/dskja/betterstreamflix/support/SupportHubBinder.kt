package com.dskja.betterstreamflix.support

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.color.MaterialColors

/**
 * Binds Impact goals, FAQ rows, and supporters into Support hub containers.
 */
object SupportHubBinder {

    fun bindSupporters(
        context: Context,
        container: LinearLayout?,
        titleView: TextView? = null,
        subtitleView: TextView? = null,
    ) {
        container ?: return
        container.removeAllViews()
        val supporters = SupportersCatalog.load(context)
        if (supporters.isEmpty()) {
            titleView?.visibility = View.GONE
            subtitleView?.visibility = View.GONE
            container.visibility = View.GONE
            return
        }
        titleView?.visibility = View.VISIBLE
        subtitleView?.visibility = View.VISIBLE
        container.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(context)
        val exp = ExperimentalMobileDesign.enabled()
        val glassBg = ExperimentalMobileDesign.glassCardBackground()
        supporters.forEachIndexed { index, supporter ->
            val row = inflater.inflate(R.layout.item_support_supporter, container, false)
            if (exp) {
                row.setBackgroundResource(glassBg)
            }
            row.findViewById<TextView>(R.id.tv_support_supporter_name).text = supporter.name
            val noteView = row.findViewById<TextView>(R.id.tv_support_supporter_note)
            if (supporter.note.isNotBlank()) {
                noteView.text = supporter.note
                noteView.visibility = View.VISIBLE
            } else {
                noteView.visibility = View.GONE
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index > 0) {
                    topMargin = context.resources.getDimensionPixelSize(R.dimen.support_card_spacing)
                }
            }
            container.addView(row, lp)
            SupportUiBinder.wireInteractive(row)
            if (exp) {
                row.visibility = View.INVISIBLE
                row.postDelayed({
                    row.visibility = View.VISIBLE
                    ExpMotion.popIn(row)
                }, 40L * index)
            }
        }
    }

    fun bindImpact(context: Context, container: LinearLayout?) {
        container ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val exp = ExperimentalMobileDesign.enabled()
        val glassBg = ExperimentalMobileDesign.glassCardBackground()
        SupportContent.impactGoals.forEachIndexed { index, goal ->
            val row = inflater.inflate(R.layout.item_support_impact, container, false)
            if (exp) {
                row.setBackgroundResource(glassBg)
                row.findViewById<ProgressBar>(R.id.pb_support_impact)?.let { bar ->
                    val primary = MaterialColors.getColor(
                        bar,
                        androidx.appcompat.R.attr.colorPrimary,
                    )
                    bar.progressTintList = android.content.res.ColorStateList.valueOf(primary)
                }
            }
            row.findViewById<TextView>(R.id.tv_support_impact_title).setText(goal.titleRes)
            row.findViewById<TextView>(R.id.tv_support_impact_body).setText(goal.bodyRes)
            row.findViewById<ProgressBar>(R.id.pb_support_impact).apply {
                val target = goal.progressPercent.coerceIn(0, 100)
                if (exp) {
                    progress = 0
                    android.animation.ObjectAnimator.ofInt(this, "progress", 0, target)
                        .setDuration(520L)
                        .start()
                } else {
                    progress = target
                }
            }
            row.findViewById<TextView>(R.id.tv_support_impact_percent).text =
                context.getString(R.string.support_impact_percent, goal.progressPercent)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index > 0) {
                    topMargin = context.resources.getDimensionPixelSize(R.dimen.support_card_spacing)
                }
            }
            container.addView(row, lp)
            SupportUiBinder.wireInteractive(row)
            if (exp) {
                row.visibility = View.INVISIBLE
                row.postDelayed({
                    row.visibility = View.VISIBLE
                    ExpMotion.popIn(row)
                }, 40L * index)
            }
        }
    }

    fun bindFaq(context: Context, container: LinearLayout?) {
        container ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val exp = ExperimentalMobileDesign.enabled()
        val glassBg = ExperimentalMobileDesign.glassCardBackground()
        SupportContent.faq.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.item_support_faq, container, false)
            if (exp) {
                row.setBackgroundResource(glassBg)
            }
            val question = row.findViewById<TextView>(R.id.tv_support_faq_question)
            val answer = row.findViewById<TextView>(R.id.tv_support_faq_answer)
            question.setText(item.questionRes)
            answer.setText(item.answerRes)
            answer.visibility = View.GONE
            row.setOnClickListener {
                ExpMotion.hapticTap(it)
                val open = answer.visibility != View.VISIBLE
                if (open) {
                    if (ExperimentalMobileDesign.enabled()) {
                        row.setBackgroundResource(ExperimentalMobileDesign.optionItemBackground())
                        question.setTextColor(
                            com.google.android.material.color.MaterialColors.getColor(
                                question, androidx.appcompat.R.attr.colorPrimary,
                            ),
                        )
                        ExpMotion.fadeInAndShow(answer)
                    } else {
                        answer.visibility = View.VISIBLE
                    }
                } else {
                    if (ExperimentalMobileDesign.enabled()) {
                        row.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
                        question.setTextColor(
                            com.google.android.material.color.MaterialColors.getColor(
                                question, com.google.android.material.R.attr.colorOnSurface,
                            ),
                        )
                        ExpMotion.fadeOutAndHide(answer)
                    } else {
                        answer.visibility = View.GONE
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index > 0) {
                    topMargin = context.resources.getDimensionPixelSize(R.dimen.support_card_spacing)
                }
            }
            container.addView(row, lp)
            SupportUiBinder.wireInteractive(row)
            if (exp) {
                row.visibility = View.INVISIBLE
                row.postDelayed({
                    row.visibility = View.VISIBLE
                    ExpMotion.popIn(row)
                }, 40L * index)
            }
        }
    }
}
