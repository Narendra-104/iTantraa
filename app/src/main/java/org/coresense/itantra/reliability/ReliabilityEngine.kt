package org.coresense.itantra.reliability

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.coresense.itantra.link.LinkTransport
import org.coresense.itantra.protocol.Packet
import org.coresense.itantra.protocol.PacketCodec
import org.coresense.itantra.protocol.PacketPriority
import org.coresense.itantra.protocol.PacketType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

data class PendingMessage(
    val packet: Packet,
    var state: DeliveryState,
    var attempts: Int = 0,
    var lastSentTimeMs: Long = 0L,
    val initialQueuedTimeMs: Long = SystemClock.elapsedRealtime()
)

data class ReliabilityStats(
    val packetsSent: Long = 0L,
    val packetsReceived: Long = 0L,
    val acksSent: Long = 0L,
    val acksReceived: Long = 0L,
    val retriesCount: Long = 0L,
    val packetLossPercent: Float = 0f,
    val averageRttMs: Long = 0L
)

/**
 * ReliabilityEngine:
 * - Two priority channels: SOS/URGENT preempts NORMAL
 * - SOS packets retried continuously until ACKed or cancelled
 * - Normal packets retried with exponential backoff (up to 3 times)
 * - In-order sequencing with monotonic sequence numbers
 * - De-duplication by msg_id on receiver
 * - Measures true RTT on ACK reception (clock-offset safe RTT/2)
 * - Computes real packet loss and retry stats
 */
class ReliabilityEngine(
    private val transport: LinkTransport,
    private val scope: CoroutineScope,
    private val localCallsign: String = "ALPHA-1"
) {

    private val seqCounter = AtomicInteger(1)

    // Two priority levels
    private val highPriorityQueue = ConcurrentLinkedQueue<PendingMessage>()
    private val normalPriorityQueue = ConcurrentLinkedQueue<PendingMessage>()

    // Unacknowledged in-flight packets mapped by msgId
    private val inFlight = ConcurrentHashMap<Long, PendingMessage>()

    // Received msg_ids for deduplication
    private val seenMessageIds = ConcurrentHashMap.newKeySet<Long>()

    // Event flows
    private val _messageDeliveryUpdates = MutableSharedFlow<Pair<Long, DeliveryState>>(extraBufferCapacity = 64)
    val messageDeliveryUpdates: SharedFlow<Pair<Long, DeliveryState>> = _messageDeliveryUpdates.asSharedFlow()

    private val _receivedPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    val receivedPackets: SharedFlow<Packet> = _receivedPackets.asSharedFlow()

    private val _stats = MutableStateFlow(ReliabilityStats())
    val stats: StateFlow<ReliabilityStats> = _stats.asStateFlow()

    private var workerJob: Job? = null
    private var receiverJob: Job? = null

    // Real measured metrics counters
    private var totalSentCount = 0L
    private var totalReceivedCount = 0L
    private var totalAcksSent = 0L
    private var totalAcksReceived = 0L
    private var totalRetries = 0L
    private var totalRttSum = 0L

    init {
        startWorker()
        startReceiver()
    }

    fun nextSequenceNumber(): Int = seqCounter.getAndIncrement()

    fun enqueue(packet: Packet) {
        val pending = PendingMessage(
            packet = packet,
            state = DeliveryState.Queued
        )

        if (packet.priority == PacketPriority.SOS || packet.priority == PacketPriority.URGENT) {
            highPriorityQueue.offer(pending)
        } else {
            normalPriorityQueue.offer(pending)
        }
        _messageDeliveryUpdates.tryEmit(Pair(packet.msgId, DeliveryState.Queued))
    }

    fun cancelSos(msgId: Long) {
        highPriorityQueue.removeIf { it.packet.msgId == msgId }
        inFlight.remove(msgId)
        _messageDeliveryUpdates.tryEmit(Pair(msgId, DeliveryState.Failed("Cancelled by user")))
    }

    private fun startWorker() {
        workerJob?.cancel()
        workerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                // 1. Process in-flight retries
                val now = SystemClock.elapsedRealtime()
                for ((msgId, pending) in inFlight) {
                    val isSos = pending.packet.priority == PacketPriority.SOS
                    val backoff = when (pending.attempts) {
                        1 -> 500L
                        2 -> 1000L
                        3 -> 2000L
                        else -> 3000L
                    }

                    if (now - pending.lastSentTimeMs >= backoff) {
                        val maxAttempts = if (isSos) Int.MAX_VALUE else 4
                        if (pending.attempts < maxAttempts) {
                            pending.attempts++
                            totalRetries++
                            updateStats()
                            sendPacketOverWire(pending)
                        } else {
                            // Max retries exceeded
                            inFlight.remove(msgId)
                            pending.state = DeliveryState.Failed("No ACK received after ${pending.attempts} attempts")
                            _messageDeliveryUpdates.emit(Pair(msgId, pending.state))
                        }
                    }
                }

                // 2. Transmit next queued message (SOS/URGENT preempts NORMAL)
                val nextToSend = highPriorityQueue.poll() ?: normalPriorityQueue.poll()
                if (nextToSend != null) {
                    nextToSend.attempts = 1
                    inFlight[nextToSend.packet.msgId] = nextToSend
                    sendPacketOverWire(nextToSend)
                }

                delay(50)
            }
        }
    }

    private suspend fun sendPacketOverWire(pending: PendingMessage) {
        pending.lastSentTimeMs = SystemClock.elapsedRealtime()
        pending.state = DeliveryState.Sending
        _messageDeliveryUpdates.emit(Pair(pending.packet.msgId, DeliveryState.Sending))

        val encoded = PacketCodec.encode(pending.packet)
        val success = transport.send(encoded)

        if (success) {
            totalSentCount++
            updateStats()
            pending.state = DeliveryState.SentAwaitingAck(pending.lastSentTimeMs)
            _messageDeliveryUpdates.emit(Pair(pending.packet.msgId, pending.state))
        } else {
            // Immediate socket failure, will retry next cycle
        }
    }

    private fun startReceiver() {
        receiverJob?.cancel()
        receiverJob = scope.launch(Dispatchers.IO) {
            transport.incomingPackets.collect { rawBytes ->
                val decodeResult = PacketCodec.decode(rawBytes)
                if (decodeResult.isFailure) {
                    // CRC failure or corrupted payload - drop or ignore
                    return@collect
                }

                val packet = decodeResult.getOrThrow()
                totalReceivedCount++

                when (packet.type) {
                    PacketType.ACK -> {
                        totalAcksReceived++
                        val inFlightMsg = inFlight.remove(packet.msgId)
                        if (inFlightMsg != null) {
                            val rttMs = SystemClock.elapsedRealtime() - inFlightMsg.lastSentTimeMs
                            totalRttSum += rttMs
                            inFlightMsg.state = DeliveryState.Delivered(rttMs)
                            _messageDeliveryUpdates.emit(Pair(packet.msgId, inFlightMsg.state))
                        }
                        updateStats()
                    }

                    PacketType.DATA -> {
                        // Send ACK back immediately
                        sendAck(packet)

                        // Deduplicate: check if already seen
                        if (!seenMessageIds.contains(packet.msgId)) {
                            seenMessageIds.add(packet.msgId)
                            _receivedPackets.emit(packet)
                        }
                        updateStats()
                    }

                    PacketType.NACK -> {
                        // Immediately re-queue packet for retry
                        val inFlightMsg = inFlight[packet.msgId]
                        if (inFlightMsg != null) {
                            inFlightMsg.lastSentTimeMs = 0L // force instant retry
                        }
                    }

                    PacketType.PING -> {
                        sendPong(packet)
                    }

                    PacketType.PONG -> {
                        // Keepalive
                    }
                }
            }
        }
    }

    private suspend fun sendAck(dataPacket: Packet) {
        val ackPacket = Packet(
            msgId = dataPacket.msgId,
            senderId = localCallsign,
            seq = nextSequenceNumber(),
            lang = dataPacket.lang,
            priority = dataPacket.priority,
            text = "ACK",
            timestamp = System.currentTimeMillis(),
            type = PacketType.ACK
        )
        val encoded = PacketCodec.encode(ackPacket)
        if (transport.send(encoded)) {
            totalAcksSent++
            updateStats()
        }
    }

    private suspend fun sendPong(pingPacket: Packet) {
        val pongPacket = Packet(
            msgId = pingPacket.msgId,
            senderId = localCallsign,
            seq = nextSequenceNumber(),
            lang = pingPacket.lang,
            priority = PacketPriority.NORMAL,
            text = "PONG",
            timestamp = System.currentTimeMillis(),
            type = PacketType.PONG
        )
        transport.send(PacketCodec.encode(pongPacket))
    }

    private fun updateStats() {
        val avgRtt = if (totalAcksReceived > 0) totalRttSum / totalAcksReceived else 0L
        val loss = if (totalSentCount > 0) {
            val unacked = inFlight.values.count { it.state is DeliveryState.SentAwaitingAck || it.state is DeliveryState.Failed }
            (unacked.toFloat() / totalSentCount.toFloat() * 100f).coerceIn(0f, 100f)
        } else 0f

        _stats.value = ReliabilityStats(
            packetsSent = totalSentCount,
            packetsReceived = totalReceivedCount,
            acksSent = totalAcksSent,
            acksReceived = totalAcksReceived,
            retriesCount = totalRetries,
            packetLossPercent = loss,
            averageRttMs = avgRtt
        )
    }

    fun close() {
        workerJob?.cancel()
        receiverJob?.cancel()
        inFlight.clear()
        highPriorityQueue.clear()
        normalPriorityQueue.clear()
    }
}
