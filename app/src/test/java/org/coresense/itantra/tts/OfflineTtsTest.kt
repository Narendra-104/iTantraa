package org.coresense.itantra.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.coresense.itantra.protocol.*
import org.junit.Assert.*
import org.junit.Test

class OfflineTtsTest {

    @Test
    fun testLanguageFromDisplayNameResolution() {
        assertEquals(Language.HINDI, Language.fromDisplayName("Hindi"))
        assertEquals(Language.HINDI, Language.fromDisplayName("hindi"))
        assertEquals(Language.ENGLISH, Language.fromDisplayName("English"))
        assertEquals(Language.ENGLISH, Language.fromDisplayName("en"))
        assertEquals(Language.MARATHI, Language.fromDisplayName("Marathi"))
        assertEquals(Language.BENGALI, Language.fromDisplayName("Bengali"))
        assertEquals(Language.TAMIL, Language.fromDisplayName("Tamil"))
        assertEquals(Language.TELUGU, Language.fromDisplayName("Telugu"))
        assertEquals(Language.GUJARATI, Language.fromDisplayName("Gujarati"))
        assertEquals(Language.KANNADA, Language.fromDisplayName("Kannada"))
        assertEquals(Language.MALAYALAM, Language.fromDisplayName("Malayalam"))
        assertEquals(Language.PUNJABI, Language.fromDisplayName("Punjabi"))
        assertEquals(Language.ODIA, Language.fromDisplayName("Odia"))
        // Fallback for null or unknown
        assertEquals(Language.ENGLISH, Language.fromDisplayName(null))
        assertEquals(Language.ENGLISH, Language.fromDisplayName("Klingon"))
    }

    @Test
    fun testOfflineTtsRejectsCloudRequirement() = runBlocking {
        val testEngine = FakeOfflineTtsEngine()
        testEngine.setVoiceAvailable(Language.HINDI, TtsStatus.NETWORK_REQUIRED_UNAVAILABLE)

        val result = testEngine.speak("नमस्ते", Language.HINDI)
        assertTrue("TTS must fail if offline voice is missing and requires network", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("offline") == true)
    }

    @Test
    fun testOfflineTtsSucceedsWhenOfflineVoiceInstalled() = runBlocking {
        val testEngine = FakeOfflineTtsEngine()
        testEngine.setVoiceAvailable(Language.HINDI, TtsStatus.SYSTEM_TTS_OFFLINE)

        val result = testEngine.speak("नमस्ते", Language.HINDI)
        assertTrue("TTS must succeed when offline voice is present", result.isSuccess)
        assertEquals("नमस्ते", testEngine.lastSpokenText)
        assertEquals(Language.HINDI, testEngine.lastSpokenLang)
        assertFalse(testEngine.lastWasEmergency)
    }

    @Test
    fun testEmergencyAnnouncementOverridesPriority() = runBlocking {
        val testEngine = FakeOfflineTtsEngine()
        testEngine.setVoiceAvailable(Language.ENGLISH, TtsStatus.SYSTEM_TTS_OFFLINE)

        val result = testEngine.speak("Emergency SOS incoming", Language.ENGLISH, isEmergency = true)
        assertTrue(result.isSuccess)
        assertTrue("Emergency flag must be forwarded", testEngine.lastWasEmergency)
    }

    @Test
    fun testStopInterruptsActivePlayback() = runBlocking {
        val testEngine = FakeOfflineTtsEngine()
        testEngine.setVoiceAvailable(Language.ENGLISH, TtsStatus.SYSTEM_TTS_OFFLINE)

        testEngine.speak("Playing a long message", Language.ENGLISH)
        assertTrue(testEngine.isSpeaking)

        testEngine.stop()
        assertFalse("Stop must terminate active speech playback", testEngine.isSpeaking)
    }

    // -------------------------------------------------------------------------
    // Step 11: Playback Controls and Auto-Read Test Suite
    // -------------------------------------------------------------------------

    @Test
    fun testAutoReadDefaultValueAndToggle() {
        val engine = FakeOfflineTtsEngine()
        val controller = PlaybackSessionController(engine)

        // 1. Initial default value must be true (Auto-Read enabled by default)
        assertTrue(controller.isAutoTtsEnabled.value)

        // 2. Toggle to false
        controller.toggleAutoTts(false)
        assertFalse(controller.isAutoTtsEnabled.value)

        // 3. Toggle back to true
        controller.toggleAutoTts(true)
        assertTrue(controller.isAutoTtsEnabled.value)

        // 4. Toggle without param inverts current state
        controller.toggleAutoTts()
        assertFalse(controller.isAutoTtsEnabled.value)
    }

    @Test
    fun testIncomingMessageSpokenWhenAutoReadOn() = runBlocking {
        val engine = FakeOfflineTtsEngine()
        engine.setVoiceAvailable(Language.ENGLISH, TtsStatus.SYSTEM_TTS_OFFLINE)
        val controller = PlaybackSessionController(engine)

        val packet = Packet(
            msgId = 101L,
            senderId = "SENDER-A",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Supplies arriving at checkpoint",
            timestamp = System.currentTimeMillis()
        )

        controller.handleIncomingPacket(packet)

        // Auto-Read is ON -> TTS must be invoked
        assertTrue(engine.isSpeaking)
        assertEquals("Supplies arriving at checkpoint", engine.lastSpokenText)
        assertEquals(101L, controller.currentlyPlayingMessageId.value)
        assertTrue(controller.isTtsSpeaking.value)
    }

    @Test
    fun testIncomingMessageNotSpokenWhenAutoReadOff() = runBlocking {
        val engine = FakeOfflineTtsEngine()
        engine.setVoiceAvailable(Language.ENGLISH, TtsStatus.SYSTEM_TTS_OFFLINE)
        val controller = PlaybackSessionController(engine)

        // Turn Auto-Read OFF
        controller.toggleAutoTts(false)

        val packet = Packet(
            msgId = 102L,
            senderId = "SENDER-B",
            seq = 2,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Routine update message",
            timestamp = System.currentTimeMillis()
        )

        controller.handleIncomingPacket(packet)

        // Auto-Read is OFF -> TTS must NOT be invoked
        assertFalse(engine.isSpeaking)
        assertNull(engine.lastSpokenText)
        assertNull(controller.currentlyPlayingMessageId.value)
        assertFalse(controller.isTtsSpeaking.value)
    }

    @Test
    fun testManualReadAloudWorksWhileAutoReadOff() = runBlocking {
        val engine = FakeOfflineTtsEngine()
        engine.setVoiceAvailable(Language.HINDI, TtsStatus.SYSTEM_TTS_OFFLINE)
        val controller = PlaybackSessionController(engine)

        // Auto-Read is OFF
        controller.toggleAutoTts(false)

        // Manual playback must still function
        controller.togglePlayMessage(201L, "नमस्ते दुनिया", Language.HINDI)

        assertTrue(engine.isSpeaking)
        assertEquals("नमस्ते दुनिया", engine.lastSpokenText)
        assertEquals(201L, controller.currentlyPlayingMessageId.value)
        assertTrue(controller.isTtsSpeaking.value)
    }

    @Test
    fun testStopPlaybackClearsStateAndCallsTtsStop() = runBlocking {
        val engine = FakeOfflineTtsEngine()
        engine.setVoiceAvailable(Language.ENGLISH, TtsStatus.SYSTEM_TTS_OFFLINE)
        val controller = PlaybackSessionController(engine)

        controller.togglePlayMessage(301L, "Message to stop", Language.ENGLISH)
        assertTrue(engine.isSpeaking)
        assertTrue(controller.isTtsSpeaking.value)
        assertEquals(301L, controller.currentlyPlayingMessageId.value)

        // Stop speech
        controller.stopPlayback()

        assertFalse(engine.isSpeaking)
        assertFalse(controller.isTtsSpeaking.value)
        assertNull(controller.currentlyPlayingMessageId.value)
    }

    @Test
    fun testSwitchingMessagesUpdatesActiveMessageId() = runBlocking {
        val engine = FakeOfflineTtsEngine()
        engine.setVoiceAvailable(Language.ENGLISH, TtsStatus.SYSTEM_TTS_OFFLINE)
        val controller = PlaybackSessionController(engine)

        // Start playing message 1
        controller.togglePlayMessage(401L, "First message", Language.ENGLISH)
        assertEquals(401L, controller.currentlyPlayingMessageId.value)
        assertEquals("First message", engine.lastSpokenText)

        // Switch to playing message 2
        controller.togglePlayMessage(402L, "Second message", Language.ENGLISH)
        assertEquals(402L, controller.currentlyPlayingMessageId.value)
        assertEquals("Second message", engine.lastSpokenText)
        assertTrue(engine.isSpeaking)
        assertTrue(controller.isTtsSpeaking.value)
    }

    @Test
    fun testOfflineVoiceUnavailabilitySurfaced() = runBlocking {
        val engine = FakeOfflineTtsEngine()
        engine.setVoiceAvailable(Language.TELUGU, TtsStatus.NOT_INSTALLED)
        val controller = PlaybackSessionController(engine)

        controller.togglePlayMessage(501L, "నమస్కారం", Language.TELUGU)

        assertFalse(engine.isSpeaking)
        assertFalse(controller.isTtsSpeaking.value)
        assertNull(controller.currentlyPlayingMessageId.value)
        assertTrue(controller.ttsStatusMessage.value?.contains("No offline voice") == true)
    }

    @Test
    fun testSosEmergencyAnnouncementBypassesAutoReadOff() = runBlocking {
        val engine = FakeOfflineTtsEngine()
        engine.setVoiceAvailable(Language.ENGLISH, TtsStatus.SYSTEM_TTS_OFFLINE)
        val controller = PlaybackSessionController(engine)

        // Auto-Read is explicitly OFF
        controller.toggleAutoTts(false)

        val sosPacket = Packet(
            msgId = 999L,
            senderId = "PEER-SOS",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "[EMERGENCY SOS] Immediate evacuation needed!",
            timestamp = System.currentTimeMillis(),
            type = PacketType.SOS
        )

        controller.handleIncomingPacket(sosPacket)

        // Emergency SOS must ALWAYS be announced regardless of Auto-Read switch
        assertTrue(engine.isSpeaking)
        assertTrue(engine.lastWasEmergency)
        assertEquals("[EMERGENCY SOS] Immediate evacuation needed!", engine.lastSpokenText)
    }

    @Test
    fun testLocationPacketNeverTriggersTts() = runBlocking {
        val engine = FakeOfflineTtsEngine()
        engine.setVoiceAvailable(Language.ENGLISH, TtsStatus.SYSTEM_TTS_OFFLINE)
        val controller = PlaybackSessionController(engine)

        val locPacket = Packet(
            msgId = 888L,
            senderId = "PEER-GPS",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Lat: 28.56720, Lon: 77.21000",
            timestamp = System.currentTimeMillis(),
            type = PacketType.LOCATION
        )

        controller.handleIncomingPacket(locPacket)

        assertFalse(engine.isSpeaking)
        assertNull(engine.lastSpokenText)
    }
}

/**
 * Playback session controller simulating MainViewModel TTS control state
 */
private class PlaybackSessionController(val ttsEngine: FakeOfflineTtsEngine) {
    val isAutoTtsEnabled = MutableStateFlow(true)
    val isTtsSpeaking = MutableStateFlow(false)
    val currentlyPlayingMessageId = MutableStateFlow<Long?>(null)
    val ttsStatusMessage = MutableStateFlow<String?>("Offline TTS Ready")

    fun toggleAutoTts(enabled: Boolean? = null) {
        val next = enabled ?: !isAutoTtsEnabled.value
        isAutoTtsEnabled.value = next
        if (!next) {
            stopPlayback()
        }
    }

    suspend fun togglePlayMessage(msgId: Long, text: String, lang: Language) {
        if (currentlyPlayingMessageId.value == msgId) {
            stopPlayback()
            return
        }
        stopPlayback()

        currentlyPlayingMessageId.value = msgId
        isTtsSpeaking.value = true
        val status = ttsEngine.isLanguageAvailable(lang)
        if (status != TtsStatus.SYSTEM_TTS_OFFLINE && status != TtsStatus.OFFLINE_READY) {
            ttsStatusMessage.value = "No offline voice for ${lang.displayName} ($status). Cloud fallback disabled."
            currentlyPlayingMessageId.value = null
            isTtsSpeaking.value = false
            return
        }
        ttsStatusMessage.value = "Reading message aloud..."
        val res = ttsEngine.speak(text, lang, isEmergency = false)
        if (res.isFailure) {
            ttsStatusMessage.value = res.exceptionOrNull()?.message ?: "TTS playback error"
            currentlyPlayingMessageId.value = null
            isTtsSpeaking.value = false
        }
    }

    fun stopPlayback() {
        ttsEngine.stop()
        currentlyPlayingMessageId.value = null
        isTtsSpeaking.value = false
    }

    suspend fun handleIncomingPacket(packet: Packet) {
        if (packet.type == PacketType.LOCATION) {
            return
        } else if (packet.priority == PacketPriority.SOS || packet.type == PacketType.SOS) {
            ttsEngine.speak(packet.text, packet.lang, isEmergency = true)
        } else if (isAutoTtsEnabled.value) {
            stopPlayback()
            currentlyPlayingMessageId.value = packet.msgId
            isTtsSpeaking.value = true
            ttsEngine.speak(packet.text, packet.lang, isEmergency = false)
        }
    }
}

/**
 * Fake offline TTS engine simulating Android TextToSpeech offline-only contract
 */
private class FakeOfflineTtsEngine : TtsEngine {
    private val availabilityMap = mutableMapOf<Language, TtsStatus>()
    var lastSpokenText: String? = null
    var lastSpokenLang: Language? = null
    var lastWasEmergency: Boolean = false
    var isSpeaking: Boolean = false

    fun setVoiceAvailable(lang: Language, status: TtsStatus) {
        availabilityMap[lang] = status
    }

    override fun isLanguageAvailable(lang: Language): TtsStatus {
        return availabilityMap[lang] ?: TtsStatus.NOT_INSTALLED
    }

    override suspend fun speak(text: String, lang: Language, isEmergency: Boolean): Result<Unit> {
        if (text.isBlank()) return Result.success(Unit)
        val status = isLanguageAvailable(lang)
        if (status != TtsStatus.SYSTEM_TTS_OFFLINE && status != TtsStatus.OFFLINE_READY) {
            return Result.failure(IllegalStateException("No verified offline TTS voice found for ${lang.displayName} ($status). Online cloud TTS is disabled."))
        }
        lastSpokenText = text
        lastSpokenLang = lang
        lastWasEmergency = isEmergency
        isSpeaking = true
        return Result.success(Unit)
    }

    override fun stop() {
        isSpeaking = false
    }

    override fun close() {
        stop()
    }
}
