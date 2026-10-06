package org.coresense.itantra.reliability

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.coresense.itantra.link.ConnectionState
import org.coresense.itantra.link.LinkTransport
import org.coresense.itantra.link.PeerDevice
import org.coresense.itantra.link.TransportType
import org.coresense.itantra.protocol.Language
import org.coresense.itantra.protocol.Packet
import org.coresense.itantra.protocol.PacketCodec
import org.coresense.itantra.protocol.PacketPriority
import org.coresense.itantra.protocol.PacketType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ReliabilityEngineTest {

    private lateinit var scope: CoroutineScope
    private lateinit var transport: MockLinkTransport
    private lateinit var engine: ReliabilityEngine

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.Unconfined + Job())
        transport = MockLinkTransport()
        engine = ReliabilityEngine(transport, scope, "LOCAL-123")
    }

    @After
    fun teardown() {
        engine.close()
        scope.cancel()
    }

    @Test
    fun testDataToAck() = runBlocking {
        // Send a DATA packet via transport to the engine (simulating incoming)
        val dataPacket = Packet(
            msgId = 101,
            senderId = "REMOTE-456",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Hello",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        delay(500)
        transport.simulateIncoming(PacketCodec.encode(dataPacket))

        // Yield to allow the engine's receiverJob to process it
        delay(100)

        // Verify that the engine sent an ACK back
        val sentPackets = transport.sentPackets.map { PacketCodec.decode(it).getOrThrow() }
        assertTrue(sentPackets.any { it.type == PacketType.ACK && it.msgId == 101L })
    }

    @Test
    fun testDuplicateDataDropped() = runBlocking {
        val dataPacket = Packet(
            msgId = 102,
            senderId = "REMOTE-456",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Hello",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        val received = mutableListOf<Packet>()
        val job = launch {
            engine.receivedPackets.collect { received.add(it) }
        }

        val encoded = PacketCodec.encode(dataPacket)
        delay(500)
        transport.simulateIncoming(encoded)
        delay(100)

        // Send EXACT SAME packet again
        delay(500)
        transport.simulateIncoming(encoded)
        delay(100)

        // Should have emitted only once to receivedPackets, but ACKed twice
        assertEquals(1, received.size)

        val acks = transport.sentPackets.map { PacketCodec.decode(it).getOrThrow() }.filter { it.type == PacketType.ACK }
        assertEquals(2, acks.size)

        job.cancel()
    }

    @Test
    fun testCorruptedDataSendsNack() = runBlocking {
        val dataPacket = Packet(
            msgId = 103,
            senderId = "REMOTE-456",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Hello",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        val encoded = PacketCodec.encode(dataPacket)
        // Corrupt the CRC manually: the last 4 bytes are CRC32 in the frame
        encoded[encoded.size - 1] = (encoded[encoded.size - 1].toInt() xor 0xFF).toByte()

        delay(500)
        transport.simulateIncoming(encoded)
        delay(100)

        val sentPackets = transport.sentPackets.map { PacketCodec.decode(it).getOrThrow() }
        assertTrue(sentPackets.any { it.type == PacketType.NACK && it.msgId == 103L })
    }

    @Test
    fun testOutgoingDataTimeoutRetry() = runBlocking {
        val dataPacket = Packet(
            msgId = 104,
            senderId = "LOCAL-123",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Hello",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        engine.enqueue(dataPacket)
        
        // Wait for first transmission + 1 retry (500ms backoff)
        delay(700)

        // Transport should have sent the same packet at least twice
        val sentPackets = transport.sentPackets.map { PacketCodec.decode(it).getOrThrow() }
        assertTrue(sentPackets.size >= 2)
        assertTrue(sentPackets.all { it.msgId == 104L })
    }

    @Test
    fun testOutgoingDataAckedAndDelivered() = runBlocking {
        val dataPacket = Packet(
            msgId = 105,
            senderId = "LOCAL-123",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Hello",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        engine.enqueue(dataPacket)
        delay(100)

        // Engine sent it
        val sentBytes = transport.sentPackets.first()
        val sent = PacketCodec.decode(sentBytes).getOrThrow()
        assertEquals(105L, sent.msgId)

        // Simulate incoming ACK from remote
        val ackPacket = Packet(
            msgId = 105,
            senderId = "REMOTE-456",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "ACK",
            timestamp = System.currentTimeMillis(),
            type = PacketType.ACK
        )
        transport.simulateIncoming(PacketCodec.encode(ackPacket))

        delay(100)

        // Validate state
        val stateUpdates = mutableListOf<DeliveryState>()
        val job = launch {
            engine.messageDeliveryUpdates.collect { if (it.first == 105L) stateUpdates.add(it.second) }
        }
        
        delay(50) // let state flow update
        assertTrue(engine.stats.value.acksReceived >= 1)
        job.cancel()
    }

    @Test
    fun testSosRetryLimitEventuallyFails() = runBlocking {
        val testEngine = ReliabilityEngine(
            transport = transport,
            scope = scope,
            localCallsign = "LOCAL-123",
            maxSosAttempts = 2
        )

        val sosPacket = Packet(
            msgId = 999,
            senderId = "LOCAL-123",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "EMERGENCY SOS",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        val deliveryStates = CopyOnWriteArrayList<DeliveryState>()
        val collectJob = launch {
            testEngine.messageDeliveryUpdates.collect { (msgId, state) ->
                if (msgId == 999L) {
                    deliveryStates.add(state)
                }
            }
        }

        testEngine.enqueue(sosPacket)

        // Wait for attempt 1 (initial send), attempt 2 (after 500ms backoff), and failure trigger (after 1000ms backoff)
        delay(1800)

        // Verify sent packets: attempt 1 + attempt 2 = 2 sends
        val sentSosPackets = transport.sentPackets
            .map { PacketCodec.decode(it).getOrThrow() }
            .filter { it.msgId == 999L }
        assertEquals(2, sentSosPackets.size)

        // Verify that the final state transitioned to Failed
        val failedState = deliveryStates.find { it is DeliveryState.Failed }
        assertNotNull("Expected DeliveryState.Failed", failedState)
        assertTrue((failedState as DeliveryState.Failed).reason.contains("2 attempts"))

        // Wait an additional interval to verify no infinite retries occur
        delay(1200)
        val sentSosPacketsAfter = transport.sentPackets
            .map { PacketCodec.decode(it).getOrThrow() }
            .filter { it.msgId == 999L }
        assertEquals("Must not retry after reaching max attempts", 2, sentSosPacketsAfter.size)

        collectJob.cancel()
        testEngine.close()
    }
}

class MockLinkTransport : LinkTransport {
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
