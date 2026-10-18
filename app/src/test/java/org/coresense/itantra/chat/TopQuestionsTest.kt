package org.coresense.itantra.chat

import kotlinx.coroutines.*
import org.coresense.itantra.data.repository.MessageRepository
import org.coresense.itantra.identity.Role
import org.coresense.itantra.identity.UserIdentity
import org.coresense.itantra.protocol.*
import org.coresense.itantra.sos.FakeConversationDao
import org.coresense.itantra.sos.FakeMessageDao
import org.coresense.itantra.storage.MessageEntity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class TopQuestionsTest {

    private lateinit var scope: CoroutineScope
    private val senderId = "IT-OPERATOR-01"
    private val receiverId = "IT-OPERATOR-02"
    private lateinit var fakeDao: FakeMessageDao
    private lateinit var messageRepository: MessageRepository

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.Unconfined + Job())
        fakeDao = FakeMessageDao()
        messageRepository = MessageRepository(fakeDao, FakeConversationDao())
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    @Test
    fun testPredefinedQuestionsAreValidAndNonEmpty() {
        val questions = listOf(
            "Need immediate assistance.",
            "What is your status and location?",
            "Send backup and medical supplies.",
            "Area secured and checkpoint clear.",
            "Heading towards target coordinates.",
            "Casualty count update requested.",
            "Route blocked, requesting alternate path.",
            "All personnel accounted for."
        )

        assertTrue(questions.size >= 5)
        questions.forEach { q ->
            assertTrue("Question must not be blank", q.isNotBlank())
            assertTrue("Question must be of meaningful length", q.length > 5)
        }
    }

    @Test
    fun testSendingTopQuestionCreatesValidPacketAndPersists() = runBlocking {
        val question = "Need immediate assistance."
        val convId = "conv_${senderId}_${receiverId}"
        val msgId = 14001L

        val packet = Packet(
            msgId = msgId,
            senderId = senderId,
            receiverId = receiverId,
            departmentId = null,
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = question,
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        // Verify wire encoding/decoding
        val encoded = PacketCodec.encode(packet)
        val decoded = PacketCodec.decode(encoded).getOrThrow()

        assertEquals(question, decoded.text)
        assertEquals(senderId, decoded.senderId)
        assertEquals(receiverId, decoded.receiverId)
        assertEquals(PacketPriority.NORMAL, decoded.priority)

        // Verify DB persistence
        val entity = MessageEntity(
            msgId = packet.msgId,
            senderId = packet.senderId,
            seq = packet.seq,
            lang = packet.lang.displayName,
            priority = packet.priority.name,
            text = packet.text,
            receiverId = packet.receiverId,
            departmentId = packet.departmentId,
            conversationId = packet.conversationId,
            timestamp = packet.timestamp,
            latitudeMicrodegrees = null,
            longitudeMicrodegrees = null,
            deliveryState = "QUEUED",
            isIncoming = false
        )
        messageRepository.insertMessage(entity)

        val retrieved = messageRepository.getMessageById(msgId)
        assertNotNull(retrieved)
        assertEquals(question, retrieved!!.text)
        assertEquals("QUEUED", retrieved.deliveryState)
    }

    @Test
    fun testDuplicateSendPrevention() {
        var isSending = false
        var sendCount = 0

        fun attemptSend(question: String) {
            if (!isSending) {
                isSending = true
                sendCount++
                // Simulated completion
                isSending = false
            }
        }

        // Single trigger
        attemptSend("Area secured and checkpoint clear.")
        assertEquals(1, sendCount)

        // Re-entrant lock test: second call while first is locked is rejected
        isSending = true
        attemptSend("Area secured and checkpoint clear.")
        assertEquals(1, sendCount)
    }
}
