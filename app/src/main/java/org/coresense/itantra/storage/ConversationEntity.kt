package org.coresense.itantra.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val localDeviceId: String,
    val participantId: String? = null,
    val departmentId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)
    
    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE conversationId = :conversationId")
    suspend fun updateConversationTime(conversationId: String, updatedAt: Long)
}
