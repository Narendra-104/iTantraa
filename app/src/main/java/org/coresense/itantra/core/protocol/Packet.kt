package org.coresense.itantra.core.protocol

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
    val accuracyMeters: Float? = null,
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

    /**
     * Strict geographical coordinate validation according to Step 12 rules:
     * - Latitude between -90.0 and +90.0 degrees
     * - Longitude between -180.0 and +180.0 degrees
     * - Reject uninitialized (0.0, 0.0) default coordinates
     */
    fun hasValidCoordinates(): Boolean {
        val lat = latitudeDegrees ?: return false
        val lon = longitudeDegrees ?: return false
        if (lat < -90.0 || lat > 90.0) return false
        if (lon < -180.0 || lon > 180.0) return false
        if (lat == 0.0 && lon == 0.0) return false
        return true
    }
}
