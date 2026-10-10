package org.coresense.itantra.stt

import org.coresense.itantra.protocol.Language

/**
 * Metadata and configuration for on-demand language model downloads.
 * Strictly adheres to SIH/iTantra offline architecture:
 * - English and Hindi are bundled directly in the APK.
 * - The other 9 Indian languages can be downloaded individually on-demand.
 * - If no verified production endpoint is active for a language, it is marked as Coming Soon.
 */
data class LanguageDownloadInfo(
    val language: Language,
    val modelUrl: String? = null,
    val tokensUrl: String? = null,
    val sha256Model: String? = null,
    val sha256Tokens: String? = null,
    val estimatedSizeBytes: Long,
    val isBundled: Boolean,
    val isAvailableForDownload: Boolean
)

object LanguageDownloadConfig {

    private val customConfigs = mutableMapOf<Language, LanguageDownloadInfo>()

    /**
     * Estimated sizes in bytes based on AI4Bharat 80MB quantized Conformer models.
     */
    private val defaultSizeMap = mapOf(
        Language.ENGLISH to 121954282L, // ~116.3 MB bundled Wav2Vec2
        Language.HINDI to 241482975L,   // ~230.3 MB bundled IndicWav2Vec
        Language.BENGALI to 85983232L,  // ~82.0 MB
        Language.TAMIL to 89128960L,    // ~85.0 MB
        Language.TELUGU to 88080384L,   // ~84.0 MB
        Language.MARATHI to 82837504L,  // ~79.0 MB
        Language.GUJARATI to 80740352L, // ~77.0 MB
        Language.KANNADA to 87031808L,  // ~83.0 MB
        Language.MALAYALAM to 90177536L,// ~86.0 MB
        Language.PUNJABI to 78643200L,  // ~75.0 MB
        Language.ODIA to 83886080L      // ~80.0 MB
    )

    fun getDownloadInfo(language: Language): LanguageDownloadInfo {
        customConfigs[language]?.let { return it }

        val isBundled = (language == Language.ENGLISH || language == Language.HINDI)
        val estimatedSize = defaultSizeMap[language] ?: (80L * 1024L * 1024L)

        return LanguageDownloadInfo(
            language = language,
            modelUrl = null,
            tokensUrl = null,
            sha256Model = null,
            sha256Tokens = null,
            estimatedSizeBytes = estimatedSize,
            isBundled = isBundled,
            isAvailableForDownload = false // Coming Soon until verified endpoint is registered
        )
    }

    fun isAvailable(language: Language): Boolean {
        return getDownloadInfo(language).isAvailableForDownload
    }

    /**
     * Configures a verified download endpoint for a language (useful for testing and production releases).
     */
    fun configureEndpoint(
        language: Language,
        modelUrl: String,
        tokensUrl: String,
        sha256Model: String? = null,
        sha256Tokens: String? = null,
        sizeBytes: Long? = null
    ) {
        val estimatedSize = sizeBytes ?: (defaultSizeMap[language] ?: (80L * 1024L * 1024L))
        customConfigs[language] = LanguageDownloadInfo(
            language = language,
            modelUrl = modelUrl,
            tokensUrl = tokensUrl,
            sha256Model = sha256Model,
            sha256Tokens = sha256Tokens,
            estimatedSizeBytes = estimatedSize,
            isBundled = (language == Language.ENGLISH || language == Language.HINDI),
            isAvailableForDownload = true
        )
    }

    fun reset() {
        customConfigs.clear()
    }
}
