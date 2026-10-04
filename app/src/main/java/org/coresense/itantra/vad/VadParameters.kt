package org.coresense.itantra.vad

data class VadParameters(
    val speechThreshold: Float = 0.50f,
    val minSilenceDurationMs: Long = 650L,
    val maxUtteranceDurationMs: Long = 15000L,
    val minSpeechDurationMs: Long = 250L
)
