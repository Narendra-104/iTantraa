package org.coresense.itantra.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, MetricSampleEntity::class, ConversationEntity::class, UserProfileEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun metricSampleDao(): MetricSampleDao
    abstract fun conversationDao(): ConversationDao
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN recipientId TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN receiverId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN departmentId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN conversationId TEXT DEFAULT NULL")
                db.execSQL("CREATE TABLE IF NOT EXISTS `conversations` (`conversationId` TEXT NOT NULL, `localDeviceId` TEXT NOT NULL, `participantId` TEXT, `departmentId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))")
            }
        }

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_profiles` (
                        `userId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `aadhar` TEXT NOT NULL DEFAULT '',
                        `email` TEXT NOT NULL DEFAULT '',
                        `mobileNumber` TEXT NOT NULL DEFAULT '',
                        `address` TEXT NOT NULL DEFAULT '',
                        `birthDate` TEXT NOT NULL DEFAULT '',
                        `bloodGroup` TEXT NOT NULL DEFAULT 'Unknown',
                        `gender` TEXT NOT NULL DEFAULT '',
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`userId`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "itantra_database.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_1_3, MIGRATION_2_3, MIGRATION_3_4).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
