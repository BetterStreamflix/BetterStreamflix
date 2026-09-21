package com.dskja.betterstreamflix.fragments.settings

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.providers.GuardaFlixProvider
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object GuardaFlixAuthSettingsController {

    private fun alertBuilder(context: android.content.Context) =
        if (ExperimentalMobileDesign.enabled()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        } else {
            AlertDialog.Builder(context)
        }

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
    ) {
        val status = findPreference("provider_guardaflix_auth_status") ?: return
        val signIn = findPreference("provider_guardaflix_sign_in")
        val signUp = findPreference("provider_guardaflix_sign_up")
        val signOut = findPreference("provider_guardaflix_sign_out")

        fun refresh() {
            val username = GuardaFlixProvider.authUsername()
            val loggedIn = GuardaFlixProvider.isLoggedIn()
            status.summary = if (loggedIn && username.isNotBlank()) {
                fragment.getString(R.string.guardaflix_signed_in_as, username)
            } else if (loggedIn) {
                fragment.getString(R.string.guardaflix_signed_in)
            } else {
                fragment.getString(R.string.guardaflix_signed_out)
            }
            signIn?.isVisible = !loggedIn
            signUp?.isVisible = !loggedIn
            signOut?.isVisible = loggedIn
        }

        signIn?.setOnPreferenceClickListener {
            showCredentialsDialog(
                fragment,
                titleRes = R.string.guardaflix_sign_in,
                confirmPassword = false,
            ) { username, password ->
                runAuth(fragment, scope, ::refresh) {
                    GuardaFlixProvider.login(username, password)
                }
            }
            true
        }

        signUp?.setOnPreferenceClickListener {
            showCredentialsDialog(
                fragment,
                titleRes = R.string.guardaflix_sign_up,
                confirmPassword = true,
            ) { username, password ->
                runAuth(fragment, scope, ::refresh) {
                    GuardaFlixProvider.register(username, password)
                }
            }
            true
        }

        signOut?.setOnPreferenceClickListener {
            val ctx = fragment.requireContext()
            val message = ctx.getString(R.string.guardaflix_sign_out_summary)
            val glass = if (ExperimentalMobileDesign.enabled()) {
                ExpDialogChrome.buildGlassMessage(ctx, message)
            } else {
                null
            }
            val builder = alertBuilder(ctx)
                .setTitle(R.string.guardaflix_sign_out)
            if (glass != null) builder.setView(glass.root)
            else builder.setMessage(message)
            builder
                .setPositiveButton(R.string.guardaflix_sign_out) { _, _ ->
                    GuardaFlixProvider.logout()
                    refresh()
                    ExpDialogChrome.notify(
                        fragment.requireContext(),
                        R.string.guardaflix_sign_out_success,
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        if (glass != null) ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                        else ExpDialogChrome.polishButtons(dialog)
                    }
                    dialog.show()
                }
            true
        }

        refresh()
    }

    private fun showCredentialsDialog(
        fragment: Fragment,
        titleRes: Int,
        confirmPassword: Boolean,
        onSubmit: (String, String) -> Unit,
    ) {
        val context = fragment.requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val fieldGap = (10 * density).toInt()
        val exp = ExperimentalMobileDesign.enabled()
        fun styledField(block: EditText.() -> Unit) = EditText(context).apply {
            isSingleLine = true
            if (exp) {
                setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                setPadding(padding, padding, padding, padding)
                setTextAppearance(R.style.TextAppearance_Lumina_Body)
            }
            block()
        }
        val username = styledField {
            hint = context.getString(R.string.guardaflix_username_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        }
        val password = styledField {
            hint = context.getString(R.string.guardaflix_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val confirm = styledField {
            hint = context.getString(R.string.guardaflix_confirm_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            visibility = if (confirmPassword) android.view.View.VISIBLE else android.view.View.GONE
        }
        val glass = if (exp) {
            ExpDialogChrome.buildGlassMessage(
                context,
                context.getString(R.string.guardaflix_username_hint),
            )
        } else {
            null
        }
        val contentView = if (glass != null) {
            fun addField(field: EditText) {
                glass.root.addView(
                    field,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).also { it.topMargin = fieldGap },
                )
            }
            addField(username)
            addField(password)
            addField(confirm)
            glass.root
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(username, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(password, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(confirm, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        val dialog = alertBuilder(context)
            .setTitle(titleRes)
            .setView(contentView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(titleRes, null)
            .create()
        dialog.setOnShowListener {
            if (glass != null) {
                ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                with(ExpPressEffects) {
                    username.applyExpPress()
                    password.applyExpPress()
                    if (confirmPassword) confirm.applyExpPress()
                }
                ExpMotion.popIn(username)
                username.postDelayed({ ExpMotion.popIn(password) }, 36L)
                if (confirmPassword) {
                    confirm.postDelayed({ ExpMotion.popIn(confirm) }, 72L)
                }
            } else {
                ExpDialogChrome.polishShown(dialog)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val usernameValue = username.text.toString().trim()
                val passwordValue = password.text.toString()
                val confirmValue = confirm.text.toString()
                when {
                    usernameValue.length < 3 -> {
                        ExpDialogChrome.notify(context, R.string.guardaflix_invalid_username)
                    }
                    passwordValue.length < 6 -> {
                        ExpDialogChrome.notify(context, R.string.guardaflix_invalid_password)
                    }
                    confirmPassword && passwordValue != confirmValue -> {
                        ExpDialogChrome.notify(context, R.string.guardaflix_password_mismatch)
                    }
                    else -> {
                        dialog.dismiss()
                        onSubmit(usernameValue, passwordValue)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun runAuth(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        refresh: () -> Unit,
        action: () -> GuardaFlixProvider.AuthResult,
    ) {
        val context = fragment.requireContext()
        val glass = if (ExperimentalMobileDesign.enabled()) {
            ExpDialogChrome.buildGlassMessage(
                context,
                context.getString(R.string.guardaflix_auth_progress_message),
            )
        } else {
            null
        }
        val builder = alertBuilder(context)
            .setTitle(R.string.guardaflix_auth_progress_title)
            .setCancelable(false)
        if (glass != null) builder.setView(glass.root)
        else builder.setMessage(R.string.guardaflix_auth_progress_message)
        val progress = builder.create()
        progress.setOnShowListener {
            if (glass != null) ExpDialogChrome.polishGlassMessageShown(progress, glass)
            else ExpDialogChrome.polishButtons(progress)
        }
        progress.show()
        scope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            progress.dismiss()
            refresh()
            val message = if (result.ok) {
                fragment.getString(R.string.guardaflix_auth_success)
            } else {
                result.error?.takeIf { it.isNotBlank() }
                    ?: fragment.getString(R.string.guardaflix_auth_failed)
            }
            ExpDialogChrome.notify(fragment.requireContext(), message)
        }
    }
}
