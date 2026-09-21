package com.dskja.betterstreamflix.fragments.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.activities.tools.WatchlistImportActivity
import com.dskja.betterstreamflix.providers.SerienStreamAuthManager
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * GuardaFlix-style Settings binding for the full SerienStream account / session surface:
 * status, WebView sign-in, validate, paste, copy, sign-out.
 */
object SerienStreamAuthSettingsController {

    private fun alertBuilder(context: android.content.Context) =
        if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        } else {
            AlertDialog.Builder(context)
        }

    /** Call from Settings onResume after returning from WebView sign-in. */
    fun refresh(findPreference: (String) -> Preference?) {
        val status = findPreference("SERIENSTREAM_AUTH_STATUS") ?: return
        val snap = SerienStreamAuthManager.snapshot()
        // Fragment context is not always available here — keep prior summary shape via tag.
        val host = status.context
        status.summary = buildStatusSummary(host, snap)
        findPreference("SERIENSTREAM_SESSION_VALIDATE")?.isVisible = snap.isLoggedIn
        findPreference("SERIENSTREAM_SESSION_COPY")?.isVisible = snap.isLoggedIn
        findPreference("SERIENSTREAM_SESSION_LOGOUT")?.isVisible = snap.isLoggedIn
        findPreference("SERIENSTREAM_SESSION_LOGIN")?.isVisible = true
        findPreference("SERIENSTREAM_SESSION_PASTE")?.isVisible = true
        findPreference("SERIENSTREAM_SESSION_COOKIES")?.isVisible = false
    }


    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        ensurePreferences: (() -> Unit)? = null,
        findPreference: (String) -> Preference?,
    ) {
        ensurePreferences?.invoke()

        val status = findPreference("SERIENSTREAM_AUTH_STATUS")
        val login = findPreference("SERIENSTREAM_SESSION_LOGIN")
        val validate = findPreference("SERIENSTREAM_SESSION_VALIDATE")
        val paste = findPreference("SERIENSTREAM_SESSION_PASTE")
        val copy = findPreference("SERIENSTREAM_SESSION_COPY")
        val logout = findPreference("SERIENSTREAM_SESSION_LOGOUT")
        val legacyCookies = findPreference("SERIENSTREAM_SESSION_COOKIES")

        fun refreshUi() {
            val snap = SerienStreamAuthManager.snapshot()
            status?.summary = buildStatusSummary(fragment, snap)
            login?.isVisible = true
            validate?.isVisible = snap.isLoggedIn
            paste?.isVisible = true
            copy?.isVisible = snap.isLoggedIn
            logout?.isVisible = snap.isLoggedIn
            legacyCookies?.isVisible = false
        }

        login?.setOnPreferenceClickListener {
            showCredentialsDialog(fragment, scope) { refreshUi() }
            true
        }

        validate?.setOnPreferenceClickListener {
            val ctx = fragment.requireContext()
            val glass = if (ExperimentalMobileDesign.enabled()) {
                ExpDialogChrome.buildGlassMessage(
                    ctx,
                    ctx.getString(R.string.serienstream_auth_validate_progress_message),
                )
            } else {
                null
            }
            val builder = alertBuilder(ctx)
                .setTitle(R.string.serienstream_auth_validate_progress_title)
                .setCancelable(false)
            if (glass != null) builder.setView(glass.root)
            else builder.setMessage(R.string.serienstream_auth_validate_progress_message)
            val progress = builder.create()
            progress.setOnShowListener {
                if (glass != null) ExpDialogChrome.polishGlassMessageShown(progress, glass)
                else ExpDialogChrome.polishButtons(progress)
            }
            progress.show()
            scope.launch {
                val result = SerienStreamAuthManager.validateSession()
                if (fragment.isAdded && progress.isShowing) {
                    progress.dismiss()
                }
                refreshUi()
                if (!fragment.isAdded) return@launch
                val message = when {
                    result.ok && !result.displayName.isNullOrBlank() ->
                        fragment.getString(
                            R.string.serienstream_auth_validate_ok_named,
                            result.displayName,
                        )
                    result.ok -> fragment.getString(R.string.serienstream_auth_validate_ok)
                    result.challengeActive ->
                        fragment.getString(R.string.serienstream_auth_validate_challenge)
                    result.redirectedToLogin ->
                        fragment.getString(R.string.serienstream_auth_validate_login)
                    else -> fragment.getString(R.string.serienstream_auth_validate_failed)
                }
                ExpDialogChrome.notify(
                    fragment.requireContext(),
                    message,
                    R.string.serienstream_auth_validate_progress_title,
                    ::alertBuilder,
                )
            }
            true
        }

        paste?.setOnPreferenceClickListener {
            showPasteDialog(fragment) { raw ->
                val ok = SerienStreamAuthManager.pasteCookies(raw)
                refreshUi()
                ExpDialogChrome.notify(
                    fragment.requireContext(),
                    if (ok) R.string.settings_serienstream_session_login_saved
                    else R.string.serienstream_auth_paste_invalid,
                    R.string.settings_serienstream_session_login,
                )
            }
            true
        }

        copy?.setOnPreferenceClickListener {
            val cookies = SerienStreamAuthManager.exportCookieHeader()
            if (cookies.isBlank()) {
                ExpDialogChrome.notify(
                    fragment.requireContext(),
                    R.string.settings_serienstream_session_cookies_empty,
                )
                return@setOnPreferenceClickListener true
            }
            val clipboard = fragment.requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("SerienStream cookies", cookies),
            )
            ExpDialogChrome.notify(
                fragment.requireContext(),
                R.string.serienstream_auth_copied,
            )
            true
        }

        logout?.setOnPreferenceClickListener {
            val ctx = fragment.requireContext()
            val glass = if (ExperimentalMobileDesign.enabled()) {
                ExpDialogChrome.buildGlassMessage(
                    ctx,
                    ctx.getString(R.string.serienstream_auth_logout_message),
                )
            } else {
                null
            }
            val builder = alertBuilder(ctx)
                .setTitle(R.string.settings_serienstream_session_cookies_clear_title)
            if (glass != null) builder.setView(glass.root)
            else builder.setMessage(R.string.serienstream_auth_logout_message)
            builder
                .setPositiveButton(R.string.serienstream_auth_sign_out) { _, _ ->
                    SerienStreamAuthManager.logout()
                    refreshUi()
                    ExpDialogChrome.notify(
                        fragment.requireContext(),
                        R.string.settings_serienstream_session_cookies_cleared,
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

        // Legacy single preference — redirect into the new controller actions.
        legacyCookies?.setOnPreferenceClickListener {
            if (SerienStreamAuthManager.isLoggedIn()) {
                logout?.performClick()
            } else {
                login?.performClick()
            }
            true
        }

        refreshUi()
    }

    private fun openBrowserLogin(fragment: Fragment) {
        fragment.startActivity(
            Intent(fragment.requireContext(), WatchlistImportActivity::class.java)
                .putExtra(
                    WatchlistImportActivity.EXTRA_SOURCE,
                    WatchlistImportActivity.SOURCE_SERIENSTREAM,
                )
                .putExtra(WatchlistImportActivity.EXTRA_SAVE_SESSION_ONLY, true),
        )
    }

    private fun showCredentialsDialog(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        refreshUi: () -> Unit,
    ) {
        val context = fragment.requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val fieldGap = (10 * density).toInt()
        val exp = ExperimentalMobileDesign.enabled()
        fun styledField(block: android.widget.EditText.() -> Unit) = android.widget.EditText(context).apply {
            isSingleLine = true
            if (exp) {
                setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                setPadding(padding, padding, padding, padding)
                setTextAppearance(R.style.TextAppearance_Lumina_Body)
            }
            block()
        }
        val email = styledField {
            hint = context.getString(R.string.serienstream_auth_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val password = styledField {
            hint = context.getString(R.string.serienstream_auth_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        val glass = if (exp) {
            ExpDialogChrome.buildGlassMessage(
                context,
                context.getString(R.string.serienstream_auth_credentials_message),
            )
        } else {
            null
        }
        val contentView = if (glass != null) {
            glass.root.addView(
                email,
                android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = fieldGap },
            )
            glass.root.addView(
                password,
                android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = fieldGap },
            )
            glass.root
        } else {
            android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(email, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(password, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        val dialog = alertBuilder(context)
            .setTitle(R.string.settings_serienstream_session_login)
            .setView(contentView)
            .setNeutralButton(R.string.serienstream_auth_browser_login, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_serienstream_session_login, null)
            .create()
        dialog.setOnShowListener {
            if (glass != null) {
                ExpDialogChrome.polishGlassMessageShown(dialog, glass)
            } else {
                ExpDialogChrome.polishShown(dialog)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                openBrowserLogin(fragment)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val emailValue = email.text.toString().trim()
                val passwordValue = password.text.toString()
                if (emailValue.length < 3 || passwordValue.length < 4) {
                    ExpDialogChrome.notify(context, R.string.serienstream_auth_credentials_invalid)
                    return@setOnClickListener
                }
                dialog.dismiss()
                runCredentialLogin(fragment, scope, refreshUi, emailValue, passwordValue)
            }
        }
        dialog.show()
    }

    private fun runCredentialLogin(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        refreshUi: () -> Unit,
        email: String,
        password: String,
    ) {
        val context = fragment.requireContext()
        val glass = if (ExperimentalMobileDesign.enabled()) {
            ExpDialogChrome.buildGlassMessage(
                context,
                context.getString(R.string.serienstream_auth_credentials_progress),
            )
        } else {
            null
        }
        val builder = alertBuilder(context)
            .setTitle(R.string.settings_serienstream_session_login)
            .setCancelable(false)
        if (glass != null) builder.setView(glass.root)
        else builder.setMessage(R.string.serienstream_auth_credentials_progress)
        val progress = builder.create()
        progress.setOnShowListener {
            if (glass != null) ExpDialogChrome.polishGlassMessageShown(progress, glass)
            else ExpDialogChrome.polishButtons(progress)
        }
        progress.show()
        scope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SerienStreamAuthManager.loginWithCredentials(email, password)
            }
            if (!fragment.isAdded) return@launch
            if (progress.isShowing) progress.dismiss()
            refreshUi()
            when {
                result.ok -> {
                    val message = if (!result.displayName.isNullOrBlank()) {
                        fragment.getString(
                            R.string.serienstream_auth_validate_ok_named,
                            result.displayName,
                        )
                    } else {
                        fragment.getString(R.string.settings_serienstream_session_login_saved)
                    }
                    ExpDialogChrome.notify(fragment.requireContext(), message)
                }
                result.challengeActive -> {
                    ExpDialogChrome.notify(
                        fragment.requireContext(),
                        R.string.serienstream_auth_credentials_challenge,
                    )
                    openBrowserLogin(fragment)
                }
                else -> ExpDialogChrome.notify(
                    fragment.requireContext(),
                    R.string.serienstream_auth_credentials_failed,
                )
            }
        }
    }

    private fun buildStatusSummary(
        fragment: Fragment,
        snap: SerienStreamAuthManager.SessionSnapshot,
    ): String = buildStatusSummary(fragment.requireContext(), snap)

    private fun buildStatusSummary(
        context: Context,
        snap: SerienStreamAuthManager.SessionSnapshot,
    ): String {
        if (!snap.isLoggedIn) {
            return context.getString(R.string.serienstream_auth_status_signed_out)
        }
        val namePart = snap.displayName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.serienstream_auth_status_signed_in)
        val cookiePart = context.getString(
            R.string.serienstream_auth_status_cookies,
            snap.cookieCount,
            snap.domain,
        )
        val validatedPart = when {
            snap.lastValidatedAtMs == null ->
                context.getString(R.string.serienstream_auth_status_not_validated)
            snap.lastValidatedOk == true -> {
                val whenText = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT,
                ).format(Date(snap.lastValidatedAtMs))
                context.getString(R.string.serienstream_auth_status_validated_ok, whenText)
            }
            else -> {
                val whenText = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT,
                ).format(Date(snap.lastValidatedAtMs))
                context.getString(R.string.serienstream_auth_status_validated_fail, whenText)
            }
        }
        return "$namePart\n$cookiePart\n$validatedPart"
    }

    private fun showPasteDialog(fragment: Fragment, onSubmit: (String) -> Unit) {
        val context = fragment.requireContext()
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val input = EditText(context).apply {
            hint = context.getString(R.string.serienstream_auth_paste_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 8
            setText(SerienStreamAuthManager.exportCookieHeader())
            setSelection(text?.length ?: 0)
            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                setPadding(padding, padding, padding, padding)
                setTextAppearance(R.style.TextAppearance_Lumina_Body)
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
            }
        }
        if (ExperimentalMobileDesign.enabled()) {
            val glass = ExpDialogChrome.buildGlassMessage(
                context,
                context.getString(R.string.serienstream_auth_paste_message),
            )
            glass.root.addView(
                input,
                android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = padding / 2 },
            )
            alertBuilder(context)
                .setTitle(R.string.serienstream_auth_paste_title)
                .setView(glass.root)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.serienstream_auth_paste_save) { _, _ ->
                    onSubmit(input.text?.toString().orEmpty())
                }
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                    }
                    dialog.show()
                }
        } else {
            val container = FrameLayout(context).apply {
                setPadding(padding, padding / 2, padding, 0)
                addView(
                    input,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            alertBuilder(context)
                .setTitle(R.string.serienstream_auth_paste_title)
                .setMessage(R.string.serienstream_auth_paste_message)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.serienstream_auth_paste_save) { _, _ ->
                    onSubmit(input.text?.toString().orEmpty())
                }
                .create()
                .also { dialog ->
                    ExpDialogChrome.polishButtons(dialog)
                    dialog.show()
                }
        }
    }
}
