package org.coresense.itantra.data.repository

import kotlinx.coroutines.flow.Flow
import org.coresense.itantra.storage.MessageDao
import org.coresense.itantra.storage.MessageEntity

import org.coresense.itantra.storage.ConversationDao
import org.coresense.itantra.storage.ConversationEntity

class MessageRepository(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao? = null
) {
    fun getAllMessages(): Flow<List<MessageEntity>> = messageDao.getAllMessages()

    fun getConversationMessages(conversationId: String): Flow<List<MessageEntity>> = messageDao.getConversationMessages(conversationId)

    suspend fun getMessageById(msgId: Long): MessageEntity? = messageDao.getMessageById(msgId)

    fun getMessagesForDepartment(departmentId: String): Flow<List<MessageEntity>> = messageDao.getMessagesForDepartment(departmentId)

    fun getAllConversations(): Flow<List<ConversationEntity>>? = conversationDao?.getAllConversations()

    suspend fun insertMessage(message: MessageEntity) {
        messageDao.insertMessage(message)
    }

    suspend fun insertConversation(conversation: ConversationEntity) {
        conversationDao?.insertConversation(conversation)
    }

    suspend fun updateDeliveryState(messageId: Long, state: String, rtt: Long) {
        messageDao.updateDeliveryState(messageId, state, rtt)
    }
}
