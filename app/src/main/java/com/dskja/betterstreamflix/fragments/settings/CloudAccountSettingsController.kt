package com.dskja.betterstreamflix.fragments.settings

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.sync.CloudSyncManager
import com.dskja.betterstreamflix.sync.CloudSyncProgress
import com.dskja.betterstreamflix.sync.SupabaseProvider
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object CloudAccountSettingsController {

    private fun alertBuilder(context: android.content.Context) =
        if (com.dskja.betterstreamflix.utils.ExperimentalMobileDesign.enabled()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        } else {
            AlertDialog.Builder(context)
        }

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
    ) {
        val status = findPreference("cloud_account_status") ?: return
        val signIn = findPreference("cloud_sign_in")
        val signUp = findPreference("cloud_sign_up")
        val signOut = findPreference("cloud_sign_out")
        val syncNow = findPreference("cloud_sync_now")

        fun refresh() {
            val email = CloudSyncManager.currentUserEmail()
            status.summary = email?.let {
                fragment.getString(R.string.cloud_sync_signed_in_as, it)
            } ?: fragment.getString(R.string.cloud_sync_signed_out)
            signIn?.isVisible = email == null
            signUp?.isVisible = email == null
            signOut?.isVisible = email != null
            syncNow?.isVisible = email != null
            status.isEnabled = SupabaseProvider.isConfigured
            signIn?.isEnabled = SupabaseProvider.isConfigured && email == null
            signUp?.isEnabled = SupabaseProvider.isConfigured && email == null
            signOut?.isEnabled = SupabaseProvider.isConfigured && email != null
            syncNow?.isEnabled = SupabaseProvider.isConfigured && email != null
        }

        signIn?.setOnPreferenceClickListener {
            showCredentialsDialog(fragment, R.string.cloud_sync_sign_in) { email, password ->
                runProgressAction(fragment, scope, ::refresh) { onProgress ->
                    CloudSyncManager.signIn(
                        fragment.requireContext(),
                        email,
                        password,
                        onProgress,
                    )
                    R.string.cloud_sync_sign_in_success
                }
            }
            true
        }

        signUp?.setOnPreferenceClickListener {
            showCredentialsDialog(fragment, R.string.cloud_sync_sign_up) { email, password ->
                runProgressAction(fragment, scope, ::refresh) { onProgress ->
                    val signedIn = CloudSyncManager.signUp(
                        fragment.requireContext(),
                        email,
                        password,
                        onProgress,
                    )
                    if (signedIn) R.string.cloud_sync_sign_up_success else R.string.cloud_sync_confirm_email
                }
            }
            true
        }

        signOut?.setOnPreferenceClickListener {
            val ctx = fragment.requireContext()
            val email = CloudSyncManager.currentUserEmail()
            val message = email?.let {
                fragment.getString(R.string.cloud_sync_signed_in_as, it)
            } ?: fragment.getString(R.string.cloud_sync_signed_out)
            val glass = if (ExperimentalMobileDesign.enabled()) {
                ExpDialogChrome.buildGlassMessage(ctx, message)
            } else {
                null
            }
            val builder = alertBuilder(ctx)
                .setTitle(R.string.cloud_sync_sign_out)
            if (glass != null) builder.setView(glass.root)
            else builder.setMessage(message)
            builder
                .setPositiveButton(R.string.cloud_sync_sign_out) { _, _ ->
                    runAction(fragment, scope, ::refresh) {
                        CloudSyncManager.signOut(fragment.requireContext())
                        R.string.cloud_sync_sign_out_success
                    }
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

        syncNow?.setOnPreferenceClickListener {
            runProgressAction(fragment, scope, ::refresh) { onProgress ->
                CloudSyncManager.syncNow(fragment.requireContext(), onProgress)
                R.string.cloud_sync_success
            }
            true
        }

        refresh()
    }

    private fun showCredentialsDialog(
        fragment: Fragment,
        titleRes: Int,
        onSubmit: (String, String) -> Unit,
    ) {
        val context = fragment.requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val fieldGap = (10 * density).toInt()
        val exp = ExperimentalMobileDesign.enabled()
        val email = EditText(context).apply {
            hint = context.getString(R.string.cloud_sync_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
            if (exp) {
                setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                setPadding(padding, padding, padding, padding)
                setTextAppearance(R.style.TextAppearance_Lumina_Body)
            }
        }
        val password = EditText(context).apply {
            hint = context.getString(R.string.cloud_sync_password_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            if (exp) {
                setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                setPadding(padding, padding, padding, padding)
                setTextAppearance(R.style.TextAppearance_Lumina_Body)
            }
        }
        val glass = if (exp) {
            ExpDialogChrome.buildGlassMessage(
                context,
                context.getString(R.string.cloud_sync_email_hint),
            )
        } else {
            null
        }
        val contentView = if (glass != null) {
            glass.root.addView(
                email,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = fieldGap },
            )
            glass.root.addView(
                password,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = fieldGap },
            )
            glass.root
        } else {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(email, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(password, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
                    email.applyExpPress()
                    password.applyExpPress()
                }
                ExpMotion.popIn(email)
                email.postDelayed({ ExpMotion.popIn(password) }, 40L)
            } else {
                ExpDialogChrome.polishShown(dialog)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val emailValue = email.text.toString().trim()
                val passwordValue = password.text.toString()
                if (!emailValue.contains('@') || passwordValue.length < 6) {
                    ExpDialogChrome.notify(context, R.string.cloud_sync_invalid_credentials)
                    return@setOnClickListener
                }
                dialog.dismiss()
                onSubmit(emailValue, passwordValue)
            }
        }
        dialog.show()
    }

    private fun runProgressAction(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        refresh: () -> Unit,
        action: suspend ((CloudSyncProgress) -> Unit) -> Int,
    ) {
        val context = fragment.requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val progressBar = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            if (ExperimentalMobileDesign.enabled()) {
                progressTintList = android.content.res.ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                        this,
                        androidx.appcompat.R.attr.colorPrimary,
                    ),
                )
            }
        }
        val message = TextView(context).apply {
            setText(R.string.cloud_sync_progress_connecting)
            if (ExperimentalMobileDesign.enabled()) {
                setTextAppearance(R.style.TextAppearance_Lumina_Body)
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                progressBar,
                LinearLayout.LayoutParams(
                    (72 * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                message,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    marginStart = padding / 2
                },
            )
        }
        val glass = if (ExperimentalMobileDesign.enabled()) {
            ExpDialogChrome.buildGlassMessage(
                context,
                context.getString(R.string.cloud_sync_progress_connecting),
            ).also { g ->
                g.body.visibility = android.view.View.GONE
                g.root.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).also { it.topMargin = (4 * density).toInt() },
                )
            }
        } else {
            null
        }
        val contentView = glass?.root ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(row)
        }
        val dialog = alertBuilder(context)
            .setTitle(R.string.cloud_sync_progress_title)
            .setView(contentView)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            if (glass != null) ExpDialogChrome.polishGlassMessageShown(dialog, glass)
            else ExpDialogChrome.polishButtons(dialog)
        }
        dialog.show()

        scope.launch {
            try {
                val resultMessage = action { progress ->
                    updateProgress(fragment, progressBar, message, progress)
                }
                if (!fragment.isAdded) return@launch
                if (dialog.isShowing) dialog.dismiss()
                refresh()
                val ctx = fragment.context ?: return@launch
                ExpDialogChrome.notify(ctx, resultMessage)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (fragment.isAdded) showError(fragment, error)
            } finally {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    private fun updateProgress(
        fragment: Fragment,
        progressBar: ProgressBar,
        message: TextView,
        progress: CloudSyncProgress,
    ) {
        when (progress.stage) {
            CloudSyncProgress.Stage.AUTHENTICATING -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_authenticating)
            }
            CloudSyncProgress.Stage.CHECKING_CLOUD -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_checking_cloud)
            }
            CloudSyncProgress.Stage.PREPARING_LOCAL -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_preparing_local)
            }
            CloudSyncProgress.Stage.MERGING -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_merging)
            }
            CloudSyncProgress.Stage.UPLOADING -> {
                progressBar.isIndeterminate = false
                progressBar.max = progress.total.coerceAtLeast(1)
                progressBar.progress = progress.current
                message.text = fragment.getString(
                    R.string.cloud_sync_progress_uploading,
                    progress.current,
                    progress.total,
                )
            }
            CloudSyncProgress.Stage.APPLYING_CLOUD -> {
                progressBar.isIndeterminate = true
                message.text = fragment.resources.getQuantityString(
                    R.plurals.cloud_sync_progress_applying_cloud,
                    progress.total,
                    progress.total,
                )
            }
            CloudSyncProgress.Stage.FINALIZING -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_finalizing)
            }
        }
    }

    private fun runAction(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        refresh: () -> Unit,
        action: suspend () -> Int,
    ) {
        scope.launch {
            runCatching { action() }
                .onSuccess { message ->
                    if (!fragment.isAdded) return@onSuccess
                    refresh()
                    val ctx = fragment.context ?: return@onSuccess
                    ExpDialogChrome.notify(ctx, message)
                }
                .onFailure { error ->
                    if (fragment.isAdded) showError(fragment, error)
                }
        }
    }

    private fun showError(fragment: Fragment, error: Throwable) {
        val ctx = fragment.context ?: return
        val message = when (error) {
            is com.dskja.betterstreamflix.sync.CloudAccountAlreadyLinkedException ->
                fragment.getString(R.string.cloud_sync_account_already_linked)
            else -> fragment.getString(
                R.string.cloud_sync_error,
                error.message ?: error.javaClass.simpleName,
            )
        }
        ExpDialogChrome.notify(ctx, message)
    }
}
