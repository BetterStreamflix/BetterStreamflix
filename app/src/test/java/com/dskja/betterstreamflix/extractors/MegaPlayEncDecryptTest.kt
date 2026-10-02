package com.dskja.betterstreamflix.extractors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MegaPlayEncDecryptTest {

    @Test
    fun decrypt_unlocksMegaPlaySourceFile() {
        // Captured Oct 2026 from megaplay.buzz getSourcesNew for One Piece ep1 (sub).
        val enc =
            "wdeBruh3qqn_i5wUNnyaPaopIIVfK48gK5UCwNjNCsj44VhzmplG_aPJoIAtFpe33aqDyH6UAuusGFTD0L9ixRbIDWH4_7RdnwixNleCsl2zzoA_GncJdYKI_6BCCyOBkO0WG2Ax0pTerx-5BJj9J2LsMGLk2CCC5XZk7szXh88"

        val decrypted = MegaPlayEncDecrypt.decrypt(enc)

        assertNotNull(decrypted)
        assertTrue(decrypted!!.contains("master.m3u8"))
        assertTrue(decrypted.contains("\"file\""))
        assertEquals(
            """{"file":"https://megap.shiora.top/f899139df5e1059396431415e770c6dd/61b87186ab260d05003427e16ccf5657/master.m3u8"}""",
            decrypted,
        )
    }

    @Test
    fun decrypt_returnsNullForGarbage() {
        val decrypted = MegaPlayEncDecrypt.decrypt("not-valid-ciphertext!!")
        assertEquals(null, decrypted)
    }
}
