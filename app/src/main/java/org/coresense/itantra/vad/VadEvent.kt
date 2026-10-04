package org.coresense.itantra.vad

enum class VadState {
    SILENCE,
    SPEECH_STARTED,
    SPEECH_ACTIVE,
    SPEECH_ENDED
}

sealed class VadEvent {
    object Silence : VadEvent()
    object SpeechStarted : VadEvent()
    data class SpeechActive(val probability: Float) : VadEvent()
    data class SpeechEnded(
        val completeUtterance: ShortArray,
        val durationMs: Long
    ) : VadEvent()
}
