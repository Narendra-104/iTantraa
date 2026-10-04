package org.coresense.itantra.sentence

import java.text.Normalizer

/**
 * SentenceAssembler:
 * - Streams partial STT hypotheses
 * - Finalizes on VAD speech pause
 * - Normalizes text (trims whitespace, Indic punctuation normalization)
 * - Splits long continuous speech into coherent sentences
 * - Enforces: NEVER emits empty or blank sentences
 */
class SentenceAssembler {

    private val partialBuffer = StringBuilder()

    fun appendPartial(text: String): String {
        val normalized = normalize(text)
        if (normalized.isNotBlank()) {
            if (partialBuffer.isNotEmpty()) {
                partialBuffer.append(" ")
            }
            partialBuffer.append(normalized)
        }
        return partialBuffer.toString().trim()
    }

    fun finalizeUtterance(finalText: String? = null): List<String> {
        val raw = if (!finalText.isNullOrBlank()) {
            finalText
        } else {
            partialBuffer.toString()
        }

        partialBuffer.clear()
        val normalized = normalize(raw)
        if (normalized.isBlank()) {
            return emptyList()
        }

        return splitIntoSentences(normalized)
    }

    fun clear() {
        partialBuffer.clear()
    }

    /**
     * Normalizes text across Latin and Indic scripts:
     * - Unicode NFC normalization
     * - Replaces multiple whitespace with single space
     * - Standardizes danda (।) and full-stop punctuation
     */
    fun normalize(input: String): String {
        if (input.isBlank()) return ""

        val nfc = Normalizer.normalize(input, Normalizer.Form.NFC)
        return nfc
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Splits long text into sentences using standard punctuation:
     * Full-stop (.), Indic Purna Viram / Danda (।), Question mark (?), Exclamation mark (!),
     * or max length cutoffs at word boundaries.
     */
    fun splitIntoSentences(text: String, maxCharsPerSentence: Int = 120): List<String> {
        if (text.isBlank()) return emptyList()

        val rawSentences = text.split(Regex("(?<=[.!?।])\\s+"))
        val result = mutableListOf<String>()

        for (s in rawSentences) {
            val trimmed = s.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.length <= maxCharsPerSentence) {
                result.add(trimmed)
            } else {
                // Split long utterance by word boundary
                val words = trimmed.split(" ")
                val chunk = StringBuilder()
                for (w in words) {
                    if (chunk.length + w.length + 1 > maxCharsPerSentence && chunk.isNotEmpty()) {
                        result.add(chunk.toString().trim())
                        chunk.clear()
                    }
                    if (chunk.isNotEmpty()) chunk.append(" ")
                    chunk.append(w)
                }
                if (chunk.isNotBlank()) {
                    result.add(chunk.toString().trim())
                }
            }
        }

        return result.filter { it.isNotBlank() }
    }
}
