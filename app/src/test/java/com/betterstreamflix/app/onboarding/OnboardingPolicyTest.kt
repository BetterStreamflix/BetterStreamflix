package com.betterstreamflix.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnboardingPolicyTest {

    @Test
    fun requiredFavoritesIsThree() {
        assertEquals(3, OnboardingPolicy.REQUIRED_FAVORITES)
    }

    @Test
    fun mobileAndTvLayoutsShareWizardIds() {
        val mobile = readLayout("dialog_onboarding_mobile.xml")
        val tv = readLayout("dialog_onboarding_tv.xml")
        listOf(
            "tv_onboarding_step",
            "tv_onboarding_title",
            "tv_onboarding_body",
            "panel_onboarding_language",
            "panel_onboarding_providers",
            "panel_onboarding_kids",
            "ll_onboarding_languages",
            "ll_onboarding_providers",
            "btn_onboarding_kids_yes",
            "btn_onboarding_kids_no",
            "btn_onboarding_skip",
            "btn_onboarding_primary",
            "tv_onboarding_provider_count",
        ).forEach { id ->
            assertTrue("mobile missing $id", mobile.contains("@+id/$id"))
            assertTrue("tv missing $id", tv.contains("@+id/$id"))
        }
        assertTrue(mobile.contains("@string/onboarding_skip"))
        assertTrue(tv.contains("@string/onboarding_skip"))
    }

    private fun readLayout(name: String): String {
        val candidates = listOf(
            File("app/src/main/res/layout/$name"),
            File("../app/src/main/res/layout/$name"),
            File("../../app/src/main/res/layout/$name"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not locate $name")
    }
}
