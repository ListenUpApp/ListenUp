@file:Suppress("MagicNumber") // Atom-format constants — readability beats names.

package com.calypsan.listenup.server.embeddedmeta.format.mp4

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

    /** True if this atom type is known to contain child atoms. */
    val isContainer: Boolean
        get() = type in CONTAINER_TYPES
}

internal class AtomParseException(
    val offset: Int,
    val expected: String,
) : RuntimeException("invalid atom at offset $offset: $expected")

private val CONTAINER_TYPES =
    setOf(
        "moov", "udta", "ilst", "trak", "mdia", "minf", "stbl", "tref", "----", "covr",
        // `meta` is a container too, but its payload starts with 4 extra bytes
        // (version+flags) before the first child atom — readers handle the
        // skip explicitly via [readMetaChildren].
    )

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
        val type = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
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
                0 -> (end - offset) to false // 0 = atom extends to EOF
                else -> size32 to false
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
