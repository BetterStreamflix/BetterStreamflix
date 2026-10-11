package com.dskja.betterstreamflix.utils

import java.util.Locale

/**
 * Tiny string-distance helpers for typo-tolerant Search (and logo matching).
 */
object FuzzyText {

    fun normalize(raw: String): String =
        raw.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")

    fun fuzzyCloseEnough(a: String, b: String, maxDistance: Int = 2): Boolean {
        val left = normalize(a)
        val right = normalize(b)
        if (left == right) return true
        if (left.isEmpty() || right.isEmpty()) return false
        if (kotlin.math.abs(left.length - right.length) > maxDistance) return false
        if (left.length < 3 || right.length < 3) return false
        return levenshtein(left, right) <= maxDistance
    }

    /** Distance between normalized forms; [Int.MAX_VALUE] when either side is blank. */
    fun distance(a: String, b: String): Int {
        val left = normalize(a)
        val right = normalize(b)
        if (left.isEmpty() || right.isEmpty()) return Int.MAX_VALUE
        if (left == right) return 0
        return levenshtein(left, right)
    }

    /**
     * Max edit distance that still counts as a typo for [query] length.
     * Short queries stay strict; longer titles allow a couple of key slips.
     */
    fun typoBudget(query: String): Int {
        val n = normalize(query).length
        return when {
            n < 4 -> 1
            n < 8 -> 2
            else -> 3
        }
    }

    fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val tmp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = tmp
            }
        }
        return dp[n]
    }
}
