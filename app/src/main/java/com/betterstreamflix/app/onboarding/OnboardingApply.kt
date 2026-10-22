package com.betterstreamflix.app.onboarding

import android.content.Intent
import androidx.fragment.app.FragmentActivity
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.utils.AppLanguageManager
import com.betterstreamflix.app.utils.UserPreferences

/** Persists onboarding choices and restarts Main into Home when possible. */
object OnboardingApply {

    fun finish(
        activity: FragmentActivity,
        draft: OnboardingDraft,
        skipped: Boolean,
    ) {
        AppLanguageManager.setSelectedLanguage(draft.languageTag)
        val langCode = OnboardingCatalog.resolveLanguageCode(draft.languageTag)
        if (draft.languageTag != AppLanguageManager.SYSTEM_LANGUAGE) {
            UserPreferences.providerLanguage = langCode
        }

        if (draft.favoriteNames.isNotEmpty()) {
            UserPreferences.favoriteProviders =
                UserPreferences.favoriteProviders + draft.favoriteNames
        }

        val activeId = ProfileManager.activeProfileId
        if (activeId.isNotBlank()) {
            ProfileManager.updateKids(activeId, draft.kidsEnabled)
        }

        val homeProvider = resolveHomeProvider(draft)
        if (homeProvider != null) {
            UserPreferences.prepareProviderSwitch(homeProvider)
        }

        OnboardingPolicy.markComplete()

        val restart = Intent(activity, activity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_FROM_ONBOARDING, true)
            if (skipped) putExtra(EXTRA_ONBOARDING_SKIPPED, true)
        }
        activity.startActivity(restart)
        activity.finish()
    }

    private fun resolveHomeProvider(draft: OnboardingDraft): Provider? {
        draft.favoriteNames.forEach { name ->
            Provider.findByName(name)?.let { return it }
            Provider.allProviders().keys.find { it.name == name }?.let { return it }
        }
        // Skip without favorites keeps the providers screen; never force a random home.
        return UserPreferences.currentProvider
    }

    const val EXTRA_FROM_ONBOARDING = "from_onboarding"
    const val EXTRA_ONBOARDING_SKIPPED = "onboarding_skipped"
}
