package com.calypsan.listenup.server.transcode

/**
 * Counts AAC frames in a raw ADTS stream by walking its headers — no decoding, no external process.
 *
 * ⛔ **This exists because FFmpeg can fail while reporting success.** Handed an xHE-AAC source it
 * cannot fully decode, FFmpeg drops the packets it does not understand, writes short segments, and
 * **exits 0** — measured on a real library file at 22.8% of the audio missing across 14,464 refused
 * packets. Nothing in an exit code, and nothing in the presence of an output file, distinguishes
 * that from a good encode. The frames actually present do.
 *
 * A frame is a fixed 1024 samples, so a count converts to an exact duration and can be compared
 * against what [HlsPlaylist] promised the player. Header walking costs a single pass over bytes the
 * server is about to send anyway.
 */
object AdtsFrames {
    /** Fixed part of an ADTS header. A CRC adds two more, which `aac_frame_length` already covers. */
    private const val HEADER_BYTES = 7

    /** Byte 1 carries the syncword's low nibble; bytes 3-6 carry the length and block count. */
    private const val OFFSET_SYNC_LOW = 1
    private const val OFFSET_LENGTH_HIGH = 3
    private const val OFFSET_LENGTH_MID = 4
    private const val OFFSET_LENGTH_LOW = 5
    private const val OFFSET_BLOCK_COUNT = 6

    private const val BYTE_MASK = 0xFF

    /** The 12-bit syncword is `0xFFF`: all of byte 0, then the high nibble of byte 1. */
    private const val SYNC_BYTE = 0xFF
    private const val SYNC_NIBBLE_MASK = 0xF0

    /** `aac_frame_length` is 13 bits: 2 low bits of byte 3, all of byte 4, 3 high bits of byte 5. */
    private const val LENGTH_HIGH_MASK = 0x03
    private const val LENGTH_HIGH_SHIFT = 11
    private const val LENGTH_MID_SHIFT = 3
    private const val LENGTH_LOW_SHIFT = 5

    /** `number_of_raw_data_blocks_in_frame`, stored as blocks-minus-one in the low 2 bits of byte 6. */
    private const val BLOCK_COUNT_MASK = 0x03

    /**
     * Number of AAC frames in [bytes], or **null** when they are not a complete, well-formed ADTS
     * stream — a truncated tail, a missing syncword, or a frame whose declared length is impossible.
     *
     * Null is deliberately not zero: a segment that cannot be parsed must never be mistaken for a
     * segment that legitimately holds no audio.
     */
    fun countFrames(bytes: ByteArray): Int? {
        var offset = 0
        var frames = 0
        while (offset < bytes.size) {
            if (offset + HEADER_BYTES > bytes.size) return null
            if (!hasSyncword(bytes, offset)) return null
            val length = frameLength(bytes, offset)
            if (length < HEADER_BYTES || offset + length > bytes.size) return null
            frames += (bytes[offset + OFFSET_BLOCK_COUNT].toInt() and BLOCK_COUNT_MASK) + 1
            offset += length
        }
        return frames
    }

    private fun hasSyncword(
        bytes: ByteArray,
        offset: Int,
    ): Boolean =
        bytes[offset].toInt() and BYTE_MASK == SYNC_BYTE &&
            bytes[offset + OFFSET_SYNC_LOW].toInt() and SYNC_NIBBLE_MASK == SYNC_NIBBLE_MASK

    private fun frameLength(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset + OFFSET_LENGTH_HIGH].toInt() and LENGTH_HIGH_MASK) shl LENGTH_HIGH_SHIFT) or
            ((bytes[offset + OFFSET_LENGTH_MID].toInt() and BYTE_MASK) shl LENGTH_MID_SHIFT) or
            ((bytes[offset + OFFSET_LENGTH_LOW].toInt() and BYTE_MASK) ushr LENGTH_LOW_SHIFT)
}
