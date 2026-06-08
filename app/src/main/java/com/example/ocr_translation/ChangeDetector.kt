package com.example.ocr_translation

/**
 * Decides whether the on-screen text changed enough to warrant a new translation.
 * Tolerates OCR jitter (status bar, anti-aliasing noise) via a similarity threshold,
 * so a static screen does not trigger repeated API requests.
 *
 * Methods may be called from different threads (capture coroutine + accessibility callback),
 * so [lastSignature] access is synchronized.
 */
class ChangeDetector(private val threshold: Double = 0.90) {

    private val lock = Any()
    private var lastSignature: String? = null

    /** Returns true if [blocks] differ enough from the last accepted text. Updates state when it does. */
    fun hasChanged(blocks: List<OCRProcessor.TextBlock>): Boolean {
        val signature = normalize(blocks)
        synchronized(lock) {
            val prev = lastSignature
            if (prev != null && similarity(signature, prev) >= threshold) return false
            lastSignature = signature
            return true
        }
    }

    fun reset() {
        synchronized(lock) { lastSignature = null }
    }

    private fun normalize(blocks: List<OCRProcessor.TextBlock>): String =
        blocks.joinToString("|") { it.text }.lowercase().replace("\\s+".toRegex(), "")

    private fun similarity(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev else 1 + minOf(prev, dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return dp[b.length]
    }
}