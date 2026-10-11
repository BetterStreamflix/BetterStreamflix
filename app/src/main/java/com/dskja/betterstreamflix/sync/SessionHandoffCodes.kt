package com.dskja.betterstreamflix.sync

/**
 * Short codes for Phone→TV Session Handoff (pure / unit-tested).
 * Alphabet matches Cast Room: A–Z and 2–9 (no 0/1/O/I/L confusion).
 */
object SessionHandoffCodes {
    private const val ALPHABET = CompanionShortCodes.ALPHABET

    fun newShortCode(random: () -> Int = { kotlin.random.Random.nextInt(0, ALPHABET.length) }): String =
        CompanionShortCodes.newShortCode(random)

    fun normalize(raw: String?): String? = CompanionShortCodes.normalize(raw)

    fun isValid(code: String?): Boolean = CompanionShortCodes.isValid(code)

    fun formatDisplay(code: String): String = CompanionShortCodes.formatDisplay(code)
}
