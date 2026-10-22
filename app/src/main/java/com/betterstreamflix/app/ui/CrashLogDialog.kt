package com.betterstreamflix.app.ui

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
import com.betterstreamflix.app.R
import com.betterstreamflix.app.utils.CrashReporter
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.ExpPressEffects.applyExpPress

/**
 * Full-featured local crash log viewer with copy / share / clear.
 */
object CrashLogDialog {
    fun show(context: Context) {
        val text = CrashReporter.latestCrashText(context)
        if (text.isNullOrBlank()) {
            com.betterstreamflix.app.utils.ExpDialogChrome.showInfo(
                context,
                R.string.settings_view_crash_log_title,
                context.getString(R.string.settings_view_crash_log_empty),
            ) { ctx ->
                androidx.appcompat.app.AlertDialog.Builder(ctx)
            }
            return
        }

        val view = LayoutInflater.from(context).inflate(
            R.layout.dialog_crash_log,
            null,
            false,
        )
        val body = view.findViewById<TextView>(R.id.tv_crash_log_body)
        body.text = text
        body.setTextIsSelectable(true)

        val builder = androidx.appcompat.app.AlertDialog.Builder(context).setView(view)
        builder.setTitle(R.string.settings_view_crash_log_title)
            .setPositiveButton(android.R.string.ok, null)
        val dialog = builder.create()

        fun wire(button: View?, action: () -> Unit) {
            button ?: return
            button.applyExpPress()
            button.setOnClickListener {
                ExpMotion.hapticTap(it)
                action()
            }
        }

        wire(view.findViewById(R.id.btn_crash_log_copy)) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("crash-log", text))
            com.betterstreamflix.app.utils.ExpDialogChrome.notify(
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
                com.betterstreamflix.app.utils.ExpDialogChrome.notify(
                    context,
                    R.string.crash_log_cleared,
                    R.string.settings_view_crash_log_title,
                )
                dialog.dismiss()
            }
        }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        view.setBackgroundResource(R.drawable.bg_profile_dialog)
        view.findViewById<View>(R.id.sv_crash_log_body)
            ?.setBackgroundResource(R.drawable.bg_cinema_glass_card)
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
                        R.id.btn_crash_log_copy -> R.drawable.bg_button_mobile
                        else -> R.drawable.bg_outlined_chip
                    },
                )
                btn.applyExpPress()
                btn.postDelayed({ ExpMotion.popIn(btn) }, 36L * index)
            }
        }

        dialog.show()
    }
}
