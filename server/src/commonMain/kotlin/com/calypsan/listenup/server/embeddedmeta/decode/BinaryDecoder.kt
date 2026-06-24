package com.calypsan.listenup.server.embeddedmeta.decode

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Binary decoding helpers for audio metadata formats.
 *
 * Built on kotlinx-io [Buffer]. Provides the read patterns kotlinx-io itself
 * doesn't ship: 24-bit big-endian ints (MP3 frame lengths), sync-safe ints
 * (ID3v2 sizes — 4 bytes × 7 useful bits), UTF-16 with BOM auto-detection,
 * UTF-8 null-terminated strings (ID3v2 frame text fields), length-prefixed
 * Pascal-style strings (Nero MP4 chpl atoms).
 */

private const val BYTE_MASK = 0xFF
private const val SYNC_SAFE_MASK = 0x7F
private const val BITS_PER_BYTE = 8
private const val BITS_PER_SYNC_SAFE_BYTE = 7
private const val UTF16_BOM_BE_HI = 0xFE
private const val UTF16_BOM_BE_LO = 0xFF
private const val UTF16_BOM_LE_HI = 0xFF
private const val UTF16_BOM_LE_LO = 0xFE

/** Read 3 bytes as a big-endian unsigned integer. */
internal fun Buffer.readBeInt24(): Int {
    val b0 = readByte().toInt() and BYTE_MASK
    val b1 = readByte().toInt() and BYTE_MASK
    val b2 = readByte().toInt() and BYTE_MASK
    return (b0 shl (BITS_PER_BYTE * 2)) or (b1 shl BITS_PER_BYTE) or b2
}

/**
 * Read an ID3v2 sync-safe integer: 4 bytes, top bit of each always 0,
 * lower 7 bits contribute to a 28-bit value. Used for ID3v2 tag and frame sizes.
 */
internal fun Buffer.readSyncSafeInt(): Int {
    val b0 = readByte().toInt() and SYNC_SAFE_MASK
    val b1 = readByte().toInt() and SYNC_SAFE_MASK
    val b2 = readByte().toInt() and SYNC_SAFE_MASK
    val b3 = readByte().toInt() and SYNC_SAFE_MASK
    return (b0 shl (BITS_PER_SYNC_SAFE_BYTE * 3)) or
        (b1 shl (BITS_PER_SYNC_SAFE_BYTE * 2)) or
        (b2 shl BITS_PER_SYNC_SAFE_BYTE) or
        b3
}

/**
 * Read [byteCount] bytes interpreted as UTF-16 with a leading BOM
 * (`0xFEFF` big-endian or `0xFFFE` little-endian). If the BOM is absent,
 * defaults to UTF-16BE. Used for ID3v2 text frames with encoding byte 1.
 */
internal fun Buffer.readUtf16WithBom(byteCount: Int): String {
    require(byteCount >= 2) { "UTF-16 requires at least 2 bytes" }
    val raw = readByteArray(byteCount)
    val (offset, bigEndian) =
        when {
            raw.size >= 2 && (raw[0].toInt() and BYTE_MASK) == UTF16_BOM_BE_HI &&
                (raw[1].toInt() and BYTE_MASK) == UTF16_BOM_BE_LO -> 2 to true

            raw.size >= 2 && (raw[0].toInt() and BYTE_MASK) == UTF16_BOM_LE_HI &&
                (raw[1].toInt() and BYTE_MASK) == UTF16_BOM_LE_LO -> 2 to false

            else -> 0 to true
        }
    return TextDecoding.decodeUtf16(raw, offset, raw.size - offset, bigEndian = bigEndian)
}

/**
 * Read a UTF-8 string terminated by a null byte. Consumes the null byte.
 * Throws [kotlinx.io.EOFException] if EOF is hit before a null byte.
 */
internal fun Buffer.readUtf8NullTerminated(): String {
    val bytes = mutableListOf<Byte>()
    while (true) {
        val b = readByte()
        if (b == 0.toByte()) break
        bytes.add(b)
    }
    return bytes.toByteArray().decodeToString()
}

/** Read a 1-byte-length-prefixed UTF-8 (Pascal) string. Used by Nero `chpl` atoms. */
internal fun Buffer.readPString(): String {
    val len = readByte().toInt() and BYTE_MASK
    val bytes = readByteArray(len)
    return bytes.decodeToString()
}
