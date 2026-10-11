package com.dskja.betterstreamflix.remotefind

import com.dskja.betterstreamflix.sync.CompanionShortCodes

/**
 * Short pairing codes for Remote Find Phone — A–Z / 2–9 (no 0/1/O/I/L confusion).
 */
object RemoteFindCode {
    private const val ALPHABET = CompanionShortCodes.ALPHABET

    fun newShortCode(random: () -> Int = { kotlin.random.Random.nextInt(0, ALPHABET.length) }): String =
        CompanionShortCodes.newShortCode(random)

    fun normalize(raw: String?): String? = CompanionShortCodes.normalize(raw)

    fun isValid(code: String?): Boolean = CompanionShortCodes.isValid(code)
}
