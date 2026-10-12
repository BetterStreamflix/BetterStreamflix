package com.dskja.betterstreamflix.fragments.settings

import android.content.Context
import android.text.format.DateUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.providers.ProviderPassport
import com.dskja.betterstreamflix.utils.ExpMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ProviderPassportSettingsController {

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
        openScreen: (screenKey: String, title: String) -> Unit,
    ) {
        val context = fragment.requireContext()
        val summaryPref = findPreference("PROVIDER_PASSPORT_SUMMARY")
        val refreshPref = findPreference("PROVIDER_PASSPORT_REFRESH")
        val attentionCategory =
            findPreference("pc_provider_passport_attention") as? PreferenceCategory
        val freshCategory =
            findPreference("pc_provider_passport_fresh") as? PreferenceCategory

        fun paint(snapshot: ProviderPassport.Snapshot) {
            summaryPref?.summary = context.getString(
                R.string.settings_provider_passport_summary_counts,
                snapshot.freshCount,
                snapshot.captchaDueCount,
                snapshot.loginDueCount,
            )
            val whenLabel = DateUtils.getRelativeTimeSpanString(
                snapshot.probedAtMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            refreshPref?.summary = context.getString(
                R.string.settings_provider_passport_refreshed_at,
                whenLabel,
            )
            paintCategory(
                context = context,
                hostView = fragment.view,
                category = attentionCategory,
                rows = snapshot.rows.filter {
                    it.status != ProviderPassport.Status.SESSION_FRESH
                },
                emptyTitle = context.getString(R.string.settings_provider_passport_attention_empty),
                openScreen = openScreen,
            )
            paintCategory(
                context = context,
                hostView = fragment.view,
                category = freshCategory,
                rows = snapshot.rows.filter {
                    it.status == ProviderPassport.Status.SESSION_FRESH
                },
                emptyTitle = context.getString(R.string.settings_provider_passport_fresh_empty),
                openScreen = openScreen,
            )
            freshCategory?.isVisible = snapshot.freshCount > 0 || snapshot.rows.isNotEmpty()
        }

        fun runRefresh(validate: Boolean) {
            refreshPref?.isEnabled = false
            refreshPref?.summary =
                context.getString(R.string.settings_provider_passport_checking)
            scope.launch {
                val snapshot = withContext(Dispatchers.IO) {
                    ProviderPassport.refresh(validateSessions = validate)
                }
                if (!fragment.isAdded) return@launch
                refreshPref?.isEnabled = true
                paint(snapshot)
            }
        }

        paint(ProviderPassport.snapshotFromRuntime())
        runRefresh(validate = true)

        refreshPref?.setOnPreferenceClickListener {
            fragment.view?.let(ExpMotion::hapticTap)
            runRefresh(validate = true)
            true
        }
    }

    private fun paintCategory(
        context: Context,
        hostView: android.view.View?,
        category: PreferenceCategory?,
        rows: List<ProviderPassport.Row>,
        emptyTitle: String?,
        openScreen: (screenKey: String, title: String) -> Unit,
    ) {
        if (category == null) return
        category.removeAll()
        if (rows.isEmpty()) {
            if (emptyTitle != null) {
                category.addPreference(
                    Preference(context).apply {
                        key = "${category.key}_empty"
                        title = emptyTitle
                        isSelectable = false
                        isIconSpaceReserved = false
                    },
                )
            }
            return
        }
        rows.forEach { row ->
            category.addPreference(
                Preference(context).apply {
                    key = "provider_passport_row_${row.name}"
                    title = row.name
                    summary = rowSummary(context, row)
                    isIconSpaceReserved = false
                    isSelectable = row.actionTarget != ProviderPassport.ActionTarget.NONE
                    if (isSelectable) {
                        setOnPreferenceClickListener {
                            hostView?.let(ExpMotion::hapticTap)
                            val (key, title) = authScreen(context, row.actionTarget)
                            if (key != null) openScreen(key, title)
                            true
                        }
                    }
                },
            )
        }
    }

    private fun rowSummary(context: Context, row: ProviderPassport.Row): String {
        val status = when (row.status) {
            ProviderPassport.Status.SESSION_FRESH ->
                context.getString(R.string.provider_passport_status_session_fresh)
            ProviderPassport.Status.CAPTCHA_DUE ->
                context.getString(R.string.provider_passport_status_captcha_due)
            ProviderPassport.Status.LOGIN_DUE ->
                context.getString(R.string.provider_passport_status_login_due)
        }
        val account = row.displayName?.takeIf { it.isNotBlank() }?.let { name ->
            context.getString(R.string.settings_provider_passport_as_user, name)
        }
        val domain = row.domain?.takeIf { it.isNotBlank() }
        val cta = when (row.status) {
            ProviderPassport.Status.CAPTCHA_DUE ->
                context.getString(R.string.settings_provider_passport_cta_captcha)
            ProviderPassport.Status.LOGIN_DUE ->
                context.getString(R.string.settings_provider_passport_cta_login)
            ProviderPassport.Status.SESSION_FRESH ->
                context.getString(R.string.settings_provider_passport_cta_manage)
        }
        return listOfNotNull(status, account, domain, cta).joinToString(" · ")
    }

    private fun authScreen(
        context: Context,
        target: ProviderPassport.ActionTarget,
    ): Pair<String?, String> = when (target) {
        ProviderPassport.ActionTarget.SERIENSTREAM_AUTH ->
            "screen_serienstream_auth" to
                context.getString(R.string.serienstream_auth_category_title)
        ProviderPassport.ActionTarget.ANIWORLD_AUTH ->
            "screen_aniworld_auth" to
                context.getString(R.string.aniworld_auth_category_title)
        ProviderPassport.ActionTarget.GUARDAFLIX_AUTH ->
            "screen_guardaflix_auth" to
                context.getString(R.string.settings_category_guardaflix)
        ProviderPassport.ActionTarget.NONE -> null to ""
    }
}
