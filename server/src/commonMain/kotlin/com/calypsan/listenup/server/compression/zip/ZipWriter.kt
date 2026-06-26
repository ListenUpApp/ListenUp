package com.calypsan.listenup.server.compression.zip

import com.calypsan.listenup.server.compression.Crc32
import com.calypsan.listenup.server.compression.DEFAULT_LEVEL
import com.calypsan.listenup.server.compression.deflated
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/** Compression method for a ZIP entry: [STORED] copies bytes verbatim, [DEFLATE] compresses them. */
public enum class ZipMethod { STORED, DEFLATE }

/**
 * Writes a ZIP archive to a sink as a stream — never seeks backward. The central directory written by
 * [finish] always carries the authoritative CRC-32 and sizes; ZIP64 fields are emitted per-entry when a
 * size or offset exceeds 32 bits. [close] calls [finish] if needed, then closes the sink. Pure commonMain.
 *
 * DEFLATE entries are fully streaming: the local header defers CRC and sizes to a trailing data descriptor
 * (flag bit 3), so content of any size compresses with bounded memory. The descriptor uses 8-byte sizes only
 * when the entry exceeds 4 GiB — matching how `java.util.zip` auto-detects descriptor width from the inflated
 * size (not from a local-header ZIP64 extra), so both its streaming `ZipInputStream` and random-access
 * `ZipFile` read entries of any size; our `ZipReader` uses the authoritative central directory regardless.
 * STORED entries cannot use a data
 * descriptor — `java.util.zip` rejects `STORED + EXT` — so a forward-only writer must know their size up
 * front; their content is buffered in memory, then a complete local header (real CRC/sizes, no descriptor)
 * precedes the bytes. STORED therefore costs one entry's worth of memory; reserve it for small or already
 * incompressible payloads.
 *
 * Usage: call [putEntry] to begin an entry, write its content to the returned sink, then close that sink
 * (e.g. via `.buffered().use { }`) before opening the next. [finish] (or [close]) seals the archive.
 */
public class ZipWriter(
    sink: RawSink,
    private val level: Int = DEFAULT_LEVEL,
) : AutoCloseable {
    private val out = sink.buffered()

    /** Total bytes emitted to [out] so far — each entry's local-header offset and the central-directory offset. */
    private var written = 0L
    private val centralEntries = mutableListOf<CentralEntry>()
    private var entryOpen = false
    private var finished = false
    private var closed = false

    /**
     * Begins an entry named [name] compressed with [method] and returns a sink for its (uncompressed)
     * content. For DEFLATE the local header is written immediately and the CRC/sizes follow in a data
     * descriptor; for STORED the content is buffered and the whole header is written when the sink closes.
     * The caller MUST close the returned sink before calling [putEntry] again or [finish]. Safe to wrap in
     * `.buffered()` and `.use { }`.
     */
    public fun putEntry(
        name: String,
        method: ZipMethod = ZipMethod.DEFLATE,
    ): RawSink {
        check(!finished) { "ZipWriter is finished" }
        check(!entryOpen) { "previous entry is still open" }
        entryOpen = true
        val nameBytes = name.encodeToByteArray()
        val localOffset = written
        return when (method) {
            ZipMethod.DEFLATE -> DeflateEntry(nameBytes, localOffset).sink
            ZipMethod.STORED -> StoredEntry(nameBytes, localOffset).sink
        }
    }

    /**
     * Seals the archive: writes the central directory, then the EOCD record (and a ZIP64 EOCD + locator
     * when an offset, size, or entry count overflows its classic field). No entry may be open. Calling
     * [finish] twice throws; [close] guards against the double call.
     */
    public fun finish() {
        check(!finished) { "ZipWriter is already finished" }
        check(!entryOpen) { "an entry is still open" }
        finished = true
        val cdOffset = written
        for (entry in centralEntries) writeCentralHeader(entry)
        val cdSize = written - cdOffset
        val count = centralEntries.size
        if (cdOffset > ZIP64_U32_MAX || cdSize > ZIP64_U32_MAX || count > ZIP16_MAX) {
            writeZip64Eocd(count, cdSize, cdOffset)
        }
        writeEocd(count, cdSize, cdOffset)
        out.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!finished) finish()
        out.close()
    }

    private fun writeCentralHeader(entry: CentralEntry) {
        val extra =
            encodeZip64Extra(
                uncompSize = entry.uncompSize.takeIf { it > ZIP64_U32_MAX },
                compSize = entry.compSize.takeIf { it > ZIP64_U32_MAX },
                localOffset = entry.localOffset.takeIf { it > ZIP64_U32_MAX },
            )
        emit {
            writeU32LE(CDH_SIG)
            writeU16LE(VERSION_MADE_BY)
            writeU16LE(if (entry.needsZip64) VERSION_ZIP64 else VERSION_DEFAULT)
            writeU16LE(flagsFor(entry.method))
            writeU16LE(methodCode(entry.method))
            writeU16LE(0) // modTime
            writeU16LE(0) // modDate
            writeU32LE(entry.crc)
            writeU32LE(clampU32(entry.compSize))
            writeU32LE(clampU32(entry.uncompSize))
            writeU16LE(entry.nameBytes.size)
            writeU16LE(extra.size)
            writeU16LE(0) // commentLen
            writeU16LE(0) // diskStart
            writeU16LE(0) // intAttrs
            writeU32LE(0) // extAttrs
            writeU32LE(clampU32(entry.localOffset))
            write(entry.nameBytes)
            if (extra.isNotEmpty()) write(extra)
        }
    }

    private fun writeZip64Eocd(
        count: Int,
        cdSize: Long,
        cdOffset: Long,
    ) {
        val zip64EocdOffset = written
        emit {
            writeU32LE(ZIP64_EOCD_SIG)
            writeU64LE(ZIP64_EOCD_RECORD_SIZE)
            writeU16LE(VERSION_MADE_BY)
            writeU16LE(VERSION_ZIP64)
            writeU32LE(0) // disk
            writeU32LE(0) // cdStartDisk
            writeU64LE(count.toLong())
            writeU64LE(count.toLong())
            writeU64LE(cdSize)
            writeU64LE(cdOffset)
        }
        emit {
            writeU32LE(ZIP64_LOCATOR_SIG)
            writeU32LE(0) // disk holding the ZIP64 EOCD
            writeU64LE(zip64EocdOffset)
            writeU32LE(1) // total disks
        }
    }

    private fun writeEocd(
        count: Int,
        cdSize: Long,
        cdOffset: Long,
    ) {
        emit {
            writeU32LE(EOCD_SIG)
            writeU16LE(0) // disk
            writeU16LE(0) // cdStartDisk
            writeU16LE(minOf(count, ZIP16_MAX))
            writeU16LE(minOf(count, ZIP16_MAX))
            writeU32LE(clampU32(cdSize))
            writeU32LE(clampU32(cdOffset))
            writeU16LE(0) // commentLen
        }
    }

    /** Builds a header in a scratch [Buffer], emits it to [out], and advances [written] by its length. */
    private fun emit(block: Buffer.() -> Unit) {
        val buffer = Buffer().apply(block)
        val size = buffer.size
        out.write(buffer, size)
        written += size
    }

    private fun appendCentral(
        nameBytes: ByteArray,
        method: ZipMethod,
        crc: Long,
        compSize: Long,
        uncompSize: Long,
        localOffset: Long,
    ) {
        val needsZip64 = compSize > ZIP64_U32_MAX || uncompSize > ZIP64_U32_MAX || localOffset > ZIP64_U32_MAX
        centralEntries += CentralEntry(nameBytes, method, crc, compSize, uncompSize, localOffset, needsZip64)
        entryOpen = false
    }

    /**
     * A streaming DEFLATE entry. The local header (with deferred CRC/sizes) was already written; content
     * is CRC-summed and compressed straight to [out] through a counting sink. Closing the content sink
     * finalizes the deflate stream, writes the data descriptor, and records the central-directory entry.
     */
    private inner class DeflateEntry(
        private val nameBytes: ByteArray,
        private val localOffset: Long,
    ) {
        private val crc = Crc32()
        private var uncompSize = 0L
        private var compSize = 0L
        private var sinkClosed = false

        init {
            emit {
                writeU32LE(LFH_SIG)
                writeU16LE(VERSION_DEFAULT)
                writeU16LE(DEFLATE_FLAGS)
                writeU16LE(METHOD_DEFLATE)
                writeU16LE(0) // modTime
                writeU16LE(0) // modDate
                writeU32LE(0) // crc — deferred to the data descriptor
                writeU32LE(0) // compSize — deferred
                writeU32LE(0) // uncompSize — deferred
                writeU16LE(nameBytes.size)
                writeU16LE(0) // extraLen
                write(nameBytes)
            }
        }

        /** Forwards compressed bytes to [out], counting them toward the archive total and this entry's compSize. */
        private val countingSink =
            object : RawSink {
                override fun write(
                    source: Buffer,
                    byteCount: Long,
                ) {
                    out.write(source, byteCount)
                    written += byteCount
                    compSize += byteCount
                }

                override fun flush() = out.flush()

                // Flush buffered bytes downstream but never close the shared archive sink.
                override fun close() = out.flush()
            }

        private val deflateSink: RawSink = countingSink.deflated(level)

        val sink: RawSink =
            object : RawSink {
                override fun write(
                    source: Buffer,
                    byteCount: Long,
                ) {
                    check(!sinkClosed) { "entry sink is closed" }
                    var remaining = byteCount
                    while (remaining > 0) {
                        val chunk = source.readByteArray(minOf(remaining, COPY_CHUNK).toInt())
                        crc.update(chunk)
                        uncompSize += chunk.size
                        deflateSink.write(Buffer().apply { write(chunk) }, chunk.size.toLong())
                        remaining -= chunk.size
                    }
                }

                override fun flush() = deflateSink.flush()

                override fun close() {
                    if (sinkClosed) return
                    sinkClosed = true
                    deflateSink.close() // emits + counts the final deflate block
                    val needsZip64 = compSize > ZIP64_U32_MAX || uncompSize > ZIP64_U32_MAX
                    emit {
                        writeU32LE(DD_SIG)
                        writeU32LE(crc.value)
                        if (needsZip64) {
                            writeU64LE(compSize)
                            writeU64LE(uncompSize)
                        } else {
                            writeU32LE(compSize)
                            writeU32LE(uncompSize)
                        }
                    }
                    appendCentral(nameBytes, ZipMethod.DEFLATE, crc.value, compSize, uncompSize, localOffset)
                }
            }
    }

    /**
     * A STORED entry. Because `java.util.zip` rejects a STORED entry with a data descriptor, the size must
     * be known before the local header — so content is buffered in memory, CRC-summed, then on close a
     * complete local header (real CRC/sizes, no descriptor) is written followed by the raw bytes.
     */
    private inner class StoredEntry(
        private val nameBytes: ByteArray,
        private val localOffset: Long,
    ) {
        private val crc = Crc32()
        private val content = Buffer()
        private var sinkClosed = false

        val sink: RawSink =
            object : RawSink {
                override fun write(
                    source: Buffer,
                    byteCount: Long,
                ) {
                    check(!sinkClosed) { "entry sink is closed" }
                    var remaining = byteCount
                    while (remaining > 0) {
                        val chunk = source.readByteArray(minOf(remaining, COPY_CHUNK).toInt())
                        crc.update(chunk)
                        content.write(chunk)
                        remaining -= chunk.size
                    }
                }

                override fun flush() = Unit

                override fun close() {
                    if (sinkClosed) return
                    sinkClosed = true
                    val size = content.size
                    val needsZip64 = size > ZIP64_U32_MAX
                    val extra =
                        encodeZip64Extra(
                            uncompSize = size.takeIf { needsZip64 },
                            compSize = size.takeIf { needsZip64 },
                            localOffset = null, // only the central header records the local offset
                        )
                    emit {
                        writeU32LE(LFH_SIG)
                        writeU16LE(if (needsZip64) VERSION_ZIP64 else VERSION_DEFAULT)
                        writeU16LE(STORED_FLAGS)
                        writeU16LE(METHOD_STORED)
                        writeU16LE(0) // modTime
                        writeU16LE(0) // modDate
                        writeU32LE(crc.value)
                        writeU32LE(clampU32(size))
                        writeU32LE(clampU32(size))
                        writeU16LE(nameBytes.size)
                        writeU16LE(extra.size)
                        write(nameBytes)
                        if (extra.isNotEmpty()) write(extra)
                    }
                    out.write(content, size)
                    written += size
                    appendCentral(nameBytes, ZipMethod.STORED, crc.value, size, size, localOffset)
                }
            }
    }
}

/** A finished entry's authoritative metadata, replayed into the central directory by [ZipWriter.finish]. */
private class CentralEntry(
    val nameBytes: ByteArray,
    val method: ZipMethod,
    val crc: Long,
    val compSize: Long,
    val uncompSize: Long,
    val localOffset: Long,
    val needsZip64: Boolean,
)

/** ZIP method code: 0 = STORED, 8 = DEFLATE. */
private fun methodCode(method: ZipMethod): Int = if (method == ZipMethod.DEFLATE) METHOD_DEFLATE else METHOD_STORED

/** General-purpose flags: STORED carries sizes in its header (UTF-8 only); DEFLATE adds the data-descriptor bit. */
private fun flagsFor(method: ZipMethod): Int = if (method == ZipMethod.DEFLATE) DEFLATE_FLAGS else STORED_FLAGS

/** Replaces a value that overflows a 32-bit field with the ZIP64 sentinel; the real value lives in the extra. */
private fun clampU32(value: Long): Long = if (value > ZIP64_U32_MAX) ZIP64_U32_MAX else value

private const val VERSION_DEFAULT: Int = 20
private const val VERSION_ZIP64: Int = 45
private const val VERSION_MADE_BY: Int = 20

/** UTF-8 names (bit 11) only — STORED records its CRC and sizes in the local header. */
private const val STORED_FLAGS: Int = 0x0800

/** Data descriptor (bit 3) + UTF-8 names (bit 11) — DEFLATE defers its CRC and sizes to a trailing descriptor. */
private const val DEFLATE_FLAGS: Int = 0x0808
private const val METHOD_STORED: Int = 0
private const val METHOD_DEFLATE: Int = 8

/** ZIP64 EOCD record size field: the fixed 56-byte record minus its 12-byte signature+size prefix. */
private const val ZIP64_EOCD_RECORD_SIZE: Long = 44L

/** Bytes copied per pass when CRC-ing and forwarding entry content — bounds working-set memory. */
private const val COPY_CHUNK: Long = 65_536L
