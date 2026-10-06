package org.coresense.itantra.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, MetricSampleEntity::class, ConversationEntity::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun metricSampleDao(): MetricSampleDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rename recipientId to receiverId
                db.execSQL("ALTER TABLE messages RENAME COLUMN recipientId TO receiverId")
                // Add new columns to messages
                db.execSQL("ALTER TABLE messages ADD COLUMN departmentId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN conversationId TEXT DEFAULT NULL")
                
                // Create conversations table
                db.execSQL("CREATE TABLE IF NOT EXISTS `conversations` (`conversationId` TEXT NOT NULL, `localDeviceId` TEXT NOT NULL, `participantId` TEXT, `departmentId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "itantra_database.db"
                ).addMigrations(MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
