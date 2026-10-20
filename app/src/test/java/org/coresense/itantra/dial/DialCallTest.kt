package org.coresense.itantra.dial

import kotlinx.coroutines.flow.MutableStateFlow
import org.coresense.itantra.core.protocol.*
import org.coresense.itantra.backend.speech.EnglishSpeechNormalizer
import org.coresense.itantra.frontend.viewmodel.CallSessionState
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for Step 13: Dial / Hands-Free Speech-over-Packet Telephony.
 * Tests:
 * 1. Target peer validation and connection/permission requirements
 * 2. Complete Call Signaling Handshake (CALL_INVITE, CALL_ACCEPT, CALL_REJECT, CALL_BUSY, CALL_END)
 * 3. Bidirectional Speech-over-Packet audio transcripts with speaker attribution
 * 4. Transcript deduplication and sequence tracking
 * 5. Strict session isolation (callId, recipient filtering)
 * 6. Zero-leakage: Call packets (CALL_SIGNAL, CALL_TRANSCRIPT) never enter message feeds or DB
 * 7. Call mute mechanics and transport disconnect handling
 */
class DialCallTest {

    private class MockCallSession(val myDeviceId: String = "MY-DEVICE") {
        val callSessionState = MutableStateFlow(CallSessionState.IDLE)
        val isCallActive = MutableStateFlow(false)
        val activeCallPeerId = MutableStateFlow<String?>(null)
        val activeCallId = MutableStateFlow<String?>(null)
        val incomingCallFrom = MutableStateFlow<String?>(null)
        val incomingCallId = MutableStateFlow<String?>(null)
        val isCallMuted = MutableStateFlow(false)
        val callDurationSeconds = MutableStateFlow(0)
        val callStatusMessage = MutableStateFlow("Idle")
        val callTranscripts = MutableStateFlow<List<Pair<String, String>>>(emptyList())

        private val receivedCallTranscriptKeys = mutableSetOf<String>()
        var callSeqCounter = 0
        var audioFocusAcquired = false
        var audioRecorderRunning = false

        val sentPackets = mutableListOf<Packet>()
        val roomDbInsertedMessages = mutableListOf<Packet>()

        fun startCall(targetPeerId: String, isConnected: Boolean = true, hasMicPermission: Boolean = true): Boolean {
            val trimmed = targetPeerId.trim()
            if (trimmed.isBlank() || trimmed.equals("GLOBAL", ignoreCase = true) || trimmed.equals("ALL", ignoreCase = true)) {
                callStatusMessage.value = "Please select a specific peer device to call"
                return false
            }

            if (callSessionState.value != CallSessionState.IDLE) return false

            if (!hasMicPermission) {
                callStatusMessage.value = "Microphone permission required for call"
                return false
            }

            if (!isConnected) {
                callStatusMessage.value = "No active peer connection. Connect over Bluetooth or Wi-Fi first."
                return false
            }

            val callId = "call_" + System.currentTimeMillis().toString(36)
            activeCallId.value = callId
            activeCallPeerId.value = trimmed
            callSessionState.value = CallSessionState.OUTGOING_CALL
            isCallActive.value = false
            isCallMuted.value = false
            callDurationSeconds.value = 0
            callStatusMessage.value = "Calling $trimmed..."
            callTranscripts.value = emptyList()
            receivedCallTranscriptKeys.clear()
            callSeqCounter = 0

            val invitePacket = Packet(
                msgId = System.currentTimeMillis() and 0x7FFFFFFF,
                senderId = myDeviceId,
                receiverId = trimmed,
                conversationId = callId,
                seq = ++callSeqCounter,
                lang = Language.ENGLISH,
                priority = PacketPriority.NORMAL,
                text = "CALL_INVITE:$callId:$myDeviceId",
                type = PacketType.CALL_SIGNAL,
                timestamp = System.currentTimeMillis()
            )
            sentPackets.add(invitePacket)
            return true
        }

        fun acceptIncomingCall() {
            if (callSessionState.value != CallSessionState.INCOMING_CALL) return
            val callId = incomingCallId.value ?: return
            val caller = incomingCallFrom.value ?: return

            activeCallId.value = callId
            activeCallPeerId.value = caller
            incomingCallId.value = null
            incomingCallFrom.value = null

            val acceptPacket = Packet(
                msgId = System.currentTimeMillis() and 0x7FFFFFFF,
                senderId = myDeviceId,
                receiverId = caller,
                conversationId = callId,
                seq = ++callSeqCounter,
                lang = Language.ENGLISH,
                priority = PacketPriority.NORMAL,
                text = "CALL_ACCEPT:$callId",
                type = PacketType.CALL_SIGNAL,
                timestamp = System.currentTimeMillis()
            )
            sentPackets.add(acceptPacket)
            startActiveCallSession(caller, callId)
        }

        fun rejectIncomingCall() {
            if (callSessionState.value != CallSessionState.INCOMING_CALL) return
            val callId = incomingCallId.value
            val caller = incomingCallFrom.value

            if (callId != null && caller != null) {
                val rejectPacket = Packet(
                    msgId = System.currentTimeMillis() and 0x7FFFFFFF,
                    senderId = myDeviceId,
                    receiverId = caller,
                    conversationId = callId,
                    seq = ++callSeqCounter,
                    lang = Language.ENGLISH,
                    priority = PacketPriority.NORMAL,
                    text = "CALL_REJECT:$callId",
                    type = PacketType.CALL_SIGNAL,
                    timestamp = System.currentTimeMillis()
                )
                sentPackets.add(rejectPacket)
            }

            incomingCallId.value = null
            incomingCallFrom.value = null
            callSessionState.value = CallSessionState.IDLE
            callStatusMessage.value = "Call declined"
        }

        fun startActiveCallSession(peerId: String, callId: String) {
            callSessionState.value = CallSessionState.CONNECTED
            isCallActive.value = true
            activeCallPeerId.value = peerId
            activeCallId.value = callId
            isCallMuted.value = false
            callDurationSeconds.value = 0
            callStatusMessage.value = "Connected with $peerId"
            audioFocusAcquired = true
            audioRecorderRunning = true
        }

        fun toggleCallMute() {
            val next = !isCallMuted.value
            isCallMuted.value = next
            callStatusMessage.value = if (next) "Microphone Muted" else "Connected with ${activeCallPeerId.value}"
        }

        fun endCall() {
            val target = activeCallPeerId.value
            val callId = activeCallId.value

            if (target != null && callId != null) {
                val endPacket = Packet(
                    msgId = System.currentTimeMillis() and 0x7FFFFFFF,
                    senderId = myDeviceId,
                    receiverId = target,
                    conversationId = callId,
                    seq = ++callSeqCounter,
                    lang = Language.ENGLISH,
                    priority = PacketPriority.NORMAL,
                    text = "CALL_END:$callId",
                    type = PacketType.CALL_SIGNAL,
                    timestamp = System.currentTimeMillis()
                )
                sentPackets.add(endPacket)
            } else if (callSessionState.value == CallSessionState.INCOMING_CALL) {
                rejectIncomingCall()
                return
            }

            terminateCallLocally("Call ended")
        }

        fun terminateCallLocally(statusMsg: String) {
            callSessionState.value = CallSessionState.IDLE
            isCallActive.value = false
            activeCallPeerId.value = null
            activeCallId.value = null
            incomingCallFrom.value = null
            incomingCallId.value = null
            isCallMuted.value = false
            audioFocusAcquired = false
            audioRecorderRunning = false
            callStatusMessage.value = statusMsg
        }

        fun processOutgoingSpeech(rawText: String, lang: Language = Language.ENGLISH) {
            if (callSessionState.value != CallSessionState.CONNECTED || isCallMuted.value) return

            val target = activeCallPeerId.value ?: return
            val callId = activeCallId.value ?: return
            val normalized = if (lang == Language.ENGLISH) EnglishSpeechNormalizer.normalize(rawText) else rawText.trim()

            if (normalized.isNotBlank()) {
                val currentSeq = ++callSeqCounter
                val list = callTranscripts.value.toMutableList()
                list.add(Pair("Me", normalized))
                callTranscripts.value = list

                val packet = Packet(
                    msgId = System.currentTimeMillis() and 0x7FFFFFFF,
                    senderId = myDeviceId,
                    receiverId = target,
                    conversationId = callId,
                    seq = currentSeq,
                    lang = lang,
                    priority = PacketPriority.NORMAL,
                    text = normalized,
                    type = PacketType.CALL_TRANSCRIPT,
                    timestamp = System.currentTimeMillis()
                )
                sentPackets.add(packet)
            }
        }

        fun handleIncomingPacket(packet: Packet) {
            val isDirectForMe = packet.receiverId?.equals(myDeviceId, ignoreCase = true) == true

            // Dedicated Call Signaling
            if (packet.type == PacketType.CALL_SIGNAL) {
                if (!isDirectForMe) return
                val text = packet.text.trim()
                when {
                    text.startsWith("CALL_INVITE:") -> {
                        val parts = text.split(":")
                        val callId = if (parts.size >= 2) parts[1] else packet.conversationId ?: ""
                        val callerId = if (parts.size >= 3) parts[2] else packet.senderId

                        if (callSessionState.value == CallSessionState.CONNECTED || callSessionState.value == CallSessionState.OUTGOING_CALL) {
                            val busyPacket = Packet(
                                msgId = System.currentTimeMillis() and 0x7FFFFFFF,
                                senderId = myDeviceId,
                                receiverId = callerId,
                                conversationId = callId,
                                seq = ++callSeqCounter,
                                lang = Language.ENGLISH,
                                priority = PacketPriority.NORMAL,
                                text = "CALL_BUSY:$callId",
                                type = PacketType.CALL_SIGNAL,
                                timestamp = System.currentTimeMillis()
                            )
                            sentPackets.add(busyPacket)
                        } else {
                            incomingCallId.value = callId
                            incomingCallFrom.value = callerId
                            callSessionState.value = CallSessionState.INCOMING_CALL
                            callStatusMessage.value = "Incoming call from $callerId"
                        }
                    }
                    text.startsWith("CALL_ACCEPT:") -> {
                        val parts = text.split(":")
                        val callId = if (parts.size >= 2) parts[1] else packet.conversationId
                        if (callSessionState.value == CallSessionState.OUTGOING_CALL && (activeCallId.value == callId || packet.senderId.equals(activeCallPeerId.value, ignoreCase = true))) {
                            startActiveCallSession(packet.senderId, callId ?: activeCallId.value ?: "")
                        }
                    }
                    text.startsWith("CALL_REJECT:") -> {
                        val parts = text.split(":")
                        val callId = if (parts.size >= 2) parts[1] else packet.conversationId
                        if (callSessionState.value == CallSessionState.OUTGOING_CALL && (activeCallId.value == callId || packet.senderId.equals(activeCallPeerId.value, ignoreCase = true))) {
                            terminateCallLocally("${packet.senderId} declined the call")
                        }
                    }
                    text.startsWith("CALL_BUSY:") -> {
                        val parts = text.split(":")
                        val callId = if (parts.size >= 2) parts[1] else packet.conversationId
                        if (callSessionState.value == CallSessionState.OUTGOING_CALL && (activeCallId.value == callId || packet.senderId.equals(activeCallPeerId.value, ignoreCase = true))) {
                            terminateCallLocally("${packet.senderId} is busy on another call")
                        }
                    }
                    text.startsWith("CALL_END:") -> {
                        val parts = text.split(":")
                        val callId = if (parts.size >= 2) parts[1] else packet.conversationId
                        if ((callSessionState.value == CallSessionState.CONNECTED && activeCallId.value == callId) ||
                            (callSessionState.value == CallSessionState.INCOMING_CALL && incomingCallId.value == callId)) {
                            terminateCallLocally("Call ended by ${packet.senderId}")
                        }
                    }
                }
                return
            }

            // Dedicated Call Transcript
            if (packet.type == PacketType.CALL_TRANSCRIPT) {
                val isCallMatch = isDirectForMe &&
                        callSessionState.value == CallSessionState.CONNECTED &&
                        packet.conversationId == activeCallId.value &&
                        packet.senderId.equals(activeCallPeerId.value, ignoreCase = true)

                if (isCallMatch && packet.text.isNotBlank()) {
                    val dedupKey = "${packet.conversationId}_${packet.senderId}_${packet.seq}_${packet.text.trim()}"
                    if (!receivedCallTranscriptKeys.contains(dedupKey)) {
                        receivedCallTranscriptKeys.add(dedupKey)
                        val currentList = callTranscripts.value.toMutableList()
                        currentList.add(Pair(packet.senderId, packet.text.trim()))
                        callTranscripts.value = currentList
                    }
                }
                return
            }

            // Standard message persistence (only if NOT LOCATION, NOT CALL_SIGNAL, NOT CALL_TRANSCRIPT)
            if (packet.type != PacketType.LOCATION && packet.type != PacketType.CALL_SIGNAL && packet.type != PacketType.CALL_TRANSCRIPT) {
                roomDbInsertedMessages.add(packet)
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
        assertEquals(CallSessionState.IDLE, session.callSessionState.value)

        // 2. Broadcast targets (GLOBAL, ALL) rejected
        assertFalse(session.startCall("GLOBAL"))
        assertFalse(session.startCall("global"))
        assertFalse(session.startCall("ALL"))
        assertFalse(session.startCall("all"))
        assertEquals(CallSessionState.IDLE, session.callSessionState.value)

        // 3. Specific peer ID accepted and initiates OUTGOING_CALL signaling
        val success = session.startCall("PEER-ALPHA-01")
        assertTrue(success)
        assertEquals(CallSessionState.OUTGOING_CALL, session.callSessionState.value)
        assertFalse(session.isCallActive.value) // Not connected yet!
        assertEquals("PEER-ALPHA-01", session.activeCallPeerId.value)
        assertNotNull(session.activeCallId.value)
        assertEquals("Calling PEER-ALPHA-01...", session.callStatusMessage.value)

        // Verify CALL_INVITE signal packet was sent
        assertEquals(1, session.sentPackets.size)
        val invite = session.sentPackets[0]
        assertEquals(PacketType.CALL_SIGNAL, invite.type)
        assertEquals("PEER-ALPHA-01", invite.receiverId)
        assertTrue(invite.text.startsWith("CALL_INVITE:${session.activeCallId.value}:MY-DEVICE"))
    }

    @Test
    fun testCallRequiresConnectionAndMicPermission() {
        val session = MockCallSession()

        // Missing permission
        assertFalse(session.startCall("PEER-BETA", isConnected = true, hasMicPermission = false))
        assertEquals("Microphone permission required for call", session.callStatusMessage.value)
        assertEquals(CallSessionState.IDLE, session.callSessionState.value)

        // Disconnected
        assertFalse(session.startCall("PEER-BETA", isConnected = false, hasMicPermission = true))
        assertEquals("No active peer connection. Connect over Bluetooth or Wi-Fi first.", session.callStatusMessage.value)
        assertEquals(CallSessionState.IDLE, session.callSessionState.value)

        // Both satisfied -> OUTGOING_CALL
        assertTrue(session.startCall("PEER-BETA", isConnected = true, hasMicPermission = true))
        assertEquals(CallSessionState.OUTGOING_CALL, session.callSessionState.value)
    }

    @Test
    fun testCallSignalingHandshakeAccept() {
        val phoneA = MockCallSession("PHONE-A")
        val phoneB = MockCallSession("PHONE-B")

        // 1. Phone A calls Phone B
        assertTrue(phoneA.startCall("PHONE-B"))
        assertEquals(CallSessionState.OUTGOING_CALL, phoneA.callSessionState.value)
        assertFalse(phoneA.isCallActive.value)
        val callId = phoneA.activeCallId.value!!

        val invitePacket = phoneA.sentPackets.last()
        assertEquals(PacketType.CALL_SIGNAL, invitePacket.type)

        // 2. Phone B receives CALL_INVITE
        phoneB.handleIncomingPacket(invitePacket)
        assertEquals(CallSessionState.INCOMING_CALL, phoneB.callSessionState.value)
        assertEquals("PHONE-A", phoneB.incomingCallFrom.value)
        assertEquals(callId, phoneB.incomingCallId.value)
        assertFalse(phoneB.isCallActive.value)

        // 3. Phone B clicks ACCEPT
        phoneB.acceptIncomingCall()
        assertEquals(CallSessionState.CONNECTED, phoneB.callSessionState.value)
        assertTrue(phoneB.isCallActive.value)
        assertTrue(phoneB.audioRecorderRunning)
        assertTrue(phoneB.audioFocusAcquired)
        assertEquals(callId, phoneB.activeCallId.value)
        assertEquals("PHONE-A", phoneB.activeCallPeerId.value)

        val acceptPacket = phoneB.sentPackets.last()
        assertEquals(PacketType.CALL_SIGNAL, acceptPacket.type)
        assertEquals("CALL_ACCEPT:$callId", acceptPacket.text)

        // 4. Phone A receives CALL_ACCEPT
        phoneA.handleIncomingPacket(acceptPacket)
        assertEquals(CallSessionState.CONNECTED, phoneA.callSessionState.value)
        assertTrue(phoneA.isCallActive.value)
        assertTrue(phoneA.audioRecorderRunning)
        assertTrue(phoneA.audioFocusAcquired)
        assertEquals(callId, phoneA.activeCallId.value)
        assertEquals("PHONE-B", phoneA.activeCallPeerId.value)
    }

    @Test
    fun testCallSignalingReject() {
        val phoneA = MockCallSession("PHONE-A")
        val phoneB = MockCallSession("PHONE-B")

        // Phone A calls Phone B
        phoneA.startCall("PHONE-B")
        val invitePacket = phoneA.sentPackets.last()

        // Phone B receives and rejects
        phoneB.handleIncomingPacket(invitePacket)
        assertEquals(CallSessionState.INCOMING_CALL, phoneB.callSessionState.value)
        phoneB.rejectIncomingCall()
        assertEquals(CallSessionState.IDLE, phoneB.callSessionState.value)

        val rejectPacket = phoneB.sentPackets.last()
        assertEquals(PacketType.CALL_SIGNAL, rejectPacket.type)
        assertTrue(rejectPacket.text.startsWith("CALL_REJECT:"))

        // Phone A receives CALL_REJECT
        phoneA.handleIncomingPacket(rejectPacket)
        assertEquals(CallSessionState.IDLE, phoneA.callSessionState.value)
        assertFalse(phoneA.isCallActive.value)
        assertEquals("PHONE-B declined the call", phoneA.callStatusMessage.value)
    }

    @Test
    fun testCallSignalingBusy() {
        val phoneA = MockCallSession("PHONE-A")
        val phoneB = MockCallSession("PHONE-B")

        // Phone B is already on a call with Phone C
        phoneB.startActiveCallSession("PHONE-C", "call_existing")
        assertEquals(CallSessionState.CONNECTED, phoneB.callSessionState.value)

        // Phone A calls Phone B
        phoneA.startCall("PHONE-B")
        val invitePacket = phoneA.sentPackets.last()

        // Phone B receives invite while connected -> sends CALL_BUSY
        phoneB.handleIncomingPacket(invitePacket)
        val busyPacket = phoneB.sentPackets.last()
        assertEquals(PacketType.CALL_SIGNAL, busyPacket.type)
        assertTrue(busyPacket.text.startsWith("CALL_BUSY:"))

        // Phone A receives CALL_BUSY
        phoneA.handleIncomingPacket(busyPacket)
        assertEquals(CallSessionState.IDLE, phoneA.callSessionState.value)
        assertEquals("PHONE-B is busy on another call", phoneA.callStatusMessage.value)
    }

    @Test
    fun testCallTerminationSignaling() {
        val phoneA = MockCallSession("PHONE-A")
        val phoneB = MockCallSession("PHONE-B")

        // Establish call between A and B
        phoneA.startActiveCallSession("PHONE-B", "call_123")
        phoneB.startActiveCallSession("PHONE-A", "call_123")

        // Phone A ends call
        phoneA.endCall()
        assertEquals(CallSessionState.IDLE, phoneA.callSessionState.value)
        assertFalse(phoneA.audioRecorderRunning)
        assertFalse(phoneA.audioFocusAcquired)

        val endPacket = phoneA.sentPackets.last()
        assertEquals(PacketType.CALL_SIGNAL, endPacket.type)
        assertEquals("CALL_END:call_123", endPacket.text)

        // Phone B receives CALL_END
        phoneB.handleIncomingPacket(endPacket)
        assertEquals(CallSessionState.IDLE, phoneB.callSessionState.value)
        assertFalse(phoneB.audioRecorderRunning)
        assertFalse(phoneB.audioFocusAcquired)
        assertEquals("Call ended by PHONE-A", phoneB.callStatusMessage.value)
    }

    @Test
    fun testTwoWayAudioTranscriptsWithSpeakerAttribution() {
        val phoneA = MockCallSession("PHONE-A")
        val phoneB = MockCallSession("PHONE-B")
        val sharedCallId = "call_sync_99"

        phoneA.startActiveCallSession("PHONE-B", sharedCallId)
        phoneB.startActiveCallSession("PHONE-A", sharedCallId)

        // 1. Phone A speaks
        phoneA.processOutgoingSpeech("checking communication link")
        val aPacket = phoneA.sentPackets.last()
        assertEquals(PacketType.CALL_TRANSCRIPT, aPacket.type)
        assertEquals("PHONE-B", aPacket.receiverId)
        assertEquals("PHONE-A", aPacket.senderId)
        assertEquals(sharedCallId, aPacket.conversationId)

        // 2. Phone B receives A's speech
        phoneB.handleIncomingPacket(aPacket)

        // 3. Phone B speaks in response
        phoneB.processOutgoingSpeech("loud and clear over")
        val bPacket = phoneB.sentPackets.last()
        assertEquals(PacketType.CALL_TRANSCRIPT, bPacket.type)
        assertEquals("PHONE-A", bPacket.receiverId)
        assertEquals("PHONE-B", bPacket.senderId)
        assertEquals(sharedCallId, bPacket.conversationId)

        // 4. Phone A receives B's speech
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
    }

    @Test
    fun testCallTranscriptDuplicateSuppression() {
        val phoneA = MockCallSession("PHONE-A")
        val sharedCallId = "call_dedup_test"
        phoneA.startActiveCallSession("PHONE-B", sharedCallId)

        val packet = Packet(
            msgId = 5001L,
            senderId = "PHONE-B",
            receiverId = "PHONE-A",
            conversationId = sharedCallId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Duplicate check segment",
            type = PacketType.CALL_TRANSCRIPT,
            timestamp = 100000L
        )

        // Process once
        phoneA.handleIncomingPacket(packet)
        assertEquals(1, phoneA.callTranscripts.value.size)

        // Duplicate delivery over mesh/transport
        phoneA.handleIncomingPacket(packet)
        assertEquals(1, phoneA.callTranscripts.value.size) // Suppressed!
    }

    @Test
    fun testCallTranscriptStrictSessionIsolation() {
        val phoneA = MockCallSession("PHONE-A")
        phoneA.startActiveCallSession("PHONE-B", "call_correct_id")

        // 1. Packet from different peer -> Rejected
        val wrongPeerPacket = Packet(
            msgId = 6001L,
            senderId = "PHONE-STRANGER",
            receiverId = "PHONE-A",
            conversationId = "call_correct_id",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Spying speech",
            type = PacketType.CALL_TRANSCRIPT,
            timestamp = 100000L
        )
        phoneA.handleIncomingPacket(wrongPeerPacket)
        assertEquals(0, phoneA.callTranscripts.value.size)

        // 2. Packet with wrong callId -> Rejected
        val wrongCallIdPacket = Packet(
            msgId = 6002L,
            senderId = "PHONE-B",
            receiverId = "PHONE-A",
            conversationId = "call_wrong_id",
            seq = 2,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Old call speech",
            type = PacketType.CALL_TRANSCRIPT,
            timestamp = 100001L
        )
        phoneA.handleIncomingPacket(wrongCallIdPacket)
        assertEquals(0, phoneA.callTranscripts.value.size)

        // 3. Transcript received when IDLE -> Rejected
        phoneA.endCall()
        val idlePacket = Packet(
            msgId = 6003L,
            senderId = "PHONE-B",
            receiverId = "PHONE-A",
            conversationId = "call_correct_id",
            seq = 3,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Late arriving speech",
            type = PacketType.CALL_TRANSCRIPT,
            timestamp = 100002L
        )
        phoneA.handleIncomingPacket(idlePacket)
        assertEquals(0, phoneA.callTranscripts.value.size)
    }

    @Test
    fun testZeroFeedLeakage() {
        val phone = MockCallSession("PHONE-A")
        phone.startActiveCallSession("PHONE-B", "call_isolation_test")

        // 1. Process CALL_SIGNAL
        val signalPacket = Packet(
            msgId = 7001L,
            senderId = "PHONE-B",
            receiverId = "PHONE-A",
            conversationId = "call_isolation_test",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "CALL_ACCEPT:call_isolation_test",
            type = PacketType.CALL_SIGNAL,
            timestamp = 100000L
        )
        phone.handleIncomingPacket(signalPacket)

        // 2. Process CALL_TRANSCRIPT
        val transcriptPacket = Packet(
            msgId = 7002L,
            senderId = "PHONE-B",
            receiverId = "PHONE-A",
            conversationId = "call_isolation_test",
            seq = 2,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Hello in call",
            type = PacketType.CALL_TRANSCRIPT,
            timestamp = 100001L
        )
        phone.handleIncomingPacket(transcriptPacket)

        // 3. Process regular chat DATA message
        val chatPacket = Packet(
            msgId = 7003L,
            senderId = "PHONE-B",
            receiverId = "PHONE-A",
            conversationId = "conv_PHONE-A_PHONE-B",
            seq = 3,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Regular chat message",
            type = PacketType.DATA,
            timestamp = 100002L
        )
        phone.handleIncomingPacket(chatPacket)

        // VERIFY: Only the regular chat message entered roomDbInsertedMessages!
        assertEquals(1, phone.roomDbInsertedMessages.size)
        assertEquals(7003L, phone.roomDbInsertedMessages[0].msgId)
        assertEquals(PacketType.DATA, phone.roomDbInsertedMessages[0].type)
    }

    @Test
    fun testCallMutePreventsSpeechTransmission() {
        val session = MockCallSession()
        session.startActiveCallSession("PEER-CHARLIE", "call_mute_test")
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
        assertEquals(PacketType.CALL_TRANSCRIPT, session.sentPackets[0].type)
        assertEquals("PEER-CHARLIE", session.sentPackets[0].receiverId)
        assertEquals(1, session.callTranscripts.value.size)
    }
}

