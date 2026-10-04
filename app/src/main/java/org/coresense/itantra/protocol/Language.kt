package org.coresense.itantra.protocol

/**
 * 11 Supported Languages for iTantra:
 * 10 Indic Languages + English
 */
enum class Language(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val bcp47: String
) {
    HINDI("hi", "Hindi", "हिन्दी", "hi-IN"),
    ENGLISH("en", "English", "English", "en-IN"),
    BENGALI("bn", "Bengali", "বাংলা", "bn-IN"),
    TAMIL("ta", "Tamil", "தமிழ்", "ta-IN"),
    TELUGU("te", "Telugu", "తెలుగు", "te-IN"),
    MARATHI("mr", "Marathi", "मराठी", "mr-IN"),
    GUJARATI("gu", "Gujarati", "ગુજરાતી", "gu-IN"),
    KANNADA("kn", "Kannada", "ಕನ್ನಡ", "kn-IN"),
    MALAYALAM("ml", "Malayalam", "മലയാളം", "ml-IN"),
    PUNJABI("pa", "Punjabi", "ਪੰਜਾਬੀ", "pa-IN"),
    ODIA("or", "Odia", "ଓଡ଼ିଆ", "or-IN");

    companion object {
        fun fromCode(code: String): Language {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
        }

        fun fromOrdinal(ordinal: Int): Language {
            return entries.getOrNull(ordinal) ?: ENGLISH
        }
    }
}
