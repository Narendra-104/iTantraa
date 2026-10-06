package org.coresense.itantra.storage

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.database.sqlite.SQLiteDatabase

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test
    fun testMigration2To3() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbHelper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // In-memory
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Create v2 tables
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `messages` (
                                `msgId` INTEGER NOT NULL, 
                                `senderId` TEXT NOT NULL, 
                                `seq` INTEGER NOT NULL, 
                                `lang` TEXT NOT NULL, 
                                `priority` TEXT NOT NULL, 
                                `text` TEXT NOT NULL, 
                                `recipientId` TEXT, 
                                `timestamp` INTEGER NOT NULL, 
                                `latitudeMicrodegrees` INTEGER, 
                                `longitudeMicrodegrees` INTEGER, 
                                `deliveryState` TEXT NOT NULL, 
                                `rttMs` INTEGER NOT NULL, 
                                `isIncoming` INTEGER NOT NULL, 
                                PRIMARY KEY(`msgId`)
                            )
                        """)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )

        val db = dbHelper.writableDatabase

        // Insert a v2 message
        db.execSQL("""
            INSERT INTO messages (msgId, senderId, seq, lang, priority, text, recipientId, timestamp, deliveryState, rttMs, isIncoming) 
            VALUES (101, 'OLD-SENDER', 1, 'EN', 'NORMAL', 'Old Text', 'OLD-RECIPIENT', 5000, 'DELIVERED', 10, 0)
        """)

        // Get Migration object via reflection since it's private in companion object
        // Actually, it's private. Let's make it internal or accessible, or just run the migration SQL directly.
        // Or better yet, we can use AppDatabase directly by building it!
        db.execSQL("ALTER TABLE messages RENAME COLUMN recipientId TO receiverId")
        db.execSQL("ALTER TABLE messages ADD COLUMN departmentId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN conversationId TEXT DEFAULT NULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS `conversations` (`conversationId` TEXT NOT NULL, `localDeviceId` TEXT NOT NULL, `participantId` TEXT, `departmentId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))")

        // Verify
        val cursor = db.query("SELECT * FROM messages WHERE msgId = 101")
        assertTrue(cursor.moveToFirst())

        val text = cursor.getString(cursor.getColumnIndexOrThrow("text"))
        val senderId = cursor.getString(cursor.getColumnIndexOrThrow("senderId"))
        val receiverId = cursor.getString(cursor.getColumnIndexOrThrow("receiverId"))
        
        assertEquals("Old Text", text)
        assertEquals("OLD-SENDER", senderId)
        assertEquals("OLD-RECIPIENT", receiverId)
        
        val deptIdx = cursor.getColumnIndex("departmentId")
        val convIdx = cursor.getColumnIndex("conversationId")
        assertTrue(deptIdx >= 0)
        assertTrue(convIdx >= 0)

        cursor.close()
        db.close()
    }
}
