package com.dskja.betterstreamflix.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportPromptPolicyTest {

    @Test
    fun cooldownConstantIsFiveDays() {
        assertEquals(5L * 24 * 60 * 60 * 1000, SupportPromptPolicy.COOLDOWN_MS)
    }

    @Test
    fun maxSoftShowsIsPositive() {
        assertTrue(SupportPromptPolicy.MAX_SOFT_SHOWS > 0)
        assertTrue(SupportPromptPolicy.MAX_SOFT_SHOWS <= 20)
    }
}

class SupportContentTest {

    @Test
    fun impactGoalsHaveProgressInRange() {
        assertTrue(SupportContent.impactGoals.isNotEmpty())
        SupportContent.impactGoals.forEach { goal ->
            assertTrue(goal.progressPercent in 0..100)
        }
        assertTrue(SupportContent.impactGoals.first().progressPercent >= 5)
    }

    @Test
    fun faqHasCoreQuestions() {
        assertTrue(SupportContent.faq.size >= 4)
    }
}

class SupportUrlsTest {

    @Test
    fun urlsAreHttps() {
        assertTrue(SupportUrls.BUY_ME_A_COFFEE_URL.startsWith("https://"))
        assertTrue(SupportUrls.BUY_ME_A_COFFEE_SHORT_URL.startsWith("https://"))
        assertTrue(SupportUrls.GITHUB_SPONSORS_URL.startsWith("https://"))
        assertTrue(SupportUrls.PATREON_URL.startsWith("https://"))
        assertTrue(SupportUrls.DISCORD_URL.startsWith("https://"))
        assertTrue(SupportUrls.GITHUB_ISSUES_URL.contains("/issues"))
        assertTrue(SupportUrls.GITHUB_RELEASES_URL.contains("/releases"))
    }

    @Test
    fun providersMarkAppreciationOnlyOnDonate() {
        assertTrue(SupportProvider.BUY_ME_A_COFFEE.marksAppreciation)
        assertTrue(SupportProvider.GITHUB_SPONSORS.marksAppreciation)
        assertTrue(SupportProvider.PATREON.marksAppreciation)
        assertFalse(SupportProvider.DISCORD.marksAppreciation)
        assertFalse(SupportProvider.TELEGRAM.marksAppreciation)
        assertFalse(SupportProvider.GITHUB_REPOSITORY.marksAppreciation)
    }
}

class SupportersCatalogTest {

    @Test
    fun parseIncludesZem936() {
        val json = """
            {
              "version": 1,
              "supporters": [
                {
                  "name": "Zem936",
                  "note": "First Buy Me a Coffee supporter",
                  "since": "2026-10-02",
                  "channel": "buy_me_a_coffee"
                }
              ]
            }
        """.trimIndent()
        val list = SupportersCatalog.parse(json)
        assertEquals(1, list.size)
        assertEquals("Zem936", list[0].name)
        assertTrue(list[0].note.contains("Buy Me a Coffee"))
    }

    @Test
    fun parseSkipsBlankNames() {
        val json = """{"supporters":[{"name":"  "},{"name":"Ada"}]}"""
        val list = SupportersCatalog.parse(json)
        assertEquals(1, list.size)
        assertEquals("Ada", list[0].name)
    }
}
