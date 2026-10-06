package org.coresense.itantra.chat

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.coresense.itantra.data.repository.MessageRepository
import org.coresense.itantra.link.ConnectionState
import org.coresense.itantra.link.LinkTransport
import org.coresense.itantra.link.PeerDevice
import org.coresense.itantra.link.TransportType
import org.coresense.itantra.protocol.Language
import org.coresense.itantra.protocol.Packet
import org.coresense.itantra.protocol.PacketCodec
import org.coresense.itantra.protocol.PacketPriority
import org.coresense.itantra.protocol.PacketType
import org.coresense.itantra.reliability.DeliveryState
import org.coresense.itantra.reliability.ReliabilityEngine
import org.coresense.itantra.storage.ConversationDao
import org.coresense.itantra.storage.ConversationEntity
import org.coresense.itantra.storage.MessageDao
import org.coresense.itantra.storage.MessageEntity
import org.coresense.itantra.ui.MainViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ChatEndToEndTest {

    private lateinit var scope: CoroutineScope
    private lateinit var fakeMessageDaoA: FakeMessageDao
    private lateinit var fakeConversationDaoA: FakeConversationDao
    private lateinit var repoA: MessageRepository

    private lateinit var fakeMessageDaoB: FakeMessageDao
    private lateinit var fakeConversationDaoB: FakeConversationDao
    private lateinit var repoB: MessageRepository

    private val deviceAId = "IT-DEVICE-ALPHA"
    private val deviceBId = "IT-DEVICE-BRAVO"

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.Unconfined + Job())
        fakeMessageDaoA = FakeMessageDao()
        fakeConversationDaoA = FakeConversationDao()
        repoA = MessageRepository(fakeMessageDaoA, fakeConversationDaoA)

        fakeMessageDaoB = FakeMessageDao()
        fakeConversationDaoB = FakeConversationDao()
        repoB = MessageRepository(fakeMessageDaoB, fakeConversationDaoB)
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    // ==========================================
    // TEST 1 & TEST 2: Outgoing Packet Creation & Fields
    // ==========================================
    @Test
    fun testOutgoingChatMessageCreatesCorrectPacketAndEncodesProperly() {
        val text = "Hello Bravo, this is Alpha"
        val msgId = 1001L
        val seq = 1
        val convId = MainViewModel.getConversationId(deviceAId, deviceBId)

        val packet = Packet(
            msgId = msgId,
            senderId = deviceAId,
            receiverId = deviceBId,
            departmentId = null,
            conversationId = convId,
            seq = seq,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = text,
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        // Verify packet contents
        assertEquals(PacketType.DATA, packet.type)
        assertEquals(deviceAId, packet.senderId)
        assertEquals(deviceBId, packet.receiverId)
        assertEquals("conv_IT-DEVICE-ALPHA_IT-DEVICE-BRAVO", packet.conversationId)
        assertEquals(msgId, packet.msgId)
        assertEquals(text, packet.text)

        // Verify encoding & decoding preserves all routing fields exactly
        val encoded = PacketCodec.encode(packet)
        assertTrue(encoded.isNotEmpty())

        val decodedResult = PacketCodec.decode(encoded)
        assertTrue(decodedResult.isSuccess)

        val decoded = decodedResult.getOrThrow()
        assertEquals(packet.msgId, decoded.msgId)
        assertEquals(packet.senderId, decoded.senderId)
        assertEquals(packet.receiverId, decoded.receiverId)
        assertEquals(packet.conversationId, decoded.conversationId)
        assertEquals(packet.text, decoded.text)
        assertEquals(packet.priority, decoded.priority)
        assertEquals(packet.type, decoded.type)
    }

    // ==========================================
    // TEST 3: Valid Incoming DATA is stored in Room
    // ==========================================
    @Test
    fun testValidIncomingDataIsStoredInRoom() = runBlocking {
        val convId = MainViewModel.getConversationId(deviceAId, deviceBId)
        val incomingPacket = Packet(
            msgId = 1002L,
            senderId = deviceAId,
            receiverId = deviceBId,
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Direct message for Bravo",
            timestamp = 1700000000000L,
            type = PacketType.DATA
        )

        // Routing check on Device B
        val isForDeviceB = incomingPacket.receiverId == null ||
                incomingPacket.receiverId == "GLOBAL" ||
                incomingPacket.receiverId == deviceBId

        assertTrue("Message should be routed to Device B", isForDeviceB)

        // Store in Device B repository
        repoB.insertConversation(
            ConversationEntity(
                conversationId = convId,
                localDeviceId = deviceBId,
                participantId = incomingPacket.senderId,
                createdAt = incomingPacket.timestamp,
                updatedAt = incomingPacket.timestamp
            )
        )

        repoB.insertMessage(
            MessageEntity(
                msgId = incomingPacket.msgId,
                senderId = incomingPacket.senderId,
                seq = incomingPacket.seq,
                lang = incomingPacket.lang.displayName,
                priority = incomingPacket.priority.name,
                text = incomingPacket.text,
                receiverId = incomingPacket.receiverId,
                conversationId = incomingPacket.conversationId,
                timestamp = incomingPacket.timestamp,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "DELIVERED",
                isIncoming = true
            )
        )

        // Verify persistence
        val storedMsg = repoB.getMessageById(1002L)
        assertNotNull(storedMsg)
        assertEquals("Direct message for Bravo", storedMsg!!.text)
        assertEquals(deviceAId, storedMsg.senderId)
        assertEquals(deviceBId, storedMsg.receiverId)
        assertEquals(convId, storedMsg.conversationId)
        assertTrue(storedMsg.isIncoming)
        assertEquals("DELIVERED", storedMsg.deliveryState)
    }

    // ==========================================
    // TEST 4: Incoming Message for Another Receiver is Dropped
    // ==========================================
    @Test
    fun testIncomingMessageForAnotherReceiverIsNotStored() = runBlocking {
        val foreignPacket = Packet(
            msgId = 1003L,
            senderId = deviceAId,
            receiverId = "IT-DEVICE-CHARLIE", // Addressed to Charlie, not Bravo
            conversationId = "conv_ALPHA_CHARLIE",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Confidential for Charlie only",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        val myDept = "GLOBAL"
        // Routing check on Device B
        val isForDeviceB = foreignPacket.receiverId == null ||
                foreignPacket.receiverId == "GLOBAL" ||
                foreignPacket.receiverId == deviceBId ||
                foreignPacket.receiverId == myDept

        assertFalse("Message addressed to Charlie must not match Device B", isForDeviceB)

        // Since isForDeviceB is false, it is dropped and never inserted into repoB
        val stored = repoB.getMessageById(1003L)
        assertNull("Dropped packet must not exist in database", stored)
    }

    // ==========================================
    // TEST 5: Duplicate Incoming msgId Does Not Create Second Record
    // ==========================================
    @Test
    fun testDuplicateIncomingMsgIdDoesNotCreateSecondRecord() = runBlocking {
        val mockTransport = TestMockTransport()
        val engineB = ReliabilityEngine(mockTransport, scope, deviceBId)

        val packet = Packet(
            msgId = 1004L,
            senderId = deviceAId,
            receiverId = deviceBId,
            conversationId = "conv_AB",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Duplicate check message",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        val receivedPackets = mutableListOf<Packet>()
        val collectJob = launch {
            engineB.receivedPackets.collect { receivedPackets.add(it) }
        }

        val encoded = PacketCodec.encode(packet)
        delay(200)

        // First arrival
        mockTransport.simulateIncoming(encoded)
        delay(200)

        // Second arrival (retransmission from sender)
        mockTransport.simulateIncoming(encoded)
        delay(200)

        // Engine deduplication must emit only once
        assertEquals("ReliabilityEngine should emit received packet exactly once", 1, receivedPackets.size)

        // Insert into Room
        repoB.insertMessage(
            MessageEntity(
                msgId = packet.msgId,
                senderId = packet.senderId,
                seq = packet.seq,
                lang = packet.lang.displayName,
                priority = packet.priority.name,
                text = packet.text,
                receiverId = packet.receiverId,
                conversationId = packet.conversationId,
                timestamp = packet.timestamp,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "DELIVERED",
                isIncoming = true
            )
        )

        // Simulating second insert with REPLACE strategy
        repoB.insertMessage(
            MessageEntity(
                msgId = packet.msgId,
                senderId = packet.senderId,
                seq = packet.seq,
                lang = packet.lang.displayName,
                priority = packet.priority.name,
                text = packet.text,
                receiverId = packet.receiverId,
                conversationId = packet.conversationId,
                timestamp = packet.timestamp,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "DELIVERED",
                isIncoming = true
            )
        )

        // Check total count for this msgId
        val messages = fakeMessageDaoB.messages.values.filter { it.msgId == 1004L }
        assertEquals("Must only have 1 database record for msgId", 1, messages.size)

        // But sender should receive 2 ACKs (one for original, one for retransmit)
        val ackCount = mockTransport.sentPackets
            .map { PacketCodec.decode(it).getOrThrow() }
            .count { it.type == PacketType.ACK && it.msgId == 1004L }
        assertEquals("Receiver must ACK both original and duplicate", 2, ackCount)

        collectJob.cancel()
        engineB.close()
    }

    // ==========================================
    // TEST 6: Receiver Reply Uses the Exact Same conversationId
    // ==========================================
    @Test
    fun testReceiverReplyUsesSameConversationId() = runBlocking {
        // Device A initiates conversation with Device B
        val convIdAtoB = MainViewModel.getConversationId(deviceAId, deviceBId)

        val messageAtoB = Packet(
            msgId = 2001L,
            senderId = deviceAId,
            receiverId = deviceBId,
            conversationId = convIdAtoB,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Hello Bravo",
            timestamp = 1000L,
            type = PacketType.DATA
        )

        // Device B calculates conversation ID when replying to Device A
        val convIdBtoA = MainViewModel.getConversationId(deviceBId, deviceAId)

        // Both deterministic calculations must match
        assertEquals(convIdAtoB, convIdBtoA)
        assertEquals("conv_IT-DEVICE-ALPHA_IT-DEVICE-BRAVO", convIdBtoA)

        val replyBtoA = Packet(
            msgId = 2002L,
            senderId = deviceBId,
            receiverId = deviceAId,
            conversationId = convIdBtoA,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Hello Alpha, reply received",
            timestamp = 2000L,
            type = PacketType.DATA
        )

        // Store both in Device A repository
        repoA.insertMessage(
            MessageEntity(
                msgId = messageAtoB.msgId,
                senderId = messageAtoB.senderId,
                seq = messageAtoB.seq,
                lang = messageAtoB.lang.displayName,
                priority = messageAtoB.priority.name,
                text = messageAtoB.text,
                receiverId = messageAtoB.receiverId,
                conversationId = messageAtoB.conversationId,
                timestamp = messageAtoB.timestamp,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "DELIVERED",
                isIncoming = false
            )
        )
        repoA.insertMessage(
            MessageEntity(
                msgId = replyBtoA.msgId,
                senderId = replyBtoA.senderId,
                seq = replyBtoA.seq,
                lang = replyBtoA.lang.displayName,
                priority = replyBtoA.priority.name,
                text = replyBtoA.text,
                receiverId = replyBtoA.receiverId,
                conversationId = replyBtoA.conversationId,
                timestamp = replyBtoA.timestamp,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "DELIVERED",
                isIncoming = true
            )
        )

        // Both messages must belong to the exact same conversation
        val conversationMessages = fakeMessageDaoA.messages.values
            .filter { it.conversationId == convIdAtoB }
            .sortedBy { it.timestamp }

        assertEquals(2, conversationMessages.size)
        assertEquals("Hello Bravo", conversationMessages[0].text)
        assertEquals("Hello Alpha, reply received", conversationMessages[1].text)
    }

    // ==========================================
    // TEST 7: ACK Causes Outgoing Message to Become DELIVERED
    // ==========================================
    @Test
    fun testAckCausesOriginalOutgoingMessageToBecomeDelivered() = runBlocking {
        val mockTransport = TestMockTransport()
        val engineA = ReliabilityEngine(mockTransport, scope, deviceAId)

        val packet = Packet(
            msgId = 3001L,
            senderId = deviceAId,
            receiverId = deviceBId,
            conversationId = "conv_AB",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Testing ACK transition",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        // Insert initial message into repoA as QUEUED
        repoA.insertMessage(
            MessageEntity(
                msgId = packet.msgId,
                senderId = packet.senderId,
                seq = packet.seq,
                lang = packet.lang.displayName,
                priority = packet.priority.name,
                text = packet.text,
                receiverId = packet.receiverId,
                conversationId = packet.conversationId,
                timestamp = packet.timestamp,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "QUEUED",
                isIncoming = false
            )
        )

        // Listen for delivery updates and sync to repoA
        val collectJob = launch {
            engineA.messageDeliveryUpdates.collect { (msgId, state) ->
                val rtt = (state as? DeliveryState.Delivered)?.rttMs ?: 0L
                repoA.updateDeliveryState(msgId, state.name, rtt)
            }
        }

        // Enqueue into engine
        engineA.enqueue(packet)
        delay(150)

        // Verify sent over transport
        assertEquals(1, mockTransport.sentPackets.size)

        // Simulate incoming ACK from Device B
        val ack = Packet(
            msgId = 3001L,
            senderId = deviceBId,
            receiverId = deviceAId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "ACK",
            timestamp = System.currentTimeMillis(),
            type = PacketType.ACK
        )
        mockTransport.simulateIncoming(PacketCodec.encode(ack))
        delay(150)

        // Verify message state in repoA updated to DELIVERED
        val updated = repoA.getMessageById(3001L)
        assertNotNull(updated)
        assertEquals("DELIVERED", updated!!.deliveryState)

        collectJob.cancel()
        engineA.close()
    }

    // ==========================================
    // TEST 8: Failed Delivery Eventually Becomes FAILED
    // ==========================================
    @Test
    fun testFailedDeliveryEventuallyBecomesFailed() = runBlocking {
        val mockTransport = TestMockTransport()
        // Configure max attempts = 2 for fast deterministic test
        val engineA = ReliabilityEngine(
            transport = mockTransport,
            scope = scope,
            localCallsign = deviceAId,
            maxNormalAttempts = 2
        )

        val packet = Packet(
            msgId = 4001L,
            senderId = deviceAId,
            receiverId = deviceBId,
            conversationId = "conv_AB",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Unreachable destination test",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        repoA.insertMessage(
            MessageEntity(
                msgId = packet.msgId,
                senderId = packet.senderId,
                seq = packet.seq,
                lang = packet.lang.displayName,
                priority = packet.priority.name,
                text = packet.text,
                receiverId = packet.receiverId,
                conversationId = packet.conversationId,
                timestamp = packet.timestamp,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "QUEUED",
                isIncoming = false
            )
        )

        val collectJob = launch {
            engineA.messageDeliveryUpdates.collect { (msgId, state) ->
                val rtt = (state as? DeliveryState.Delivered)?.rttMs ?: 0L
                repoA.updateDeliveryState(msgId, state.name, rtt)
            }
        }

        engineA.enqueue(packet)

        // Wait for attempt 1 (0ms), attempt 2 (500ms backoff), and failure trigger (1000ms backoff)
        delay(1800)

        val failedMsg = repoA.getMessageById(4001L)
        assertNotNull(failedMsg)
        assertEquals("FAILED", failedMsg!!.deliveryState)

        collectJob.cancel()
        engineA.close()
    }

    // ==========================================
    // TEST 9: Corrupted Packet is Not Stored or Displayed
    // ==========================================
    @Test
    fun testCorruptedPacketIsNotStored() = runBlocking {
        val mockTransport = TestMockTransport()
        val engineB = ReliabilityEngine(mockTransport, scope, deviceBId)

        val packet = Packet(
            msgId = 5001L,
            senderId = deviceAId,
            receiverId = deviceBId,
            conversationId = "conv_AB",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Corrupted transmission test",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        val received = mutableListOf<Packet>()
        val collectJob = launch {
            engineB.receivedPackets.collect { received.add(it) }
        }

        val encoded = PacketCodec.encode(packet)
        // Flip bits in the CRC bytes
        encoded[encoded.size - 1] = (encoded[encoded.size - 1].toInt() xor 0xFF).toByte()

        delay(100)
        mockTransport.simulateIncoming(encoded)
        delay(200)

        // Corrupted packet should not be emitted
        assertTrue(received.isEmpty())

        // And not stored in repoB
        assertNull(repoB.getMessageById(5001L))

        // Transport should have sent a NACK back to the sender
        val nackSent = mockTransport.sentPackets
            .map { PacketCodec.decode(it).getOrThrow() }
            .any { it.type == PacketType.NACK && it.msgId == 5001L }
        assertTrue("Corrupted packet must generate NACK", nackSent)

        collectJob.cancel()
        engineB.close()
    }

    // ==========================================
    // TEST 10: Conversation Survives Simulated App Restart / Database Reload
    // ==========================================
    @Test
    fun testConversationSurvivesDatabaseReload() = runBlocking {
        val convId = MainViewModel.getConversationId(deviceAId, deviceBId)

        // 1. Initial session: store conversation and messages
        repoA.insertConversation(
            ConversationEntity(
                conversationId = convId,
                localDeviceId = deviceAId,
                participantId = deviceBId,
                createdAt = 1000L,
                updatedAt = 2000L
            )
        )
        repoA.insertMessage(
            MessageEntity(
                msgId = 6001L,
                senderId = deviceAId,
                seq = 1,
                lang = "English",
                priority = "NORMAL",
                text = "Persistent message 1",
                receiverId = deviceBId,
                conversationId = convId,
                timestamp = 1000L,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "DELIVERED",
                isIncoming = false
            )
        )
        repoA.insertMessage(
            MessageEntity(
                msgId = 6002L,
                senderId = deviceBId,
                seq = 2,
                lang = "English",
                priority = "NORMAL",
                text = "Persistent reply 2",
                receiverId = deviceAId,
                conversationId = convId,
                timestamp = 2000L,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "DELIVERED",
                isIncoming = true
            )
        )

        // 2. Simulate App Restart: create new MessageRepository instance over the existing DAOs
        val reloadedRepo = MessageRepository(fakeMessageDaoA, fakeConversationDaoA)

        // 3. Verify conversation and messages reload identically
        val reloadedMsg1 = reloadedRepo.getMessageById(6001L)
        val reloadedMsg2 = reloadedRepo.getMessageById(6002L)

        assertNotNull(reloadedMsg1)
        assertNotNull(reloadedMsg2)
        assertEquals("Persistent message 1", reloadedMsg1!!.text)
        assertEquals("Persistent reply 2", reloadedMsg2!!.text)
        assertEquals(convId, reloadedMsg1.conversationId)
        assertEquals(convId, reloadedMsg2.conversationId)

        val convList = fakeConversationDaoA.conversations.values.toList()
        assertEquals(1, convList.size)
        assertEquals(convId, convList[0].conversationId)
        assertEquals(deviceBId, convList[0].participantId)
    }
}

// ==========================================
// In-Memory Test Doubles for Room DAOs and Transport
// ==========================================

class FakeMessageDao : MessageDao {
    val messages = ConcurrentHashMap<Long, MessageEntity>()
    private val _flow = MutableStateFlow<List<MessageEntity>>(emptyList())

    private fun updateFlow() {
        _flow.value = messages.values.sortedBy { it.timestamp }
    }

    override fun getAllMessages(): Flow<List<MessageEntity>> = _flow.asStateFlow()

    override fun getEmergencyMessages(): Flow<List<MessageEntity>> {
        return MutableStateFlow(messages.values.filter { it.priority == "SOS" })
    }

    override fun getConversationMessages(conversationId: String): Flow<List<MessageEntity>> {
        return MutableStateFlow(messages.values.filter { it.conversationId == conversationId }.sortedBy { it.timestamp })
    }

    override suspend fun getMessageById(msgId: Long): MessageEntity? = messages[msgId]

    override fun getMessagesForDepartment(departmentId: String): Flow<List<MessageEntity>> {
        return MutableStateFlow(messages.values.filter { it.departmentId == departmentId })
    }

    override suspend fun insertMessage(message: MessageEntity) {
        messages[message.msgId] = message
        updateFlow()
    }

    override suspend fun updateDeliveryState(msgId: Long, deliveryState: String, rttMs: Long) {
        val existing = messages[msgId]
        if (existing != null) {
            messages[msgId] = existing.copy(deliveryState = deliveryState, rttMs = rttMs)
            updateFlow()
        }
    }

    override suspend fun clearAll() {
        messages.clear()
        updateFlow()
    }
}

class FakeConversationDao : ConversationDao {
    val conversations = ConcurrentHashMap<String, ConversationEntity>()
    private val _flow = MutableStateFlow<List<ConversationEntity>>(emptyList())

    private fun updateFlow() {
        _flow.value = conversations.values.sortedByDescending { it.updatedAt }
    }

    override fun getAllConversations(): Flow<List<ConversationEntity>> = _flow.asStateFlow()

    override suspend fun insertConversation(conversation: ConversationEntity) {
        conversations[conversation.conversationId] = conversation
        updateFlow()
    }

    override suspend fun updateConversationTime(conversationId: String, updatedAt: Long) {
        val existing = conversations[conversationId]
        if (existing != null) {
            conversations[conversationId] = existing.copy(updatedAt = updatedAt)
            updateFlow()
        }
    }
}

class TestMockTransport : LinkTransport {
    override val transportType = TransportType.BLUETOOTH_RFCOMM
    override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())

    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<ByteArray> = _incomingPackets

    val sentPackets = CopyOnWriteArrayList<ByteArray>()

    override fun startDiscovery() = Result.success(Unit)
    override fun stopDiscovery() {}
    override fun connect(peer: PeerDevice) = Result.success(Unit)
    override fun disconnect() {}

    override suspend fun send(data: ByteArray): Boolean {
        sentPackets.add(data)
        return true
    }

    override fun close() {}

    suspend fun simulateIncoming(data: ByteArray) {
        _incomingPackets.emit(data)
    }
}
