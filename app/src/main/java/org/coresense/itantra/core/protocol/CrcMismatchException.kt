package org.coresense.itantra.core.protocol

class CrcMismatchException(
    val partialPacket: Packet,
    message: String
) : Exception(message)
