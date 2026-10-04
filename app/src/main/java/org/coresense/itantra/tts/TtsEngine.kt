package org.coresense.itantra.tts

import org.coresense.itantra.protocol.Language

interface TtsEngine {
    suspend fun speak(text: String, lang: Language, isEmergency: Boolean = false): Result<Unit>
    fun stop()
    fun isLanguageAvailable(lang: Language): TtsStatus
    fun close()
}
