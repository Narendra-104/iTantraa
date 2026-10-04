package org.coresense.itantra.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

object CompressionUtil {
    private const val FLAG_RAW: Byte = 0x00
    private const val FLAG_DEFLATED: Byte = 0x01

    /**
     * Compresses [payload] with Deflate only if compressed size + 1 flag byte < original size.
     * Always prefixes a 1-byte compression header flag.
     */
    fun compressIfNeeded(payload: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(payload)
        deflater.finish()

        val buffer = ByteArray(payload.size + 32)
        val compressedBytes = deflater.deflate(buffer)
        deflater.end()

        // Only compress if it genuinely saves bytes (including flag byte)
        return if (compressedBytes > 0 && (compressedBytes + 1) < payload.size) {
            val result = ByteArray(compressedBytes + 1)
            result[0] = FLAG_DEFLATED
            System.arraycopy(buffer, 0, result, 1, compressedBytes)
            result
        } else {
            val result = ByteArray(payload.size + 1)
            result[0] = FLAG_RAW
            System.arraycopy(payload, 0, result, 1, payload.size)
            result
        }
    }

    /**
     * Decompresses framed payload checking the 1-byte compression flag.
     */
    fun decompressIfNeeded(framedData: ByteArray): ByteArray {
        if (framedData.isEmpty()) return ByteArray(0)
        val flag = framedData[0]
        val data = framedData.copyOfRange(1, framedData.size)

        return when (flag) {
            FLAG_RAW -> data
            FLAG_DEFLATED -> {
                val inflater = Inflater()
                inflater.setInput(data)
                val outputStream = ByteArrayOutputStream(data.size * 2)
                val buffer = ByteArray(256)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0 && inflater.needsInput()) break
                    outputStream.write(buffer, 0, count)
                }
                inflater.end()
                outputStream.toByteArray()
            }
            else -> data // Fallback to raw if unknown flag
        }
    }
}
