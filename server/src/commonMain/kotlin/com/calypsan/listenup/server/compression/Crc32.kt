package com.calypsan.listenup.server.compression

/**
 * IEEE CRC-32 (polynomial 0xEDB88320, reflected), the checksum DEFLATE/ZIP entries carry.
 *
 * Mirrors `java.util.zip.CRC32`: feed bytes via [update]; read the running checksum from [value]
 * as a 32-bit unsigned value held in a [Long] (`0..0xFFFFFFFF`). Pure commonMain.
 */
class Crc32 {
    private var crc = 0xFFFFFFFFuL

    /** Folds the low 8 bits of [byte] into the running checksum. */
    fun update(byte: Int) {
        crc = TABLE[((crc xor byte.toULong()) and 0xFFuL).toInt()] xor (crc shr 8)
    }

    /** Folds [length] bytes of [bytes] starting at [offset] into the running checksum. */
    fun update(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ) {
        var c = crc
        for (i in offset until offset + length) {
            c = TABLE[((c xor bytes[i].toULong()) and 0xFFuL).toInt()] xor (c shr 8)
        }
        crc = c
    }

    /** The running checksum as an unsigned 32-bit value in `0..0xFFFFFFFF`. */
    val value: Long get() = (crc xor 0xFFFFFFFFuL).toLong()

    /** Resets to the initial state (checksum of the empty input). */
    fun reset() {
        crc = 0xFFFFFFFFuL
    }

    private companion object {
        val TABLE =
            ULongArray(256) { n ->
                var c = n.toULong()
                repeat(8) { c = if (c and 1uL != 0uL) 0xEDB88320uL xor (c shr 1) else c shr 1 }
                c
            }
    }
}
