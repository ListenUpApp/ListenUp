package com.calypsan.listenup.server.compression.zip

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

// ── Signature constants (Long to avoid sign issues with high-bit 32-bit values) ──────────────────

internal const val LFH_SIG: Long = 0x04034b50L
internal const val DD_SIG: Long = 0x08074b50L
internal const val CDH_SIG: Long = 0x02014b50L
internal const val EOCD_SIG: Long = 0x06054b50L
internal const val ZIP64_EOCD_SIG: Long = 0x06064b50L
internal const val ZIP64_LOCATOR_SIG: Long = 0x07064b50L
internal const val ZIP64_EXTRA_ID: Int = 0x0001

/** Sentinel: a 32-bit slot holding this value means the real value is in the ZIP64 extra field. */
internal const val ZIP64_U32_MAX: Long = 0xFFFF_FFFFL

/** Maximum value for a 16-bit unsigned ZIP field. */
internal const val ZIP16_MAX: Int = 0xFFFF

// ── Little-endian Buffer I/O ──────────────────────────────────────────────────────────────────────

/** Writes [v] as a 2-byte little-endian unsigned integer. */
internal fun Buffer.writeU16LE(v: Int) {
    writeByte((v and 0xFF).toByte())
    writeByte(((v shr 8) and 0xFF).toByte())
}

/** Writes [v] as a 4-byte little-endian unsigned integer. [v] must fit in 0..0xFFFFFFFF. */
internal fun Buffer.writeU32LE(v: Long) {
    writeByte((v and 0xFFL).toByte())
    writeByte(((v shr 8) and 0xFFL).toByte())
    writeByte(((v shr 16) and 0xFFL).toByte())
    writeByte(((v shr 24) and 0xFFL).toByte())
}

/** Writes [v] as an 8-byte little-endian integer. */
internal fun Buffer.writeU64LE(v: Long) {
    writeByte((v and 0xFFL).toByte())
    writeByte(((v shr 8) and 0xFFL).toByte())
    writeByte(((v shr 16) and 0xFFL).toByte())
    writeByte(((v shr 24) and 0xFFL).toByte())
    writeByte(((v shr 32) and 0xFFL).toByte())
    writeByte(((v shr 40) and 0xFFL).toByte())
    writeByte(((v shr 48) and 0xFFL).toByte())
    writeByte(((v shr 56) and 0xFFL).toByte())
}

/** Reads a 2-byte little-endian unsigned integer, returning it as a non-negative [Int]. */
internal fun Buffer.readU16LE(): Int {
    val b0 = readByte().toInt() and 0xFF
    val b1 = readByte().toInt() and 0xFF
    return b0 or (b1 shl 8)
}

/**
 * Reads a 4-byte little-endian unsigned integer, returning it as a [Long] in 0..0xFFFFFFFF.
 * Masking each byte to 0xFF guarantees the result is never negative; 0xFFFFFFFF → 4294967295L, not -1.
 */
internal fun Buffer.readU32LE(): Long {
    val b0 = readByte().toLong() and 0xFFL
    val b1 = readByte().toLong() and 0xFFL
    val b2 = readByte().toLong() and 0xFFL
    val b3 = readByte().toLong() and 0xFFL
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

/** Reads an 8-byte little-endian integer as a [Long]. */
internal fun Buffer.readU64LE(): Long {
    val b0 = readByte().toLong() and 0xFFL
    val b1 = readByte().toLong() and 0xFFL
    val b2 = readByte().toLong() and 0xFFL
    val b3 = readByte().toLong() and 0xFFL
    val b4 = readByte().toLong() and 0xFFL
    val b5 = readByte().toLong() and 0xFFL
    val b6 = readByte().toLong() and 0xFFL
    val b7 = readByte().toLong() and 0xFFL
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24) or
        (b4 shl 32) or (b5 shl 40) or (b6 shl 48) or (b7 shl 56)
}

// ── ZIP64 extra field (header id 0x0001) ─────────────────────────────────────────────────────────

/** Holds the optional ZIP64 overflow values decoded from the 0x0001 extra field. */
internal class Zip64ExtraFields(
    val uncompSize: Long?,
    val compSize: Long?,
    val localOffset: Long?,
)

/**
 * Encodes a ZIP64 extra field (header id 0x0001) containing only the non-null values, in fixed
 * order: uncompSize, compSize, localOffset. dataSize = 8 * (count of non-null). Returns an empty
 * [ByteArray] if all arguments are null.
 */
internal fun encodeZip64Extra(
    uncompSize: Long?,
    compSize: Long?,
    localOffset: Long?,
): ByteArray {
    val fields = listOfNotNull(uncompSize, compSize, localOffset)
    if (fields.isEmpty()) return ByteArray(0)
    val buf = Buffer()
    buf.writeU16LE(ZIP64_EXTRA_ID)
    buf.writeU16LE(fields.size * 8)
    for (field in fields) buf.writeU64LE(field)
    return buf.readByteArray()
}

/**
 * Walks [extra] as a sequence of TLV records (id:u16, size:u16, data[size]). Finds the 0x0001
 * record and reads its fields positionally: uncompSize first, then compSize, then localOffset,
 * up to dataSize/8 values. Fields absent from the record are returned as null. On a truncated record
 * the walk stops cleanly (no partial result, no out-of-bounds read); if no 0x0001 record is found, all
 * fields are null.
 */
internal fun parseZip64Extra(extra: ByteArray): Zip64ExtraFields {
    var pos = 0
    while (pos + 4 <= extra.size) {
        val id = (extra[pos].toInt() and 0xFF) or ((extra[pos + 1].toInt() and 0xFF) shl 8)
        val size = (extra[pos + 2].toInt() and 0xFF) or ((extra[pos + 3].toInt() and 0xFF) shl 8)
        val dataStart = pos + 4
        val dataEnd = dataStart + size

        if (dataEnd > extra.size) break // truncated record — stop

        if (id == ZIP64_EXTRA_ID) {
            val count = size / 8

            fun readField(index: Int): Long? {
                if (index >= count) return null
                val off = dataStart + index * 8
                if (off + 8 > dataEnd) return null
                var v = 0L
                for (i in 0..7) v = v or ((extra[off + i].toLong() and 0xFFL) shl (i * 8))
                return v
            }
            return Zip64ExtraFields(
                uncompSize = readField(0),
                compSize = readField(1),
                localOffset = readField(2),
            )
        }

        pos = dataEnd
    }
    return Zip64ExtraFields(uncompSize = null, compSize = null, localOffset = null)
}

/**
 * Sentinel-aware variant of [parseZip64Extra] for the reader. A central-directory header carries a
 * ZIP64 overflow value only for each base field whose 32-bit slot equals the 0xFFFFFFFF sentinel, and
 * the 0x0001 record packs exactly those values in fixed order: uncompSize, then compSize, then
 * localOffset. This walks to the 0x0001 record and assigns its i-th 8-byte value to the i-th field that
 * [hasUncomp]/[hasComp]/[hasOffset] marks as a sentinel — so a record carrying only compSize (because
 * uncompSize was a real 32-bit value) decodes to [Zip64ExtraFields.compSize], never mis-assigned to
 * uncompSize the way a positional read would. A field marked sentinel but absent from the record (a
 * short or truncated record) decodes to null; the caller treats that as malformed.
 */
internal fun parseZip64ExtraFor(
    extra: ByteArray,
    hasUncomp: Boolean,
    hasComp: Boolean,
    hasOffset: Boolean,
): Zip64ExtraFields {
    var pos = 0
    while (pos + 4 <= extra.size) {
        val id = (extra[pos].toInt() and 0xFF) or ((extra[pos + 1].toInt() and 0xFF) shl 8)
        val size = (extra[pos + 2].toInt() and 0xFF) or ((extra[pos + 3].toInt() and 0xFF) shl 8)
        val dataStart = pos + 4
        val dataEnd = dataStart + size

        if (dataEnd > extra.size) break // truncated record — stop

        if (id == ZIP64_EXTRA_ID) {
            val available = size / 8
            var index = 0

            fun nextValue(): Long? {
                if (index >= available) return null
                val off = dataStart + index * 8
                var v = 0L
                for (i in 0..7) v = v or ((extra[off + i].toLong() and 0xFFL) shl (i * 8))
                index++
                return v
            }
            // Left-to-right argument evaluation guarantees the values are consumed in field order.
            return Zip64ExtraFields(
                uncompSize = if (hasUncomp) nextValue() else null,
                compSize = if (hasComp) nextValue() else null,
                localOffset = if (hasOffset) nextValue() else null,
            )
        }

        pos = dataEnd
    }
    return Zip64ExtraFields(uncompSize = null, compSize = null, localOffset = null)
}

// ── EOCD locator ─────────────────────────────────────────────────────────────────────────────────

/**
 * Scans [tail] (the last ≤ 65557 bytes of the ZIP file) backward from offset [tail.size - 22]
 * looking for the EOCD signature (0x06054b50 LE). Returns the offset into [tail] where the
 * signature starts, or throws [MalformedZipException] if the signature is not found.
 */
internal fun findEocdOffset(tail: ByteArray): Long {
    if (tail.size < 22) throw MalformedZipException("tail too short to contain an EOCD (${tail.size} bytes)")
    for (i in tail.size - 22 downTo 0) {
        val sig =
            (tail[i].toLong() and 0xFFL) or
                ((tail[i + 1].toLong() and 0xFFL) shl 8) or
                ((tail[i + 2].toLong() and 0xFFL) shl 16) or
                ((tail[i + 3].toLong() and 0xFFL) shl 24)
        if (sig == EOCD_SIG) return i.toLong()
    }
    throw MalformedZipException("end of central directory not found")
}
