package com.dskja.betterstreamflix.extractors

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MegaPlay / Nekostream `getSources` payloads moved from plaintext `sources.file`
 * to AES-CBC ciphertext in `enc`. Keys match the player client defaults
 * (`trustAesKey` / `trustAesIv`), with UTF-8 zero-padding to 32/16 bytes.
 */
internal object MegaPlayEncDecrypt {

    const val DEFAULT_AES_KEY = "i?LMTAx0Q6,:}50U"
    const val DEFAULT_AES_IV = "W0;27ToaUpl_P%'c"

    fun decrypt(
        enc: String,
        key: String = DEFAULT_AES_KEY,
        iv: String = DEFAULT_AES_IV,
    ): String? {
        return runCatching {
            val cipherBytes = decodeCipher(enc)
            val keyBytes = utf8ZeroPad(key, 32)
            val ivBytes = utf8ZeroPad(iv, 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes),
            )
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun decodeCipher(enc: String): ByteArray {
        val trimmed = enc.trim().replace(Regex("\\s"), "")
        val pad = (4 - trimmed.length % 4) % 4
        val padded = trimmed + "=".repeat(pad)
        var last: IllegalArgumentException? = null
        for (decoder in listOf(Base64.getUrlDecoder(), Base64.getDecoder(), Base64.getMimeDecoder())) {
            try {
                return decoder.decode(padded)
            } catch (e: IllegalArgumentException) {
                last = e
            }
        }
        throw last ?: IllegalArgumentException("MegaPlay ciphertext is not base64")
    }

    private fun utf8ZeroPad(value: String, size: Int): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        val out = ByteArray(size)
        System.arraycopy(raw, 0, out, 0, minOf(raw.size, size))
        return out
    }
}
