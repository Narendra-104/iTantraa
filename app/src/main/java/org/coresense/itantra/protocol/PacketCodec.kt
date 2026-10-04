package org.coresense.itantra.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Standard Protocol Buffers binary wire-format encoder and decoder for [Packet].
 * Interoperable with standard protobuf-lite definition in i_tantra.proto.
 *
 * Wire Types:
 * 0 = Varint
 * 2 = Length-delimited (string / bytes)
 * 5 = 32-bit fixed
 */
object PacketCodec {

    // Field tags
    private const val TAG_MSG_ID = 1
    private const val TAG_SENDER_ID = 2
    private const val TAG_SEQ = 3
    private const val TAG_LANG = 4
    private const val TAG_PRIORITY = 5
    private const val TAG_TEXT = 6
    private const val TAG_TIMESTAMP = 7
    private const val TAG_LAT = 8
    private const val TAG_LON = 9
    private const val TAG_TYPE = 10
    private const val TAG_CRC32 = 11

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    fun encode(packet: Packet): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. msg_id (varint)
        writeTag(out, TAG_MSG_ID, WIRE_VARINT)
        writeVarint64(out, packet.msgId)

        // 2. sender_id (string)
        val senderBytes = packet.senderId.toByteArray(Charsets.UTF_8)
        writeTag(out, TAG_SENDER_ID, WIRE_LENGTH_DELIMITED)
        writeVarint32(out, senderBytes.size)
        out.write(senderBytes)

        // 3. seq (varint)
        writeTag(out, TAG_SEQ, WIRE_VARINT)
        writeVarint32(out, packet.seq)

        // 4. lang (enum varint)
        writeTag(out, TAG_LANG, WIRE_VARINT)
        writeVarint32(out, packet.lang.ordinal)

        // 5. priority (enum varint)
        writeTag(out, TAG_PRIORITY, WIRE_VARINT)
        writeVarint32(out, packet.priority.value)

        // 6. text (string)
        val textBytes = packet.text.toByteArray(Charsets.UTF_8)
        writeTag(out, TAG_TEXT, WIRE_LENGTH_DELIMITED)
        writeVarint32(out, textBytes.size)
        out.write(textBytes)

        // 7. timestamp (varint)
        writeTag(out, TAG_TIMESTAMP, WIRE_VARINT)
        writeVarint64(out, packet.timestamp)

        // 8. lat (sint32 zigzag varint)
        packet.latitudeMicrodegrees?.let { lat ->
            writeTag(out, TAG_LAT, WIRE_VARINT)
            writeVarint32(out, encodeZigZag32(lat))
        }

        // 9. lon (sint32 zigzag varint)
        packet.longitudeMicrodegrees?.let { lon ->
            writeTag(out, TAG_LON, WIRE_VARINT)
            writeVarint32(out, encodeZigZag32(lon))
        }

        // 10. type (enum varint)
        writeTag(out, TAG_TYPE, WIRE_VARINT)
        writeVarint32(out, packet.type.value)

        val rawBytesWithoutCrc = out.toByteArray()
        val calculatedCrc = Crc32Util.calculate(rawBytesWithoutCrc)

        // 11. crc32 (fixed 32-bit little endian)
        writeTag(out, TAG_CRC32, WIRE_FIXED32)
        val crcBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(calculatedCrc.toInt())
        out.write(crcBuf.array())

        val uncompressed = out.toByteArray()
        return CompressionUtil.compressIfNeeded(uncompressed)
    }

    fun decode(framedData: ByteArray): Result<Packet> {
        return try {
            val data = CompressionUtil.decompressIfNeeded(framedData)
            val stream = ByteArrayInputStream(data)

            var msgId: Long = 0L
            var senderId: String = ""
            var seq: Int = 0
            var lang: Language = Language.ENGLISH
            var priority: PacketPriority = PacketPriority.NORMAL
            var text: String = ""
            var timestamp: Long = 0L
            var lat: Int? = null
            var lon: Int? = null
            var type: PacketType = PacketType.DATA
            var crc32: Long = 0L

            var crcOffset = -1

            while (stream.available() > 0) {
                val tagAndWire = readVarint32(stream) ?: break
                val fieldTag = tagAndWire ushr 3
                val wireType = tagAndWire and 0x07

                when (fieldTag) {
                    TAG_MSG_ID -> msgId = readVarint64(stream)
                    TAG_SENDER_ID -> {
                        val len = readVarint32(stream) ?: 0
                        val buf = ByteArray(len)
                        stream.read(buf)
                        senderId = String(buf, Charsets.UTF_8)
                    }
                    TAG_SEQ -> seq = readVarint32(stream) ?: 0
                    TAG_LANG -> {
                        val ordinal = readVarint32(stream) ?: 0
                        lang = Language.fromOrdinal(ordinal)
                    }
                    TAG_PRIORITY -> {
                        val pVal = readVarint32(stream) ?: 0
                        priority = PacketPriority.fromValue(pVal)
                    }
                    TAG_TEXT -> {
                        val len = readVarint32(stream) ?: 0
                        val buf = ByteArray(len)
                        stream.read(buf)
                        text = String(buf, Charsets.UTF_8)
                    }
                    TAG_TIMESTAMP -> timestamp = readVarint64(stream)
                    TAG_LAT -> lat = decodeZigZag32(readVarint32(stream) ?: 0)
                    TAG_LON -> lon = decodeZigZag32(readVarint32(stream) ?: 0)
                    TAG_TYPE -> {
                        val tVal = readVarint32(stream) ?: 0
                        type = PacketType.fromValue(tVal)
                    }
                    TAG_CRC32 -> {
                        // Fixed 32
                        crcOffset = data.size - stream.available() - 1 // tag byte already consumed
                        val b = ByteArray(4)
                        stream.read(b)
                        crc32 = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    }
                    else -> skipField(stream, wireType)
                }
            }

            // Verify CRC32 on bytes before the CRC tag if tag was present
            if (crcOffset > 0) {
                val expectedCrc = Crc32Util.calculate(data, 0, crcOffset)
                if (expectedCrc != crc32) {
                    return Result.failure(IllegalStateException("CRC32 mismatch: calculated $expectedCrc, received $crc32"))
                }
            }

            Result.success(
                Packet(
                    msgId = msgId,
                    senderId = senderId,
                    seq = seq,
                    lang = lang,
                    priority = priority,
                    text = text,
                    timestamp = timestamp,
                    latitudeMicrodegrees = lat,
                    longitudeMicrodegrees = lon,
                    type = type,
                    crc32 = crc32
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun writeTag(out: ByteArrayOutputStream, tag: Int, wireType: Int) {
        writeVarint32(out, (tag shl 3) or wireType)
    }

    private fun writeVarint32(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    private fun writeVarint64(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
    }

    private fun readVarint32(stream: InputStream): Int? {
        var result = 0
        var shift = 0
        while (shift < 32) {
            val b = stream.read()
            if (b == -1) return if (shift == 0) null else result
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        return result
    }

    private fun readVarint64(stream: InputStream): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = stream.read()
            if (b == -1) return result
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        return result
    }

    private fun encodeZigZag32(n: Int): Int = (n shl 1) xor (n shr 31)
    private fun decodeZigZag32(n: Int): Int = (n ushr 1) xor -(n and 1)

    private fun skipField(stream: InputStream, wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint64(stream)
            WIRE_FIXED32 -> stream.skip(4)
            WIRE_LENGTH_DELIMITED -> {
                val len = readVarint32(stream) ?: 0
                stream.skip(len.toLong())
            }
            1 -> stream.skip(8) // Fixed 64
        }
    }
}
