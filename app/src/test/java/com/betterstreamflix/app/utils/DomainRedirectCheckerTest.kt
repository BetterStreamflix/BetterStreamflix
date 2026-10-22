package com.betterstreamflix.app.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRedirectCheckerTest {

    @Test
    fun isBlockedHost_parkingExactAndSuffix() {
        assertTrue(DomainRedirectChecker.isBlockedHost("sedo.com"))
        assertTrue(DomainRedirectChecker.isBlockedHost("www.sedo.com"))
        assertTrue(DomainRedirectChecker.isBlockedHost("foo.sedo.com"))
        assertTrue(DomainRedirectChecker.isBlockedHost("parking.example.com"))
        assertTrue(DomainRedirectChecker.isBlockedHost("example.parking"))
    }

    @Test
    fun isBlockedHost_doesNotFalsePositiveOnParkingSubstring() {
        // Mid-label "parking" must not block unrelated hosts.
        assertFalse(DomainRedirectChecker.isBlockedHost("sparking.com"))
        assertFalse(DomainRedirectChecker.isBlockedHost("notparkingcdn.example.com"))
        assertFalse(DomainRedirectChecker.isBlockedHost("myparkinglot.io"))
    }

    @Test
    fun isBlockedHost_cloudflareOnlyChallengeOrExact() {
        assertTrue(DomainRedirectChecker.isBlockedHost("cloudflare.com"))
        assertTrue(DomainRedirectChecker.isBlockedHost("www.cloudflare.com"))
        assertTrue(DomainRedirectChecker.isBlockedHost("challenge.cloudflare.com"))
        // Merely containing the substring must not block.
        assertFalse(DomainRedirectChecker.isBlockedHost("cdn.cloudflarecdn.example.com"))
        assertFalse(DomainRedirectChecker.isBlockedHost("notcloudflare.com"))
    }
}
