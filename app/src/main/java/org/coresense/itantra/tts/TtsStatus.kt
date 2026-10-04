package org.coresense.itantra.tts

enum class TtsStatus(val description: String) {
    OFFLINE_READY("Offline Voice Installed"),
    SYSTEM_TTS_OFFLINE("System Offline Voice Available"),
    NETWORK_REQUIRED_UNAVAILABLE("Unavailable (Requires Internet Connection)"),
    NOT_INSTALLED("Voice Not Installed")
}
