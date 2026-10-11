package com.dskja.betterstreamflix.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthDomainTest {

    @Test
    fun domainUpdateAllowlist_includesKellerKinoAndKinoGer() {
        assertTrue(ProviderHealth.domainUpdateAllowlist.contains("Kellerkino"))
        assertTrue(ProviderHealth.domainUpdateAllowlist.contains("KinoGer"))
    }

    @Test
    fun supportsDomainUpdate_forConfigUrlAllowlisted() {
        assertTrue(ProviderHealth.supportsDomainUpdate(KinoGerProvider))
        assertTrue(ProviderHealth.supportsDomainUpdate(KellerKinoProvider))
        assertTrue(ProviderHealth.supportsDomainUpdate(CineHaxProvider))
    }

    @Test
    fun supportsDomainUpdate_falseForNonConfigProviders() {
        assertFalse(ProviderHealth.supportsDomainUpdate(VavooVodProvider.DE))
    }
}
