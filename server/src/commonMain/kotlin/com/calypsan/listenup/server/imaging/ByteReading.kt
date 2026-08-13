package com.calypsan.listenup.server.imaging

/**
 * The unsigned value of one byte.
 *
 * JPEG is a byte-oriented format and every field in it is unsigned, while Kotlin's [Byte] is signed
 * — so the mask is not incidental, it is the conversion. Naming it once keeps it out of every
 * expression that reads a header field.
 */
internal fun readUByte(
    bytes: ByteArray,
    at: Int,
): Int = bytes[at].toInt() and BYTE_MASK

internal fun readUShort(
    bytes: ByteArray,
    at: Int,
): Int = readUByte(bytes, at) shl BITS_PER_BYTE or readUByte(bytes, at + 1)

/**
 * JPEG packs two 4-bit fields into a byte throughout its headers — table class and id, horizontal
 * and vertical sampling, run and size, successive-approximation high and low.
 */
internal fun highNibble(value: Int): Int = value and HIGH_NIBBLE_MASK shr NIBBLE_BITS

internal fun lowNibble(value: Int): Int = value and LOW_NIBBLE_MASK

internal const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xFF
private const val HIGH_NIBBLE_MASK = 0xF0
private const val LOW_NIBBLE_MASK = 0x0F
internal const val NIBBLE_BITS = 4
