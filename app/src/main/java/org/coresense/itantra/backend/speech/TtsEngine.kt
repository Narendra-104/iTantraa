package org.coresense.itantra.backend.speech

import org.coresense.itantra.core.protocol.Language

interface TtsEngine {
    fun isReady(): Boolean = true
    suspend fun speak(text: String, lang: Language, isEmergency: Boolean = false): Result<Unit>
    fun stop()
    fun isLanguageAvailable(lang: Language): TtsStatus
    fun close()
}
