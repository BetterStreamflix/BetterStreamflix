package com.dskja.betterstreamflix.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.CrashReporter
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign

/**
 * Full-featured local crash log viewer with copy / share / clear.
 */
object CrashLogDialog {
    fun show(context: Context) {
        val text = CrashReporter.latestCrashText(context)
        if (text.isNullOrBlank()) {
            if (ExperimentalMobileDesign.enabled()) {
                com.dskja.betterstreamflix.utils.ExpDialogChrome.showInfo(
                    context,
                    R.string.settings_view_crash_log_title,
                    context.getString(R.string.settings_view_crash_log_empty),
                ) { ctx ->
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                }
            } else {
                Toast.makeText(context, R.string.settings_view_crash_log_empty, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val view = LayoutInflater.from(context).inflate(
            ExperimentalMobileDesign.layout(
                R.layout.dialog_crash_log,
                R.layout.dialog_crash_log_exp,
            ),
            null,
            false,
        )
        val body = view.findViewById<TextView>(R.id.tv_crash_log_body)
        body.text = text
        body.setTextIsSelectable(true)

        val builder = if (ExperimentalMobileDesign.enabled()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context).setView(view)
        } else {
            AlertDialog.Builder(context).setView(view)
        }
        if (!ExperimentalMobileDesign.enabled()) {
            builder.setTitle(R.string.settings_view_crash_log_title)
                .setPositiveButton(android.R.string.ok, null)
        }
        val dialog = builder.create()

        fun wire(button: View?, action: () -> Unit) {
            button ?: return
            if (ExperimentalMobileDesign.enabled()) {
                button.applyExpPress()
            }
            button.setOnClickListener {
                ExpMotion.hapticTap(it)
                action()
            }
        }

        wire(view.findViewById(R.id.btn_crash_log_copy)) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("crash-log", text))
            com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(
                context,
                R.string.crash_log_copied,
                R.string.settings_view_crash_log_title,
            )
        }
        wire(view.findViewById(R.id.btn_crash_log_share)) {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "BetterStreamflix crash log")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(share, context.getString(R.string.crash_log_share)),
                )
            }
        }
        wire(view.findViewById(R.id.btn_crash_log_clear)) {
            if (CrashReporter.clearAll(context)) {
                com.dskja.betterstreamflix.utils.ExpDialogChrome.notify(
                    context,
                    R.string.crash_log_cleared,
                    R.string.settings_view_crash_log_title,
                )
                dialog.dismiss()
            }
        }

        if (ExperimentalMobileDesign.enabled()) {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            view.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
            ExperimentalMobileDesign.applyReducedGlass(view)
            view.findViewById<View>(R.id.sv_crash_log_body)
                ?.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            ExpMotion.enterScreen(view)
            ExpMotion.revealHeader(
                view.findViewById(R.id.tv_crash_log_title),
                view.findViewById(R.id.v_crash_log_rule),
            )
            ExpMotion.pulseAccentRule(view.findViewById(R.id.v_crash_log_rule))
            view.findViewById<View>(R.id.sv_crash_log_body)?.let { ExpMotion.popIn(it) }
            listOf(
                R.id.btn_crash_log_copy,
                R.id.btn_crash_log_share,
                R.id.btn_crash_log_clear,
            ).forEachIndexed { index, id ->
                view.findViewById<View>(id)?.let { btn ->
                    btn.setBackgroundResource(
                        when (id) {
                            R.id.btn_crash_log_copy -> ExperimentalMobileDesign.primaryButtonBackground()
                            else -> ExperimentalMobileDesign.chipBackground()
                        },
                    )
                    btn.applyExpPress()
                    btn.postDelayed({ ExpMotion.popIn(btn) }, 36L * index)
                }
            }
        }

        dialog.show()
    }
}
