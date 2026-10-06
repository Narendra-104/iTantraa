package org.coresense.itantra.protocol

/**
 * iTantra Protocol Packet data structure.
 *
 * Typical payload: ~40-80 bytes.
 * Microdegrees: degrees * 1_000_000 (e.g. 28.567200 -> 28567200)
 */
data class Packet(
    val msgId: Long,
    val senderId: String,
    val receiverId: String? = null,
    val departmentId: String? = null,
    val conversationId: String? = null,
    val seq: Int,
    val lang: Language,
    val priority: PacketPriority,
    val text: String,
    val timestamp: Long,
    val latitudeMicrodegrees: Int? = null,
    val longitudeMicrodegrees: Int? = null,
    val type: PacketType = PacketType.DATA,
    val crc32: Long = 0L
) {
    val isEmergency: Boolean
        get() = priority == PacketPriority.SOS || type == PacketType.SOS

    val hasLocation: Boolean
        get() = latitudeMicrodegrees != null && longitudeMicrodegrees != null

    val latitudeDegrees: Double?
        get() = latitudeMicrodegrees?.let { it.toDouble() / 1_000_000.0 }

    val longitudeDegrees: Double?
        get() = longitudeMicrodegrees?.let { it.toDouble() / 1_000_000.0 }
}
