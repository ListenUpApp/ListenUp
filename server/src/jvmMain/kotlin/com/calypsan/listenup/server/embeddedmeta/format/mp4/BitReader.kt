package com.calypsan.listenup.server.embeddedmeta.format.mp4

/** Minimal MSB-first bit reader over a byte array, for AudioSpecificConfig decoding. */
internal class BitReader(private val bytes: ByteArray) {
    private var bitPos = 0

    /** Read [count] bits (0..32) MSB-first as an unsigned Int. Returns 0 past EOF. */
    fun readBits(count: Int): Int {
        var result = 0
        repeat(count) {
            val byteIndex = bitPos ushr 3
            val bit =
                if (byteIndex >= bytes.size) {
                    0
                } else {
                    val shift = 7 - (bitPos and 7)
                    (bytes[byteIndex].toInt() ushr shift) and 1
                }
            result = (result shl 1) or bit
            bitPos++
        }
        return result
    }
}
