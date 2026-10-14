package org.coresense.itantra.dial

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.coresense.itantra.protocol.*
import org.coresense.itantra.stt.EnglishSpeechNormalizer
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Step 13: Dial / Speech-over-Packet Telephony.
 * Tests target peer validation, call state lifecycle, mute mechanics,
 * speech packet creation and targeted routing, receiver transcript filtering,
 * and transport disconnect handling.
 */
class DialCallTest {

    // Simulates call state container matching MainViewModel's architecture
    private class MockCallSession(val myDeviceId: String = "MY-DEVICE") {
        val isCallActive = MutableStateFlow(false)
        val activeCallPeerId = MutableStateFlow<String?>(null)
        val isCallMuted = MutableStateFlow(false)
        val callDurationSeconds = MutableStateFlow(0)
        val callStatusMessage = MutableStateFlow("Idle")
        val callTranscripts = MutableStateFlow<List<Pair<String, String>>>(emptyList())

        var micStarted = false
        var vadReset = false
        val sentPackets = mutableListOf<Packet>()

        fun startCall(targetPeerId: String, isConnected: Boolean = true, hasMicPermission: Boolean = true): Boolean {
            val trimmed = targetPeerId.trim()
            if (trimmed.isBlank() || trimmed.equals("GLOBAL", ignoreCase = true) || trimmed.equals("ALL", ignoreCase = true)) {
                callStatusMessage.value = "Please select a specific peer device to call"
                return false
            }

            if (isCallActive.value) return false

            if (!hasMicPermission) {
                callStatusMessage.value = "Microphone permission required for call"
                return false
            }

            if (!isConnected) {
                callStatusMessage.value = "No active peer connection. Connect over Bluetooth or Wi-Fi first."
                return false
            }

            isCallActive.value = true
            activeCallPeerId.value = trimmed
            isCallMuted.value = false
            callDurationSeconds.value = 0
            callStatusMessage.value = "Connected with $trimmed"
            callTranscripts.value = emptyList()

            micStarted = true
            vadReset = true
            return true
        }

        fun toggleCallMute() {
            val next = !isCallMuted.value
            isCallMuted.value = next
            callStatusMessage.value = if (next) "Microphone Muted" else "Connected with ${activeCallPeerId.value}"
        }

        fun endCall() {
            isCallActive.value = false
            activeCallPeerId.value = null
            isCallMuted.value = false
            micStarted = false
            vadReset = true
            callStatusMessage.value = "Call ended"
        }

        fun onTransportDisconnected() {
            if (isCallActive.value) {
                endCall()
                callStatusMessage.value = "Call disconnected: peer connection lost"
            }
        }

        fun processOutgoingSpeech(rawText: String, lang: Language = Language.ENGLISH) {
            if (!isCallActive.value || isCallMuted.value) return

            val target = activeCallPeerId.value ?: return
            val normalized = if (lang == Language.ENGLISH) EnglishSpeechNormalizer.normalize(rawText) else rawText.trim()

            if (normalized.isNotBlank()) {
                val list = callTranscripts.value.toMutableList()
                list.add(Pair("Me", normalized))
                callTranscripts.value = list

                val packet = Packet(
                    msgId = System.currentTimeMillis(),
                    senderId = myDeviceId,
                    seq = (sentPackets.size + 1),
                    lang = lang,
                    priority = PacketPriority.NORMAL,
                    text = normalized,
                    receiverId = target,
                    timestamp = System.currentTimeMillis(),
                    type = PacketType.DATA
                )
                sentPackets.add(packet)
            }
        }

        fun handleIncomingPacket(packet: Packet) {
            if (isCallActive.value && activeCallPeerId.value?.equals(packet.senderId, ignoreCase = true) == true && packet.type != PacketType.LOCATION) {
                val list = callTranscripts.value.toMutableList()
                list.add(Pair(packet.senderId, packet.text))
                callTranscripts.value = list
            }
        }
    }

    @Test
    fun testTargetPeerValidation() {
        val session = MockCallSession()

        // 1. Blank or whitespace rejected
        assertFalse(session.startCall(""))
        assertFalse(session.startCall("   "))
        assertEquals("Please select a specific peer device to call", session.callStatusMessage.value)
        assertFalse(session.isCallActive.value)

        // 2. Broadcast targets (GLOBAL, ALL) rejected
        assertFalse(session.startCall("GLOBAL"))
        assertFalse(session.startCall("global"))
        assertFalse(session.startCall("ALL"))
        assertFalse(session.startCall("all"))
        assertFalse(session.isCallActive.value)

        // 3. Specific peer ID accepted
        val success = session.startCall("PEER-ALPHA-01")
        assertTrue(success)
        assertTrue(session.isCallActive.value)
        assertEquals("PEER-ALPHA-01", session.activeCallPeerId.value)
        assertEquals("Connected with PEER-ALPHA-01", session.callStatusMessage.value)
    }

    @Test
    fun testCallRequiresConnectionAndMicPermission() {
        val session = MockCallSession()

        // Missing permission
        assertFalse(session.startCall("PEER-BETA", isConnected = true, hasMicPermission = false))
        assertEquals("Microphone permission required for call", session.callStatusMessage.value)
        assertFalse(session.isCallActive.value)

        // Disconnected
        assertFalse(session.startCall("PEER-BETA", isConnected = false, hasMicPermission = true))
        assertEquals("No active peer connection. Connect over Bluetooth or Wi-Fi first.", session.callStatusMessage.value)
        assertFalse(session.isCallActive.value)

        // Both satisfied
        assertTrue(session.startCall("PEER-BETA", isConnected = true, hasMicPermission = true))
        assertTrue(session.isCallActive.value)
    }

    @Test
    fun testCallMuteBehavior() {
        val session = MockCallSession()
        session.startCall("PEER-CHARLIE")
        assertFalse(session.isCallMuted.value)

        // Mute
        session.toggleCallMute()
        assertTrue(session.isCallMuted.value)
        assertEquals("Microphone Muted", session.callStatusMessage.value)

        // While muted, speech should NOT be transcribed or transmitted
        session.processOutgoingSpeech("Hello are you there")
        assertEquals(0, session.sentPackets.size)
        assertTrue(session.callTranscripts.value.isEmpty())

        // Unmute
        session.toggleCallMute()
        assertFalse(session.isCallMuted.value)
        assertEquals("Connected with PEER-CHARLIE", session.callStatusMessage.value)

        // Unmuted speech transmits normally
        session.processOutgoingSpeech("can you hear me now")
        assertEquals(1, session.sentPackets.size)
        assertEquals("can you hear me now", session.sentPackets[0].text)
        assertEquals("PEER-CHARLIE", session.sentPackets[0].receiverId)
        assertEquals(1, session.callTranscripts.value.size)
    }

    @Test
    fun testCallEndCleansUpState() {
        val session = MockCallSession()
        session.startCall("PEER-DELTA")
        assertTrue(session.isCallActive.value)
        assertTrue(session.micStarted)

        session.endCall()
        assertFalse(session.isCallActive.value)
        assertNull(session.activeCallPeerId.value)
        assertFalse(session.isCallMuted.value)
        assertFalse(session.micStarted)
        assertTrue(session.vadReset)
        assertEquals("Call ended", session.callStatusMessage.value)
    }

    @Test
    fun testTransportDisconnectEndsActiveCall() {
        val session = MockCallSession()
        session.startCall("PEER-ECHO")
        assertTrue(session.isCallActive.value)

        // Sudden transport drop
        session.onTransportDisconnected()
        assertFalse(session.isCallActive.value)
        assertNull(session.activeCallPeerId.value)
        assertEquals("Call disconnected: peer connection lost", session.callStatusMessage.value)
    }

    @Test
    fun testOutgoingSpeechPacketTargetingAndNormalization() {
        val session = MockCallSession()
        session.startCall("PEER-ZULU")

        // Raw phonetic speech should be normalized to standard English words
        session.processOutgoingSpeech("plees ned halp at sektur four", Language.ENGLISH)

        assertEquals(1, session.sentPackets.size)
        val packet = session.sentPackets[0]
        assertEquals("PEER-ZULU", packet.receiverId)
        assertNotEquals("GLOBAL", packet.receiverId)
        assertEquals(PacketPriority.NORMAL, packet.priority)
        assertEquals("please need help at sector four", packet.text)

        // Verify transcript entry
        val transcripts = session.callTranscripts.value
        assertEquals(1, transcripts.size)
        assertEquals("Me", transcripts[0].first)
        assertEquals("please need help at sector four", transcripts[0].second)
    }

    @Test
    fun testIncomingSpeechTranscriptFiltering() {
        val session = MockCallSession()
        session.startCall("PEER-CALLER-01")

        // 1. Packet from active call peer -> Appended to call transcript
        val packetFromCallPeer = Packet(
            msgId = 2001L,
            senderId = "PEER-CALLER-01",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Copy that sector 4 acknowledged",
            receiverId = "MY-DEVICE",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )
        session.handleIncomingPacket(packetFromCallPeer)

        assertEquals(1, session.callTranscripts.value.size)
        assertEquals("PEER-CALLER-01", session.callTranscripts.value[0].first)
        assertEquals("Copy that sector 4 acknowledged", session.callTranscripts.value[0].second)

        // 2. Packet from different peer -> NOT appended to this call transcript
        val packetFromOtherPeer = Packet(
            msgId = 2002L,
            senderId = "OTHER-DEVICE-99",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Unrelated message from someone else",
            receiverId = "MY-DEVICE",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )
        session.handleIncomingPacket(packetFromOtherPeer)
        assertEquals(1, session.callTranscripts.value.size)

        // 3. Location beacon from call peer -> NOT appended to call transcript
        val locationBeacon = Packet(
            msgId = 2003L,
            senderId = "PEER-CALLER-01",
            seq = 2,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Lat: 28.56720, Lon: 77.21000",
            receiverId = "MY-DEVICE",
            timestamp = System.currentTimeMillis(),
            type = PacketType.LOCATION
        )
        session.handleIncomingPacket(locationBeacon)
        assertEquals(1, session.callTranscripts.value.size)
    }

    @Test
    fun testTwoPhoneCallConversationFlow() {
        val phoneA = MockCallSession("PHONE-A")
        val phoneB = MockCallSession("PHONE-B")

        // Phone A calls Phone B
        phoneA.startCall("PHONE-B")
        phoneB.startCall("PHONE-A")

        // 1. Phone A speaks: "checking communication link"
        phoneA.processOutgoingSpeech("checking communication link")
        val aPacket = phoneA.sentPackets.last()
        assertEquals("PHONE-B", aPacket.receiverId)
        assertEquals("PHONE-A", aPacket.senderId)

        // 2. Phone B receives A's packet
        phoneB.handleIncomingPacket(aPacket)

        // 3. Phone B speaks: "loud and clear over"
        phoneB.processOutgoingSpeech("loud and clear over")
        val bPacket = phoneB.sentPackets.last()
        assertEquals("PHONE-A", bPacket.receiverId)
        assertEquals("PHONE-B", bPacket.senderId)

        // 4. Phone A receives B's packet
        phoneA.handleIncomingPacket(bPacket)

        // Verify transcripts on Phone A
        assertEquals(2, phoneA.callTranscripts.value.size)
        assertEquals("Me", phoneA.callTranscripts.value[0].first)
        assertEquals("checking communication link", phoneA.callTranscripts.value[0].second)
        assertEquals("PHONE-B", phoneA.callTranscripts.value[1].first)
        assertEquals("loud and clear over", phoneA.callTranscripts.value[1].second)

        // Verify transcripts on Phone B
        assertEquals(2, phoneB.callTranscripts.value.size)
        assertEquals("PHONE-A", phoneB.callTranscripts.value[0].first)
        assertEquals("checking communication link", phoneB.callTranscripts.value[0].second)
        assertEquals("Me", phoneB.callTranscripts.value[1].first)
        assertEquals("loud and clear over", phoneB.callTranscripts.value[1].second)

        // End call on Phone A
        phoneA.endCall()
        assertFalse(phoneA.isCallActive.value)
    }
}
