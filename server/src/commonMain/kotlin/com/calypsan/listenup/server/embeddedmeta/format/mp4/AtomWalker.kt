
package com.calypsan.listenup.server.embeddedmeta.format.mp4

import com.calypsan.listenup.server.io.SeekableSource
import com.calypsan.listenup.server.embeddedmeta.decode.TextDecoding

/**
 * Lightweight MP4 atom (box) walker over an in-memory byte slice.
 *
 * The MP4 container is a flat sequence of length-prefixed boxes:
 * `size(4 BE) + type(4 ASCII) + payload`. A `size == 1` flags an extended
 * 64-bit size that follows the type field; `size == 0` means "to EOF". A
 * declared size below the 8-byte minimum is rejected as a corrupt atom.
 *
 * Container atoms (`moov`, `udta`, `meta`, `ilst`, `trak`, `mdia`, `minf`,
 * `stbl`, `tref`, `----`, `covr`) hold child atoms in their payload. Leaf
 * atoms hold structured payloads consumed by readers in this package.
 *
 * Ports `/home/simonh/Code/audiometa/internal/m4a/atoms.go` to Kotlin with
 * pre-buffered bytes (the file is read in full at parse time — see
 * [Mp4Parser]) so failures map to byte-slice bounds rather than IO errors.
 */
internal data class Atom(
    val type: String,
    /** Absolute offset of the atom header within the source slice. */
    val offset: Int,
    /** Total atom size in bytes (header + payload). */
    val size: Int,
    val extended: Boolean,
) {
    val headerSize: Int get() = if (extended) 16 else 8
    val dataOffset: Int get() = offset + headerSize
    val dataSize: Int get() = (size - headerSize).coerceAtLeast(0)
    val end: Int get() = offset + size
}

internal class AtomParseException(
    val offset: Int,
    val expected: String,
) : RuntimeException("invalid atom at offset $offset: $expected")

/**
 * Result of a streaming top-level atom lookup. Records the file-absolute
 * offset of the atom header, the atom's total size including header, and
 * the header size itself (8 or 16 bytes for extended atoms) so callers
 * can decide whether to read just the payload or header+payload.
 */
internal data class TopLevelAtom(
    val offset: Long,
    val size: Long,
    val headerSize: Long,
) {
    val end: Long get() = offset + size
}

// MP4 atom header field offsets/sizes (8-byte basic, 16-byte extended) and the
// big-endian byte-assembly shift widths are fixed by the ISO base media file
// format (ISO/IEC 14496-12).
@Suppress("MagicNumber")
internal object AtomWalker {
    /**
     * Read the atom header at [offset]. Throws [AtomParseException] if the
     * declared size is below the 8-byte minimum or extends past [end]; the
     * caller maps this to the appropriate [com.calypsan.listenup.api.error.AudioMetadataError].
     */
    fun readHeader(
        bytes: ByteArray,
        offset: Int,
        end: Int = bytes.size,
    ): Atom {
        if (offset + 8 > end) {
            throw AtomParseException(offset, "atom header (need 8 bytes, have ${end - offset})")
        }
        val size32 = readBeInt32(bytes, offset)
        val type = TextDecoding.decodeLatin1(bytes, offset + 4, 4)
        val (size, extended) =
            when (size32) {
                1 -> {
                    if (offset + 16 > end) {
                        throw AtomParseException(offset, "extended atom size (need 16 bytes)")
                    }
                    val size64 = readBeInt64(bytes, offset + 8)
                    if (size64 > Int.MAX_VALUE.toLong()) {
                        throw AtomParseException(offset, "atom size > Int.MAX_VALUE not supported")
                    }
                    size64.toInt() to true
                }

                0 -> {
                    (end - offset) to false
                }

                // 0 = atom extends to EOF
                else -> {
                    size32 to false
                }
            }
        if (size < 8) {
            throw AtomParseException(offset, "atom size $size below 8-byte minimum")
        }
        if (offset + size > end) {
            throw AtomParseException(offset, "atom size $size extends past end ($end)")
        }
        return Atom(type = type, offset = offset, size = size, extended = extended)
    }

    /**
     * Iterate every direct child atom inside the byte range
     * `[start, end)`. Stops at [end] or as soon as a malformed atom header
     * is encountered (caller decides whether to bubble or swallow via the
     * passed [onError] callback — default is to stop walking silently).
     */
    inline fun forEachChild(
        bytes: ByteArray,
        start: Int,
        end: Int,
        onError: (AtomParseException) -> Unit = {},
        action: (Atom) -> Unit,
    ) {
        var offset = start
        while (offset < end) {
            val atom =
                try {
                    readHeader(bytes, offset, end)
                } catch (e: AtomParseException) {
                    onError(e)
                    return
                }
            action(atom)
            offset = atom.end
        }
    }

    /**
     * Find the first direct child atom of type [type] within `[start, end)`.
     * Returns null if no such atom exists.
     */
    fun findChild(
        bytes: ByteArray,
        start: Int,
        end: Int,
        type: String,
    ): Atom? {
        var found: Atom? = null
        forEachChild(bytes, start, end) { atom ->
            if (atom.type == type && found == null) found = atom
        }
        return found
    }

    /**
     * Find the first descendant atom matched by the dotted [path] (e.g.
     * `"moov.udta.meta.ilst"`). Containers are descended into in order;
     * if any segment cannot be found returns null.
     *
     * The `meta` atom's 4-byte version+flags prefix is automatically skipped
     * when descending into it.
     */
    fun findPath(
        bytes: ByteArray,
        path: String,
    ): Atom? {
        val segments = path.split('.')
        var start = 0
        var end = bytes.size
        var found: Atom? = null
        for (segment in segments) {
            val atom = findChild(bytes, start, end, segment) ?: return null
            found = atom
            start = atom.dataOffset
            end = atom.end
            if (segment == "meta") {
                // meta has a 4-byte version+flags prefix before its children.
                start += 4
            }
        }
        return found
    }

    /**
     * Streaming counterpart of [findChild] for the top-level box list.
     *
     * Walks the file's top-level atoms by reading only their 8/16-byte
     * headers via [source.seek]/[source.readFully] — never the payload.
     * Returns the (offset, size) of the first atom whose type matches
     * [type], or null if the file has no such atom or contains a
     * malformed header before the target is found.
     *
     * Used by [Mp4Parser] to locate `moov` without loading the whole
     * file (typically multi-GB for audiobooks) into memory; only
     * `moov`'s payload is then read back as a sub-range.
     */
    fun findTopLevelAtom(
        source: SeekableSource,
        type: String,
    ): TopLevelAtom? {
        val length = source.length
        var offset = 0L
        while (offset + 8 <= length) {
            source.seek(offset)
            val headerBytes = source.readFully(8)
            // The 32-bit size field is unsigned per ISO/IEC 14496-12 — sizes from 2 GB to 4 GB
            // are valid in the wild (audiobook `mdat` atoms carrying 30+ hours of audio).
            // Read into a Long so the high bit doesn't flip to a negative signed Int.
            val size32 =
                ((headerBytes[0].toLong() and 0xFFL) shl 24) or
                    ((headerBytes[1].toLong() and 0xFFL) shl 16) or
                    ((headerBytes[2].toLong() and 0xFFL) shl 8) or
                    (headerBytes[3].toLong() and 0xFFL)
            val atomType = TextDecoding.decodeLatin1(headerBytes, 4, 4)
            val (size, headerSize) =
                when (size32) {
                    1L -> {
                        if (offset + 16 > length) return null
                        source.seek(offset + 8)
                        val ext = source.readFully(8)
                        val size64 = bytesToBeInt64(ext, 0)
                        if (size64 < 16 || offset + size64 > length) return null
                        size64 to 16L
                    }

                    // 0 = atom extends to EOF
                    0L -> {
                        (length - offset) to 8L
                    }

                    else -> {
                        if (size32 < 8L) return null
                        if (offset + size32 > length) return null
                        size32 to 8L
                    }
                }
            if (atomType == type) {
                return TopLevelAtom(offset = offset, size = size, headerSize = headerSize)
            }
            offset += size
        }
        return null
    }

    private fun bytesToBeInt64(
        bytes: ByteArray,
        off: Int,
    ): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (bytes[off + i].toLong() and 0xFFL)
        }
        return v
    }

    /** Read 4 big-endian bytes as a signed Int. */
    fun readBeInt32(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** Read 4 big-endian bytes as an unsigned 32-bit value (returned as Long). */
    fun readBeUInt32(
        bytes: ByteArray,
        offset: Int,
    ): Long = readBeInt32(bytes, offset).toLong() and 0xFFFFFFFFL

    /** Read 8 big-endian bytes as a signed Long. */
    fun readBeInt64(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return v
    }
}
