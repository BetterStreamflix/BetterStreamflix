package com.betterstreamflix.app.logo

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.betterstreamflix.app.R
import com.betterstreamflix.app.utils.ExpDialogChrome

/**
 * Prompt to set / clear a user-supplied title-logo URL (LogoSource.USER).
 * The URL is stored in [CustomLogoStore]; TitleLogoSurface prefers it over
 * provider/TMDb/Fanart resolution until removed.
 */
object CustomLogoDialog {

    fun show(
        context: Context,
        title: String,
        isTv: Boolean,
        id: String,
        onChanged: (() -> Unit)? = null,
    ) {
        CustomLogoStore.init(context)
        val themed = ExpDialogChrome.themedContext(context)
        val input = android.widget.EditText(themed).apply {
            setText(CustomLogoStore.get(isTv, id))
            hint = context.getString(R.string.custom_logo_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            setSelection(text?.length ?: 0)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val hasExisting = !CustomLogoStore.get(isTv, id).isNullOrBlank()
        val builder = AlertDialog.Builder(themed)
            .setTitle(context.getString(R.string.custom_logo_dialog_title, title))
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (hasExisting) {
            builder.setNeutralButton(R.string.custom_logo_remove, null)
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            ExpDialogChrome.polishShown(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.isNotEmpty() &&
                    !(url.startsWith("https://") || url.startsWith("http://"))
                ) {
                    input.error = context.getString(R.string.custom_logo_invalid)
                    return@setOnClickListener
                }
                if (url.isEmpty()) {
                    CustomLogoStore.clear(isTv, id)
                    Toast.makeText(context, R.string.custom_logo_removed, Toast.LENGTH_SHORT).show()
                } else {
                    CustomLogoStore.set(isTv, id, url)
                    Toast.makeText(context, R.string.custom_logo_saved, Toast.LENGTH_SHORT).show()
                }
                onChanged?.invoke()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                CustomLogoStore.clear(isTv, id)
                Toast.makeText(context, R.string.custom_logo_removed, Toast.LENGTH_SHORT).show()
                onChanged?.invoke()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
