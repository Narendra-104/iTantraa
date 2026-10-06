package org.coresense.itantra.protocol

class CrcMismatchException(
    val partialPacket: Packet,
    message: String
) : Exception(message)
