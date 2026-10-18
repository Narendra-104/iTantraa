package org.coresense.itantra.notifications

import org.coresense.itantra.protocol.Language
import org.coresense.itantra.protocol.Packet
import org.coresense.itantra.protocol.PacketPriority
import org.coresense.itantra.protocol.PacketType
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class NotificationRoutingTest {

    @Test
    fun testNotificationDeduplicationPreventsSpam() {
        val notifiedMessageIds = ConcurrentHashMap.newKeySet<Long>()
        var notificationTriggerCount = 0

        fun processPacketNotification(packet: Packet) {
            // Deduplicate per msgId
            if (notifiedMessageIds.add(packet.msgId)) {
                notificationTriggerCount++
            }
        }

        val packet1 = Packet(
            msgId = 15001L,
            senderId = "IT-SENDER-01",
            receiverId = "IT-RECEIVER-01",
            conversationId = "conv_1",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Status update: team en route",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        // 1st reception
        processPacketNotification(packet1)
        assertEquals(1, notificationTriggerCount)

        // Retransmission of same packet
        processPacketNotification(packet1)
        processPacketNotification(packet1)
        assertEquals("Duplicate packets must not re-trigger notifications", 1, notificationTriggerCount)

        // Distinct packet
        val packet2 = packet1.copy(msgId = 15002L, text = "New checkpoint reached")
        processPacketNotification(packet2)
        assertEquals(2, notificationTriggerCount)
    }

    @Test
    fun testNotificationTitleFormatting() {
        fun buildTitle(packet: Packet): String {
            return when {
                packet.priority == PacketPriority.SOS || packet.type == PacketType.SOS -> "🚨 EMERGENCY SOS FROM ${packet.senderId}"
                !packet.departmentId.isNullOrBlank() -> "[${packet.departmentId}] from ${packet.senderId}"
                !packet.receiverId.isNullOrBlank() && packet.receiverId != "GLOBAL" -> "Direct Message from ${packet.senderId}"
                else -> "Broadcast from ${packet.senderId}"
            }
        }

        val deptPacket = Packet(
            msgId = 15003L,
            senderId = "IT-MED-01",
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = "conv_dept",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Ambulance standing by",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )
        assertEquals("[MEDICAL] from IT-MED-01", buildTitle(deptPacket))

        val directPacket = Packet(
            msgId = 15004L,
            senderId = "IT-DISPATCH",
            receiverId = "IT-FIELD-02",
            departmentId = null,
            conversationId = "conv_direct",
            seq = 2,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Direct instructions",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )
        assertEquals("Direct Message from IT-DISPATCH", buildTitle(directPacket))

        val broadcastPacket = Packet(
            msgId = 15005L,
            senderId = "IT-LEADER",
            receiverId = "GLOBAL",
            departmentId = null,
            conversationId = "conv_global",
            seq = 3,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Attention all units",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )
        assertEquals("Broadcast from IT-LEADER", buildTitle(broadcastPacket))
    }

    @Test
    fun testContentPrivacyTruncation() {
        val longText = "A".repeat(500)
        val safeText = longText.trim().take(120)
        assertEquals(120, safeText.length)
        assertTrue(safeText.length <= 120)
    }
}
