package com.dskja.betterstreamflix.extractors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    fun decrypt_acceptsStandardBase64WithoutPadding() {
        val plain = """{"file":"https://cdn.example/master.m3u8"}"""
        val key = "i?LMTAx0Q6,:}50U".toByteArray(Charsets.UTF_8).copyOf(32)
        val iv = "W0;27ToaUpl_P%'c".toByteArray(Charsets.UTF_8).copyOf(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray()))
            .trimEnd('=')

        assertEquals(plain, MegaPlayEncDecrypt.decrypt(encoded))
    }

    @Test
    fun decrypt_returnsNullForGarbage() {
        val decrypted = MegaPlayEncDecrypt.decrypt("not-valid-ciphertext!!")
        assertEquals(null, decrypted)
    }
}
