package com.calypsan.listenup.server.transcode

/**
 * Builds syntactically valid ADTS streams without needing an encoder.
 *
 * Tests that pre-write a cached segment need bytes the frame counter will accept, because the serve
 * path now verifies that a segment holds the number of frames the playlist promised — arbitrary
 * filler would be refused, correctly, for the wrong reason.
 */
object AdtsFixtures {
    /** Fixed ADTS header size, with no CRC. */
    const val HEADER_BYTES = 7

    /**
     * One syntactically valid ADTS packet: `0xFFF` syncword, MPEG-4 AAC-LC, no CRC, a declared
     * length covering header plus [payload], and [rawDataBlocks] blocks inside it.
     */
    fun frame(
        payload: Int = 100,
        rawDataBlocks: Int = 1,
    ): ByteArray {
        val length = HEADER_BYTES + payload
        val frame = ByteArray(length)
        frame[0] = 0xFF.toByte()
        // 0xF1: syncword low nibble, MPEG-4, layer 00, protection_absent = 1 (no CRC).
        frame[1] = 0xF1.toByte()
        // profile AAC-LC, 44.1 kHz sampling index (4), stereo channel config high bit.
        frame[2] = 0x50.toByte()
        frame[3] = (0x80 or (length shr 11 and 0x03)).toByte()
        frame[4] = (length shr 3 and 0xFF).toByte()
        frame[5] = ((length and 0x07) shl 5 or 0x1F).toByte()
        frame[6] = (rawDataBlocks - 1 and 0x03).toByte()
        return frame
    }

    /** [count] concatenated frames — a stand-in for a segment holding exactly that much audio. */
    fun stream(count: Int): ByteArray {
        val one = frame()
        val out = ByteArray(one.size * count)
        for (i in 0 until count) one.copyInto(out, i * one.size)
        return out
    }
}
