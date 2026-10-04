package org.coresense.itantra.protocol

enum class PacketPriority(val value: Int) {
    NORMAL(0),
    URGENT(1),
    SOS(2);

    companion object {
        fun fromValue(value: Int): PacketPriority {
            return entries.firstOrNull { it.value == value } ?: NORMAL
        }
    }
}
