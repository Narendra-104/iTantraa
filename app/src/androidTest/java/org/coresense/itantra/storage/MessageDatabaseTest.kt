package org.coresense.itantra.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.coresense.itantra.data.repository.MessageRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var repository: MessageRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context, AppDatabase::class.java
        ).allowMainThreadQueries().build()
        messageDao = db.messageDao()
        conversationDao = db.conversationDao()
        repository = MessageRepository(messageDao, conversationDao)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun test1And2_insertAndReadRoutedMessage() = runBlocking {
        val msg = MessageEntity(
            msgId = 100L,
            senderId = "SENDER-1",
            seq = 1,
            lang = "ENGLISH",
            priority = "NORMAL",
            text = "Hello!",
            receiverId = "RECEIVER-2",
            departmentId = "MEDICAL",
            conversationId = "CONV-A",
            timestamp = 1000L,
            latitudeMicrodegrees = null,
            longitudeMicrodegrees = null,
            deliveryState = "PENDING"
        )
        
        repository.insertMessage(msg)
        
        val retrieved = repository.getMessageById(100L)
        assertNotNull(retrieved)
        assertEquals("SENDER-1", retrieved?.senderId)
        assertEquals("RECEIVER-2", retrieved?.receiverId)
        assertEquals("MEDICAL", retrieved?.departmentId)
        assertEquals("CONV-A", retrieved?.conversationId)
    }

    @Test
    fun test3_multipleMessagesSameConversationOrder() = runBlocking {
        repository.insertMessage(MessageEntity(1L, "S1", 1, "EN", "NORMAL", "A", conversationId = "C1", timestamp = 2000L, latitudeMicrodegrees = null, longitudeMicrodegrees = null, deliveryState = "SENT"))
        repository.insertMessage(MessageEntity(2L, "S1", 2, "EN", "NORMAL", "B", conversationId = "C1", timestamp = 1000L, latitudeMicrodegrees = null, longitudeMicrodegrees = null, deliveryState = "SENT"))
        
        val messages = repository.getConversationMessages("C1").first()
        assertEquals(2, messages.size)
        assertEquals(2L, messages[0].msgId) // 1000L first due to ASC
        assertEquals(1L, messages[1].msgId) // 2000L second
    }

    @Test
    fun test4_updateDeliveryStatus() = runBlocking {
        val msg = MessageEntity(3L, "S1", 3, "EN", "NORMAL", "A", timestamp = 1000L, latitudeMicrodegrees = null, longitudeMicrodegrees = null, deliveryState = "PENDING")
        repository.insertMessage(msg)
        
        repository.updateDeliveryState(3L, "DELIVERED", 150L)
        
        val updated = repository.getMessageById(3L)
        assertEquals("DELIVERED", updated?.deliveryState)
        assertEquals(150L, updated?.rttMs)
    }

    @Test
    fun test7_repositoryMediatesAccess() = runBlocking {
        val conv = ConversationEntity("CONV-1", "DEV-1", "PART-2", null, 1000L, 1000L)
        repository.insertConversation(conv)
        
        val convs = repository.getAllConversations()?.first()
        assertNotNull(convs)
        assertEquals(1, convs?.size)
        assertEquals("CONV-1", convs?.get(0)?.conversationId)
    }
}
