package org.coresense.itantra.stt

data class SttResult(
    val text: String,
    val confidence: Float,
    val processingTimeMs: Long,
    val audioDurationMs: Long,
    val rtf: Float = if (audioDurationMs > 0) processingTimeMs.toFloat() / audioDurationMs.toFloat() else 0f
)
