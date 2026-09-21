package com.dskja.betterstreamflix.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dskja.betterstreamflix.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object LoggingUtils {
    
    /**
     * Displays a logging dialog with error details
     * @param context The context to show the dialog
     * @param error The throwable error to log
     */
    fun showErrorDialog(context: Context, error: Throwable) {
        val tag = "ErrorLog"
        val logBuilder = StringBuilder()
        
        val errorMessage = context.getString(R.string.error_dialog_message)
        val errorCause = context.getString(R.string.error_dialog_cause)
        val stackTrace = context.getString(R.string.error_dialog_stack_trace)
        val unknownError = context.getString(R.string.error_dialog_unknown)
        val noCause = context.getString(R.string.error_dialog_no_cause)
        
        fun log(message: String) {
            Log.e(tag, message)
            logBuilder.append(message).append("\n\n")
        }

        log("📋 $errorMessage:\n${error.message ?: unknownError}")
        log("🔗 $errorCause:\n${error.cause?.message ?: noCause}")
        log("📜 $stackTrace:\n${Log.getStackTraceString(error)}")

        val logContent = logBuilder.toString()
        val exp = ExperimentalMobileDesign.enabled()

        val builder = if (exp) {
            MaterialAlertDialogBuilder(context)
        } else {
            AlertDialog.Builder(context)
        }
        builder.setTitle(
            if (exp) context.getString(R.string.error_dialog_title)
            else "📝 ${context.getString(R.string.error_dialog_title)}",
        )
        if (exp) {
            val density = context.resources.displayMetrics.density
            val column = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            }
            val rule = View(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (36 * density).toInt(),
                    (3 * density).toInt(),
                ).also { it.bottomMargin = (10 * density).toInt() }
                setBackgroundResource(R.drawable.bg_exp_accent_rule)
            }
            val body = TextView(context).apply {
                text = logContent
                setTextIsSelectable(true)
                setTextAppearance(R.style.TextAppearance_Lumina_Caption)
                setPadding(48, 24, 48, 24)
                setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            }
            val scroll = ScrollView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                addView(body)
                isFillViewport = true
            }
            column.addView(rule)
            column.addView(scroll)
            builder.setView(column)
            val dialog = builder
                .setPositiveButton("OK", null)
                .setNeutralButton(context.getString(R.string.error_dialog_copy)) { _, _ ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Error Log", logContent)
                    clipboard.setPrimaryClip(clip)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        ExpDialogChrome.notify(
                            context,
                            R.string.error_dialog_copied,
                            R.string.error_dialog_title,
                        )
                    }
                }
                .create()
            dialog.setOnShowListener {
                ExpDialogChrome.polishShown(dialog)
                dialog.window?.setBackgroundDrawableResource(
                    ExperimentalMobileDesign.dialogBackground(),
                )
                dialog.window?.decorView?.let { decor ->
                    ExperimentalMobileDesign.applyReducedGlass(decor)
                    ExpMotion.enterScreen(decor)
                }
                ExpMotion.pulseAccentRule(rule)
                ExpMotion.revealHeader(rule, body)
                ExpMotion.popIn(scroll)
            }
            dialog.show()
            return
        }
        builder.setMessage(logContent)
        val dialog = builder
            .setPositiveButton("OK", null)
            .setNeutralButton("📋 ${context.getString(R.string.error_dialog_copy)}") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Error Log", logContent)
                clipboard.setPrimaryClip(clip)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, context.getString(R.string.error_dialog_copied), Toast.LENGTH_SHORT).show()
                }
            }
            .create()
        dialog.show()
    }
}
