package com.dskja.betterstreamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPassportTest {

    @Test
    fun clearanceGate_freshWhenClearancePresent() {
        val status = ProviderPassport.resolveStatus(
            ProviderPassport.Signals(
                kind = ProviderPassport.Kind.CLEARANCE_GATE,
                hasClearance = true,
                isLoggedIn = false,
            ),
        )
        assertEquals(ProviderPassport.Status.SESSION_FRESH, status)
    }

    @Test
    fun clearanceGate_failedValidationForcesCaptchaDueEvenWithClearance() {
        val status = ProviderPassport.resolveStatus(
            ProviderPassport.Signals(
                kind = ProviderPassport.Kind.CLEARANCE_GATE,
                hasClearance = true,
                isLoggedIn = true,
                lastValidatedOk = false,
            ),
        )
        assertEquals(ProviderPassport.Status.CAPTCHA_DUE, status)
    }

    @Test
    fun accountToken_failedValidationForcesLoginDue() {
        val status = ProviderPassport.resolveStatus(
            ProviderPassport.Signals(
                kind = ProviderPassport.Kind.ACCOUNT_TOKEN,
                isLoggedIn = true,
                lastValidatedOk = false,
            ),
        )
        assertEquals(ProviderPassport.Status.LOGIN_DUE, status)
    }

    @Test
    fun clearanceGate_captchaDueWhenLoggedInWithoutClearance() {
        val status = ProviderPassport.resolveStatus(
            ProviderPassport.Signals(
                kind = ProviderPassport.Kind.CLEARANCE_GATE,
                hasClearance = false,
                isLoggedIn = true,
            ),
        )
        assertEquals(ProviderPassport.Status.CAPTCHA_DUE, status)
    }

    @Test
    fun clearanceGate_captchaDueWhenWebSessionWithoutClearance() {
        val status = ProviderPassport.resolveStatus(
            ProviderPassport.Signals(
                kind = ProviderPassport.Kind.CLEARANCE_GATE,
                hasClearance = false,
                hasWebSession = true,
            ),
        )
        assertEquals(ProviderPassport.Status.CAPTCHA_DUE, status)
    }

    @Test
    fun clearanceGate_loginDueWhenEmpty() {
        val status = ProviderPassport.resolveStatus(
            ProviderPassport.Signals(
                kind = ProviderPassport.Kind.CLEARANCE_GATE,
            ),
        )
        assertEquals(ProviderPassport.Status.LOGIN_DUE, status)
    }

    @Test
    fun accountToken_freshWhenLoggedIn() {
        val status = ProviderPassport.resolveStatus(
            ProviderPassport.Signals(
                kind = ProviderPassport.Kind.ACCOUNT_TOKEN,
                isLoggedIn = true,
                displayName = "alice",
            ),
        )
        assertEquals(ProviderPassport.Status.SESSION_FRESH, status)
    }

    @Test
    fun accountToken_loginDueWhenSignedOut() {
        val status = ProviderPassport.resolveStatus(
            ProviderPassport.Signals(
                kind = ProviderPassport.Kind.ACCOUNT_TOKEN,
                isLoggedIn = false,
            ),
        )
        assertEquals(ProviderPassport.Status.LOGIN_DUE, status)
    }

    @Test
    fun snapshot_ordersAttentionBeforeFresh() {
        val snapshot = ProviderPassport.snapshotFromEntries(
            listOf(
                ProviderPassport.Entry(
                    name = "FreshGate",
                    actionTarget = ProviderPassport.ActionTarget.SERIENSTREAM_AUTH,
                    signals = ProviderPassport.Signals(
                        kind = ProviderPassport.Kind.CLEARANCE_GATE,
                        hasClearance = true,
                    ),
                ),
                ProviderPassport.Entry(
                    name = "CaptchaGate",
                    actionTarget = ProviderPassport.ActionTarget.ANIWORLD_AUTH,
                    signals = ProviderPassport.Signals(
                        kind = ProviderPassport.Kind.CLEARANCE_GATE,
                        isLoggedIn = true,
                    ),
                ),
                ProviderPassport.Entry(
                    name = "LoginToken",
                    actionTarget = ProviderPassport.ActionTarget.GUARDAFLIX_AUTH,
                    signals = ProviderPassport.Signals(
                        kind = ProviderPassport.Kind.ACCOUNT_TOKEN,
                    ),
                ),
            ),
            nowMs = 1_700_000_000_000L,
        )
        assertEquals(listOf("CaptchaGate", "LoginToken", "FreshGate"), snapshot.rows.map { it.name })
        assertEquals(1, snapshot.freshCount)
        assertEquals(1, snapshot.captchaDueCount)
        assertEquals(1, snapshot.loginDueCount)
        assertEquals(2, snapshot.attentionCount)
        assertEquals(ProviderPassport.Status.CAPTCHA_DUE, snapshot.rows.first().status)
        assertTrue(snapshot.rows.last().status == ProviderPassport.Status.SESSION_FRESH)
    }
}
