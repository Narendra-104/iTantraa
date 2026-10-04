package org.coresense.itantra.metrics

import kotlin.math.min

object WerCalculator {

    data class WerResult(
        val wer: Float,
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        val referenceWordsCount: Int,
        val hypothesisWordsCount: Int
    )

    /**
     * Computes true Word Error Rate (WER) using Levenshtein distance on words:
     * WER = (Substitutions + Deletions + Insertions) / N
     */
    fun calculate(reference: String, hypothesis: String): WerResult {
        val refWords = tokenize(reference)
        val hypWords = tokenize(hypothesis)

        val n = refWords.size
        val m = hypWords.size

        if (n == 0) {
            return if (m == 0) {
                WerResult(0f, 0, 0, 0, 0, 0)
            } else {
                WerResult(1.0f, 0, 0, m, 0, m)
            }
        }

        // DP matrix
        val d = Array(n + 1) { IntArray(m + 1) }

        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j

        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (refWords[i - 1].equals(hypWords[j - 1], ignoreCase = true)) 0 else 1
                d[i][j] = min(
                    min(d[i - 1][j] + 1, d[i][j - 1] + 1), // deletion, insertion
                    d[i - 1][j - 1] + cost // substitution
                )
            }
        }

        // Backtrack to count S, D, I
        var i = n
        var j = m
        var substitutions = 0
        var deletions = 0
        var insertions = 0

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                val cost = if (refWords[i - 1].equals(hypWords[j - 1], ignoreCase = true)) 0 else 1
                if (d[i][j] == d[i - 1][j - 1] + cost) {
                    if (cost == 1) substitutions++
                    i--
                    j--
                    continue
                }
            }
            if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                deletions++
                i--
                continue
            }
            if (j > 0 && d[i][j] == d[i][j - 1] + 1) {
                insertions++
                j--
                continue
            }
            break
        }

        val wer = (substitutions + deletions + insertions).toFloat() / n.toFloat()

        return WerResult(
            wer = wer,
            substitutions = substitutions,
            deletions = deletions,
            insertions = insertions,
            referenceWordsCount = n,
            hypothesisWordsCount = m
        )
    }

    private fun tokenize(text: String): List<String> {
        return text.trim()
            .replace(Regex("[.,!?।\"'()\\-\\[\\]]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }
}
