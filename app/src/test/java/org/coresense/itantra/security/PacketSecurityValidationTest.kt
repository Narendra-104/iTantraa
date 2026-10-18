package org.coresense.itantra.security

import org.coresense.itantra.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PacketSecurityValidationTest {

    @Test
    fun testValidPacketEncodesAndDecodesSuccessfully() {
        val packet = Packet(
            msgId = 17001L,
            senderId = "IT-OPERATOR-SEC-01",
            receiverId = "IT-OPERATOR-SEC-02",
            departmentId = "POLICE",
            conversationId = "conv_sec",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Security patrol checkpoint verified",
            timestamp = System.currentTimeMillis(),
            latitudeMicrodegrees = 28567200,
            longitudeMicrodegrees = 77210000,
            type = PacketType.DATA
        )

        val encoded = PacketCodec.encode(packet)
        val decoded = PacketCodec.decode(encoded).getOrThrow()

        assertEquals(packet.msgId, decoded.msgId)
        assertEquals(packet.senderId, decoded.senderId)
        assertEquals(packet.text, decoded.text)
        assertEquals(packet.latitudeMicrodegrees, decoded.latitudeMicrodegrees)
        assertEquals(packet.longitudeMicrodegrees, decoded.longitudeMicrodegrees)
    }

    @Test
    fun testCoordinateBoundsSanitization() {
        // Test latitude exceeding 90 degrees (90,000,000 microdegrees)
        val invalidLatPacket = Packet(
            msgId = 17002L,
            senderId = "IT-TEST-01",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Invalid location test",
            timestamp = System.currentTimeMillis(),
            latitudeMicrodegrees = 150_000_000, // 150 degrees (impossible)
            longitudeMicrodegrees = 77_000_000,
            type = PacketType.LOCATION
        )

        val encoded = PacketCodec.encode(invalidLatPacket)
        val decoded = PacketCodec.decode(encoded).getOrThrow()

        // Decoded latitude must be sanitized to null because 150 deg is invalid
        assertNull("Invalid latitude > 90 deg must be sanitized to null", decoded.latitudeMicrodegrees)
        assertEquals(77_000_000, decoded.longitudeMicrodegrees)
    }

    @Test
    fun testCrc32TamperDetection() {
        val packet = Packet(
            msgId = 17003L,
            senderId = "IT-ORIGINAL",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Original untampered command",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        val encoded = PacketCodec.encode(packet).copyOf()

        // Tamper with one byte in the middle of payload
        val tamperIndex = encoded.size / 2
        encoded[tamperIndex] = (encoded[tamperIndex].toInt() xor 0xFF).toByte()

        val result = PacketCodec.decode(encoded)
        assertTrue("Tampered packet with corrupted bytes must fail CRC32 or decompression", result.isFailure)
    }

    @Test
    fun testMalformedFieldLengthSafetyCeiling() {
        // Construct a raw protobuf packet with field length claiming 10,000,000 bytes
        val out = ByteArrayOutputStream()
        out.write(0) // 0x00 = FLAG_RAW compression header
        // Tag 2 (sender_id), wire type 2 (length-delimited) = (2 shl 3) or 2 = 18
        out.write(18)
        // Varint for 8,388,608 bytes > 65,536 limit
        out.write(0x80)
        out.write(0x80)
        out.write(0x80)
        out.write(0x04)

        val result = PacketCodec.decode(out.toByteArray())
        assertTrue("Malformed length exceeding safety limit must be rejected without OOM", result.isFailure)
    }

    @Test
    fun testControlCharacterInputSanitization() {
        val rawInput = "Commander\u0000\u0007\u001B\nAlpha"
        val sanitized = rawInput.trim().filter { !it.isISOControl() }.take(64)
        assertEquals("CommanderAlpha", sanitized)
        assertFalse(sanitized.any { it.isISOControl() })
    }
}
