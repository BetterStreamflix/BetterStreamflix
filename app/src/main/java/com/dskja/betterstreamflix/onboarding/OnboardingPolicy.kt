package com.dskja.betterstreamflix.onboarding

import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * First-launch 60s onboarding: language → 3 favorite providers → Kids yes/no → Home.
 * Existing installs (already have a provider or favorites) are grandfathered once.
 */
object OnboardingPolicy {

    const val REQUIRED_FAVORITES = 3

    fun shouldShow(): Boolean {
        grandfatherExistingInstallIfNeeded()
        return !UserPreferences.onboardingCompleted
    }

    fun markComplete() {
        UserPreferences.onboardingCompleted = true
    }

    /**
     * Upgrades must not suddenly show the wizard. Any prior provider pick or
     * favorites set means the user already set up the app.
     */
    fun grandfatherExistingInstallIfNeeded() {
        if (UserPreferences.onboardingCompleted) return
        if (UserPreferences.currentProvider != null ||
            UserPreferences.favoriteProviders.isNotEmpty()
        ) {
            markComplete()
        }
    }
}
