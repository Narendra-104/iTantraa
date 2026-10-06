package org.coresense.itantra.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val msgId: Long,
    val senderId: String,
    val seq: Int,
    val lang: String,
    val priority: String,
    val text: String,
    val receiverId: String? = null,
    val departmentId: String? = null,
    val conversationId: String? = null,
    val timestamp: Long,
    val latitudeMicrodegrees: Int?,
    val longitudeMicrodegrees: Int?,
    val deliveryState: String,
    val rttMs: Long = 0L,
    val isIncoming: Boolean = false
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE priority = 'SOS' ORDER BY timestamp DESC")
    fun getEmergencyMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getConversationMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE msgId = :msgId LIMIT 1")
    suspend fun getMessageById(msgId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE departmentId = :departmentId ORDER BY timestamp ASC")
    fun getMessagesForDepartment(departmentId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET deliveryState = :deliveryState, rttMs = :rttMs WHERE msgId = :msgId")
    suspend fun updateDeliveryState(msgId: Long, deliveryState: String, rttMs: Long)

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}
