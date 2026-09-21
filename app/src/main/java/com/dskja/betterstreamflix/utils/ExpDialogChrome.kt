package com.dskja.betterstreamflix.utils

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.dskja.betterstreamflix.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shared Lumina polish for Material / AppCompat alert buttons and dialog shells.
 * Always themed via [themedContext] so Player / non-Material surfaces cannot crash.
 */
object ExpDialogChrome {

    fun themedContext(context: Context): Context {
        val baseTheme = when {
            ExperimentalMobileDesign.enabled() -> ExperimentalMobileDesign.themeRes()
            else -> R.style.AppTheme_Mobile
        }
        return ContextThemeWrapper(context, baseTheme)
    }

    /**
     * Attaches an OnShowListener that ExpPresses dialog buttons.
     * Call only when the dialog does not already own an OnShowListener;
     * otherwise call [polishShown] from inside that listener.
     */
    fun polishButtons(dialog: Dialog) {
        if (!ExperimentalMobileDesign.enabled()) return
        dialog.setOnShowListener { polishShown(dialog) }
    }

    /** Apply ExpPress / popIn to dialog buttons while the dialog is showing. */
    fun polishShown(dialog: Dialog) {
        if (!ExperimentalMobileDesign.enabled()) return
        dialog.window?.setBackgroundDrawableResource(
            ExperimentalMobileDesign.dialogBackground(),
        )
        dialog.window?.decorView?.let { decor ->
            ExperimentalMobileDesign.applyReducedGlass(decor)
            if (decor.getTag(R.id.exp_enter_animated_tag) != true) {
                decor.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.enterScreen(decor)
            }
        }
        listOf(
            AlertDialog.BUTTON_POSITIVE,
            AlertDialog.BUTTON_NEGATIVE,
            AlertDialog.BUTTON_NEUTRAL,
        ).forEachIndexed { index, which ->
            val btn = (dialog as? AlertDialog)?.getButton(which) ?: return@forEachIndexed
            btn.setBackgroundResource(
                when (which) {
                    AlertDialog.BUTTON_POSITIVE -> ExperimentalMobileDesign.primaryButtonBackground()
                    else -> ExperimentalMobileDesign.chipBackground()
                },
            )
            if (which == AlertDialog.BUTTON_POSITIVE) {
                btn.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        btn,
                        com.google.android.material.R.attr.colorOnPrimary,
                    ),
                )
            } else {
                btn.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        btn,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
            }
            with(ExpPressEffects) { btn.applyExpPress() }
            btn.postDelayed({ ExpMotion.popIn(btn) }, 32L * index)
        }
    }

    /**
     * Info dialog with glass body + accent rule when Experimental UI is on;
     * plain message otherwise.
     */
    data class GlassMessage(
        val root: LinearLayout,
        val rule: View,
        val body: TextView,
    )

    /** Build a glass body + accent rule column for custom AlertDialogs. */
    fun buildGlassMessage(context: Context, message: CharSequence): GlassMessage {
        val themed = themedContext(context)
        val density = themed.resources.displayMetrics.density
        val column = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(),
                (12 * density).toInt(),
                (20 * density).toInt(),
                (8 * density).toInt(),
            )
        }
        val rule = View(themed).apply {
            layoutParams = LinearLayout.LayoutParams(
                (36 * density).toInt(),
                (3 * density).toInt(),
            ).also { it.bottomMargin = (12 * density).toInt() }
            setBackgroundResource(R.drawable.bg_exp_accent_rule)
        }
        val body = TextView(themed).apply {
            text = message
            setTextIsSelectable(true)
            setTextAppearance(R.style.TextAppearance_Lumina_Body)
            setPadding(
                (16 * density).toInt(),
                (14 * density).toInt(),
                (16 * density).toInt(),
                (14 * density).toInt(),
            )
            setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        column.addView(rule)
        column.addView(body)
        return GlassMessage(column, rule, body)
    }

    fun polishGlassMessageShown(dialog: Dialog, glass: GlassMessage) {
        polishShown(dialog)
        ExperimentalMobileDesign.applyReducedGlass(glass.root)
        ExpMotion.pulseAccentRule(glass.rule)
        ExpMotion.revealHeader(glass.rule, glass.body)
    }

    fun showInfo(
        context: Context,
        titleRes: Int,
        message: CharSequence,
        alertBuilder: (Context) -> AlertDialog.Builder,
    ) {
        showInfo(context, context.getString(titleRes), message, alertBuilder)
    }

    fun showInfo(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        alertBuilder: (Context) -> AlertDialog.Builder,
    ) {
        runCatching {
            val themed = themedContext(context)
            if (!ExperimentalMobileDesign.enabled()) {
                alertBuilder(themed)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
                    .also { dialog ->
                        polishButtons(dialog)
                        dialog.show()
                    }
                return
            }
            val glass = buildGlassMessage(themed, message)
            alertBuilder(themed)
                .setTitle(title)
                .setView(glass.root)
                .setPositiveButton(android.R.string.ok, null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener { polishGlassMessageShown(dialog, glass) }
                    dialog.show()
                }
        }.onFailure {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Lumina glass info when Experimental UI is on; short Toast otherwise.
     * Default builder is MaterialAlertDialogBuilder on a themed context.
     */
    fun notify(
        context: Context,
        message: CharSequence,
        titleRes: Int = R.string.loading_error_title,
        alertBuilder: ((Context) -> AlertDialog.Builder)? = null,
    ) {
        if (!ExperimentalMobileDesign.enabled()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            return
        }
        val builder = alertBuilder ?: { ctx ->
            MaterialAlertDialogBuilder(themedContext(ctx))
        }
        showInfo(context, titleRes, message, builder)
    }

    fun notify(
        context: Context,
        messageRes: Int,
        titleRes: Int = R.string.loading_error_title,
        alertBuilder: ((Context) -> AlertDialog.Builder)? = null,
    ) {
        notify(context, context.getString(messageRes), titleRes, alertBuilder)
    }
}
