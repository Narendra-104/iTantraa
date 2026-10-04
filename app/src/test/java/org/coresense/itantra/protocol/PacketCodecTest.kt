package org.coresense.itantra.protocol

import org.junit.Assert.*
import org.junit.Test

class PacketCodecTest {

    @Test
    fun testEncodeDecodeRoundTrip() {
        val original = Packet(
            msgId = 9876543210L,
            senderId = "ALPHA-NODE-01",
            seq = 105,
            lang = Language.HINDI,
            priority = PacketPriority.SOS,
            text = "आपातकालीन स्थिति है तुरंत सहायता भेजें",
            timestamp = 1718000000000L,
            latitudeMicrodegrees = 28567200,   // 28.5672° N
            longitudeMicrodegrees = 77210000,  // 77.2100° E
            type = PacketType.DATA
        )

        val encoded = PacketCodec.encode(original)
        assertNotNull(encoded)
        assertTrue("Encoded packet should be compact (< 250 bytes, actual: ${encoded.size})", encoded.size < 250)

        val decodeResult = PacketCodec.decode(encoded)
        assertTrue("Decode must succeed: ${decodeResult.exceptionOrNull()?.message}", decodeResult.isSuccess)

        val decoded = decodeResult.getOrThrow()
        assertEquals(original.msgId, decoded.msgId)
        assertEquals(original.senderId, decoded.senderId)
        assertEquals(original.seq, decoded.seq)
        assertEquals(original.lang, decoded.lang)
        assertEquals(original.priority, decoded.priority)
        assertEquals(original.text, decoded.text)
        assertEquals(original.timestamp, decoded.timestamp)
        assertEquals(original.latitudeMicrodegrees, decoded.latitudeMicrodegrees)
        assertEquals(original.longitudeMicrodegrees, decoded.longitudeMicrodegrees)
        assertEquals(original.type, decoded.type)
    }

    @Test
    fun testNegativeCoordinatesZigzag() {
        val original = Packet(
            msgId = 111L,
            senderId = "BRAVO",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Southern hemisphere coordinate test",
            timestamp = 1700000000L,
            latitudeMicrodegrees = -33868800,  // -33.8688° S
            longitudeMicrodegrees = 151209300, // 151.2093° E
            type = PacketType.DATA
        )

        val encoded = PacketCodec.encode(original)
        val decoded = PacketCodec.decode(encoded).getOrThrow()

        assertEquals(-33868800, decoded.latitudeMicrodegrees)
        assertEquals(151209300, decoded.longitudeMicrodegrees)
    }

    @Test
    fun testCorruptedCrcDropsPacket() {
        val original = Packet(
            msgId = 999L,
            senderId = "TEST",
            seq = 5,
            lang = Language.TAMIL,
            priority = PacketPriority.NORMAL,
            text = "வணக்கம்",
            timestamp = 1700000000L,
            type = PacketType.DATA
        )

        val encoded = PacketCodec.encode(original)
        // Corrupt one byte in the payload
        if (encoded.size > 5) {
            encoded[3] = (encoded[3].toInt() xor 0xFF).toByte()
        }

        val result = PacketCodec.decode(encoded)
        assertTrue("Corrupted payload must fail CRC or decode", result.isFailure)
    }
}
