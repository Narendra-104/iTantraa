package org.coresense.itantra.protocol

enum class PacketType(val value: Int) {
    DATA(0),
    ACK(1),
    NACK(2),
    PING(3),
    PONG(4),
    SOS(5);

    companion object {
        fun fromValue(value: Int): PacketType {
            return entries.firstOrNull { it.value == value } ?: DATA
        }
    }
}
