package com.betterstreamflix.app.onboarding

import com.betterstreamflix.app.utils.AppLanguageManager

/** In-memory wizard state for one onboarding session. */
data class OnboardingDraft(
    var languageTag: String = AppLanguageManager.SYSTEM_LANGUAGE,
    val favoriteNames: LinkedHashSet<String> = LinkedHashSet(),
    var kidsEnabled: Boolean = false,
)
