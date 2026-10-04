package org.coresense.itantra.stt

import org.coresense.itantra.protocol.Language

interface SttEngine {
    suspend fun initialize(lang: Language): Result<Unit>
    suspend fun transcribe(pcm16: ShortArray): Result<SttResult>
    fun currentLanguage(): Language?
    fun isLanguageLoaded(lang: Language): Boolean
    fun close()
}
