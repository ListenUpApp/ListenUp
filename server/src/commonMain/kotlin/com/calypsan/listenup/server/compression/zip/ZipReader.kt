package com.calypsan.listenup.server.compression.zip

import com.calypsan.listenup.server.compression.inflated
import com.calypsan.listenup.server.io.SeekableSource
import com.calypsan.listenup.server.io.openSeekableSource
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

/**
 * One entry decoded from a ZIP central directory: its name, compression [method], CRC-32, and the
 * authoritative compressed/uncompressed sizes (ZIP64-resolved). [localHeaderOffset] is the byte offset
 * of the entry's local file header and is consumed only by [ZipReader.openEntry]; callers identify an
 * entry by [name]. Construct these only via [ZipReader].
 */
public class ZipEntryInfo internal constructor(
    public val name: String,
    public val method: ZipMethod,
    public val crc32: Long,
    public val compressedSize: Long,
    public val uncompressedSize: Long,
    internal val localHeaderOffset: Long,
)

/**
 * Random-access reader for a ZIP archive on disk. It parses the central directory up front (the
 * authoritative index — STORED and DEFLATE entries, classic and ZIP64), then [openEntry] seeks to an
 * entry's data and streams it: STORED bytes verbatim, DEFLATE bytes through the pure-Kotlin inflater.
 *
 * Built to read every archive `java.util.zip.ZipOutputStream` produces — including the data-descriptor
 * DEFLATE entries our own [ZipWriter] emits — because the central directory always carries the real
 * sizes regardless of how the local headers defer them. Every length and offset read from the archive
 * is treated as untrusted: an out-of-range value or a bad signature raises [MalformedZipException]
 * rather than an out-of-bounds read or an unbounded allocation. Pure commonMain over [SeekableSource];
 * the same code reads on the JVM and Kotlin/Native.
 *
 * Not thread-safe: a single underlying file handle backs every [openEntry] stream. Read entries
 * sequentially (each stream tracks its own absolute offset, so a fully-drained stream leaves the next
 * correct), and [close] when done — closing the reader closes the file handle and invalidates any open
 * entry streams.
 */
public class ZipReader(
    path: Path,
) : AutoCloseable {
    private val source: SeekableSource = openSeekableSource(path)

    private val parsedEntries: List<ZipEntryInfo> =
        try {
            parseCentralDirectory()
        } catch (e: MalformedZipException) {
            source.close()
            throw e
        } catch (e: Exception) {
            // EOFException from a short read, an arithmetic edge — anything the parse didn't classify is
            // still a malformed archive from the caller's point of view. Never leak the handle.
            source.close()
            throw MalformedZipException("malformed ZIP archive", e)
        }

    /** The entries listed in the central directory, in directory order. */
    public fun entries(): List<ZipEntryInfo> = parsedEntries.toList()

    /** The entry named [name], or null if no entry has that exact name. */
    public fun entry(name: String): ZipEntryInfo? = parsedEntries.firstOrNull { it.name == name }

    /**
     * Opens [entry]'s content as a fresh [RawSource]: the raw bytes for [ZipMethod.STORED], the inflated
     * bytes for [ZipMethod.DEFLATE]. The stream reads exactly the entry's compressed bytes from disk and
     * does not verify the CRC-32 (a partial read is legitimate; CRC checking is the consumer's choice).
     * Throws [MalformedZipException] if the local file header is missing or the entry's data would run
     * past the end of the file.
     */
    public fun openEntry(entry: ZipEntryInfo): RawSource {
        // Range-check the offset against the live file length first: the directory was validated at
        // construction, but the file may have been truncated since (disk rot, partial write), and a
        // crafted ZIP64 offset must never reach a raw read. `source.length - LFH_FIXED_SIZE` can't
        // overflow; if the file is now shorter than a header, every non-negative offset is rejected.
        if (entry.localHeaderOffset < 0 || entry.localHeaderOffset > source.length - LFH_FIXED_SIZE) {
            throw MalformedZipException("local header offset ${entry.localHeaderOffset} out of range")
        }
        val lfh =
            try {
                source.seek(entry.localHeaderOffset)
                Buffer().apply { write(source.readFully(LFH_FIXED_SIZE)) }
            } catch (e: Exception) {
                // The only failure the reads above can raise is a short/EOF read — meaning the file was
                // truncated between the range check and this read (TOCTOU). Surface it as a malformed
                // archive, never a raw EOFException.
                throw MalformedZipException("entry '${entry.name}' has a truncated local header", e)
            }
        if (lfh.readU32LE() != LFH_SIG) {
            throw MalformedZipException("entry '${entry.name}' has no local file header")
        }
        lfh.skip(LFH_PRE_NAME_LEN_BYTES) // straight to the nameLen/extraLen fields at offsets 26/28
        val nameLen = lfh.readU16LE()
        val extraLen = lfh.readU16LE()
        // localHeaderOffset ≤ length − 30 and nameLen/extraLen are u16, so the sum can't overflow a Long.
        val dataStart = entry.localHeaderOffset + LFH_FIXED_SIZE + nameLen + extraLen
        if (dataStart < 0 || dataStart > source.length ||
            entry.compressedSize < 0 || entry.compressedSize > source.length - dataStart
        ) {
            throw MalformedZipException("entry '${entry.name}' data runs past end of file")
        }
        val bounded = BoundedEntrySource(entry.name, dataStart, entry.compressedSize)
        return if (entry.method == ZipMethod.DEFLATE) bounded.inflated() else bounded
    }

    override fun close() {
        source.close()
    }

    private fun parseCentralDirectory(): List<ZipEntryInfo> {
        val fileLen = source.length
        if (fileLen < EOCD_FIXED_SIZE) {
            throw MalformedZipException("file too small to be a ZIP ($fileLen bytes)")
        }

        // The EOCD lives in the last 22..65557 bytes (22 fixed + up to a 65535-byte comment).
        val tailLen = minOf(fileLen, MAX_EOCD_SEARCH)
        val tailStart = fileLen - tailLen
        source.seek(tailStart)
        val tail = source.readFully(tailLen.toInt())

        val eocdRel = findEocdOffset(tail).toInt()
        val eocdAbs = tailStart + eocdRel
        val eocd = Buffer().apply { write(tail, eocdRel, eocdRel + EOCD_FIXED_SIZE.toInt()) }
        if (eocd.readU32LE() != EOCD_SIG) throw MalformedZipException("bad end-of-central-directory signature")
        eocd.skip(EOCD_DISK_FIELDS_LEN) // disk, cdStartDisk, entriesThisDisk
        val totalEntries16 = eocd.readU16LE()
        val cdSize32 = eocd.readU32LE()
        val cdOffset32 = eocd.readU32LE()

        var totalEntries: Long = totalEntries16.toLong()
        var cdSize: Long = cdSize32
        var cdOffset: Long = cdOffset32
        // The directory must end exactly where its terminator begins: the classic EOCD when not ZIP64,
        // the ZIP64 EOCD record when it is. This anchor is resolved alongside the sizes below.
        var anchorOffset: Long = eocdAbs

        val needsZip64 =
            totalEntries16 == ZIP16_MAX || cdSize32 == ZIP64_U32_MAX || cdOffset32 == ZIP64_U32_MAX
        if (needsZip64) {
            val z64 = readZip64Eocd(eocdAbs, fileLen)
            totalEntries = z64.totalEntries
            cdSize = z64.cdSize
            cdOffset = z64.cdOffset
            anchorOffset = z64.zip64EocdOffset
        }

        // Operand-first bounds that can't overflow a Long: `fileLen - cdOffset` is safe once cdOffset is
        // pinned to 0..fileLen, so cdSize is compared against the real remaining space, never a wrapped sum.
        if (cdOffset < 0 || cdOffset > fileLen || cdSize < 0 || cdSize > fileLen - cdOffset) {
            throw MalformedZipException(
                "central directory out of range (offset=$cdOffset size=$cdSize fileLen=$fileLen)",
            )
        }
        if (totalEntries < 0 || totalEntries > Int.MAX_VALUE) {
            throw MalformedZipException("implausible central-directory entry count ($totalEntries)")
        }
        // Cross-check the declared directory size against the entry count BEFORE buffering it: every
        // central-directory header is ≥ CDH_FIXED_SIZE and ≤ that plus a name/extra/comment of ≤ 65535
        // each, so a crafted `cdSize = fileLen` with one entry rejects in O(1) instead of allocating the
        // whole file onto the heap. (totalEntries is already pinned to 0..Int.MAX_VALUE, so no overflow.)
        val maxCdSize = totalEntries * (CDH_FIXED_SIZE + 3L * ZIP16_MAX)
        if (cdSize > maxCdSize) {
            throw MalformedZipException(
                "central directory size $cdSize implausible for $totalEntries entries",
            )
        }
        // Self-consistency anchor: the directory must abut its terminator. This stops a stray EOCD-shaped
        // signature embedded in entry data from redirecting the parser at attacker-chosen bytes. Safe to
        // sum here because the range guard above already bounded both operands to ≤ fileLen.
        if (cdOffset + cdSize != anchorOffset) {
            throw MalformedZipException("central directory does not abut the end-of-central-directory record")
        }

        val cd = readExactly(cdOffset, cdSize)
        return parseHeaders(cd, totalEntries.toInt(), fileLen)
    }

    /**
     * The count/size/offset values recovered from a ZIP64 end-of-central-directory record, plus
     * [zip64EocdOffset] — the record's own absolute offset, which the directory must abut.
     */
    private class Zip64Eocd(
        val totalEntries: Long,
        val cdSize: Long,
        val cdOffset: Long,
        val zip64EocdOffset: Long,
    )

    /**
     * Follows the ZIP64 EOCD locator (the 20 bytes immediately before the classic EOCD at [eocdAbs]) to
     * the ZIP64 EOCD record and reads the 64-bit entry count and central-directory size/offset.
     */
    private fun readZip64Eocd(
        eocdAbs: Long,
        fileLen: Long,
    ): Zip64Eocd {
        val locatorAbs = eocdAbs - ZIP64_LOCATOR_SIZE
        if (locatorAbs < 0) throw MalformedZipException("no room for a ZIP64 EOCD locator")
        source.seek(locatorAbs)
        val locator = Buffer().apply { write(source.readFully(ZIP64_LOCATOR_SIZE.toInt())) }
        if (locator.readU32LE() != ZIP64_LOCATOR_SIG) throw MalformedZipException("bad ZIP64 EOCD locator signature")
        locator.skip(4) // diskWithZip64Eocd
        val zip64EocdOffset = locator.readU64LE()
        // Operand-first bound: `fileLen - ZIP64_EOCD_SIZE` can't overflow, and a u64 offset near Long.MAX
        // (or negative) is rejected before any seek.
        if (zip64EocdOffset < 0 || zip64EocdOffset > fileLen - ZIP64_EOCD_SIZE) {
            throw MalformedZipException("ZIP64 EOCD offset out of range ($zip64EocdOffset)")
        }
        source.seek(zip64EocdOffset)
        val z = Buffer().apply { write(source.readFully(ZIP64_EOCD_SIZE.toInt())) }
        if (z.readU32LE() != ZIP64_EOCD_SIG) throw MalformedZipException("bad ZIP64 EOCD signature")
        z.skip(ZIP64_EOCD_PRE_COUNTS_LEN) // recordSize, versionMadeBy, versionNeeded, disk, cdStartDisk
        z.readU64LE() // entriesThisDisk
        val totalEntries = z.readU64LE()
        val cdSize = z.readU64LE()
        val cdOffset = z.readU64LE()
        return Zip64Eocd(totalEntries, cdSize, cdOffset, zip64EocdOffset)
    }

    /** Parses [count] central-directory headers out of [cd], resolving ZIP64 overflow per entry. */
    private fun parseHeaders(
        cd: Buffer,
        count: Int,
        fileLen: Long,
    ): List<ZipEntryInfo> {
        val entries = ArrayList<ZipEntryInfo>()
        repeat(count) {
            if (cd.size < CDH_FIXED_SIZE) throw MalformedZipException("central-directory header truncated")
            if (cd.readU32LE() != CDH_SIG) throw MalformedZipException("bad central-directory header signature")
            cd.skip(CDH_VERSIONS_LEN) // versionMadeBy, versionNeeded
            val flags = cd.readU16LE()
            val methodCode = cd.readU16LE()
            cd.skip(CDH_MODTIME_LEN) // modTime, modDate
            val crc = cd.readU32LE()
            val compSize32 = cd.readU32LE()
            val uncompSize32 = cd.readU32LE()
            val nameLen = cd.readU16LE()
            val extraLen = cd.readU16LE()
            val commentLen = cd.readU16LE()
            cd.skip(CDH_DISK_ATTRS_LEN) // diskStart, intAttrs, extAttrs
            val localOffset32 = cd.readU32LE()

            val variableLen = nameLen.toLong() + extraLen + commentLen
            if (cd.size < variableLen) throw MalformedZipException("central-directory entry overruns the directory")
            val name = cd.readByteArray(nameLen).decodeToString()
            val extra = cd.readByteArray(extraLen)
            cd.skip(commentLen.toLong())

            if ((flags and ENCRYPTION_FLAG) !=
                0
            ) {
                throw MalformedZipException("encrypted entry '$name' is not supported")
            }
            val method =
                when (methodCode) {
                    METHOD_STORED -> ZipMethod.STORED
                    METHOD_DEFLATE -> ZipMethod.DEFLATE
                    else -> throw MalformedZipException("unsupported compression method $methodCode for '$name'")
                }

            val (compSize, uncompSize, localOffset) =
                resolveZip64(name, extra, compSize32, uncompSize32, localOffset32)
            if (localOffset < 0 || localOffset > fileLen - LFH_FIXED_SIZE) {
                throw MalformedZipException("local header offset out of range for '$name'")
            }
            if (compSize < 0 || compSize > fileLen || uncompSize < 0) {
                throw MalformedZipException("implausible entry sizes for '$name'")
            }
            entries += ZipEntryInfo(name, method, crc, compSize, uncompSize, localOffset)
        }
        return entries
    }

    /** Resolves the (compSize, uncompSize, localOffset) triple, reading the ZIP64 extra for sentinel slots. */
    private fun resolveZip64(
        name: String,
        extra: ByteArray,
        compSize32: Long,
        uncompSize32: Long,
        localOffset32: Long,
    ): Triple<Long, Long, Long> {
        val hasUncomp = uncompSize32 == ZIP64_U32_MAX
        val hasComp = compSize32 == ZIP64_U32_MAX
        val hasOffset = localOffset32 == ZIP64_U32_MAX
        if (!hasUncomp && !hasComp && !hasOffset) return Triple(compSize32, uncompSize32, localOffset32)

        val z64 = parseZip64ExtraFor(extra, hasUncomp, hasComp, hasOffset)
        val uncompSize = if (hasUncomp) z64.uncompSize.requireField(name, "uncompressed size") else uncompSize32
        val compSize = if (hasComp) z64.compSize.requireField(name, "compressed size") else compSize32
        val localOffset = if (hasOffset) z64.localOffset.requireField(name, "local header offset") else localOffset32
        return Triple(compSize, uncompSize, localOffset)
    }

    private fun Long?.requireField(
        name: String,
        field: String,
    ): Long = this ?: throw MalformedZipException("ZIP64 extra for '$name' is missing the $field")

    /** Reads exactly [count] bytes starting at [offset] into a fresh [Buffer], chunking the disk reads. */
    private fun readExactly(
        offset: Long,
        count: Long,
    ): Buffer {
        source.seek(offset)
        val out = Buffer()
        val chunk = ByteArray(COPY_CHUNK)
        var remaining = count
        while (remaining > 0) {
            val want = minOf(remaining, COPY_CHUNK.toLong()).toInt()
            val n = source.read(chunk, want)
            if (n <= 0) throw MalformedZipException("unexpected end of file reading the central directory")
            out.write(chunk, 0, n)
            remaining -= n
        }
        return out
    }

    /**
     * A [RawSource] over exactly [limit] bytes of the archive starting at a fixed offset. It seeks before
     * every read so multiple entry streams over the shared handle don't corrupt each other, and treats a
     * short disk read (the file is shorter than the central directory promised) as [MalformedZipException].
     */
    private inner class BoundedEntrySource(
        private val name: String,
        startOffset: Long,
        private val limit: Long,
    ) : RawSource {
        private var nextOffset = startOffset
        private var remaining = limit
        private var closed = false

        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            require(byteCount >= 0) { "byteCount < 0: $byteCount" }
            check(!closed) { "entry source is closed" }
            if (remaining == 0L) return -1L
            if (byteCount == 0L) return 0L
            val want = minOf(byteCount, remaining, COPY_CHUNK.toLong()).toInt()
            val buffer = ByteArray(want)
            source.seek(nextOffset)
            val n = source.read(buffer, want)
            if (n <= 0) throw MalformedZipException("entry '$name' data is truncated ($remaining bytes unread)")
            sink.write(buffer, 0, n)
            nextOffset += n
            remaining -= n
            return n.toLong()
        }

        override fun close() {
            // Only releases this view; the shared file handle is owned by the enclosing ZipReader.
            closed = true
        }
    }

    private companion object {
        const val EOCD_FIXED_SIZE: Long = 22L
        const val LFH_FIXED_SIZE: Int = 30

        /** Bytes from the start of an LFH up to (not including) its nameLen field: 30 fixed − 4 sig − 2 − 2. */
        const val LFH_PRE_NAME_LEN_BYTES: Long = 22L
        const val ZIP64_LOCATOR_SIZE: Long = 20L
        const val ZIP64_EOCD_SIZE: Long = 56L
        const val CDH_FIXED_SIZE: Long = 46L

        /** 22-byte EOCD + a 65535-byte max comment — the furthest back the EOCD can sit. */
        const val MAX_EOCD_SEARCH: Long = 65_557L

        const val EOCD_DISK_FIELDS_LEN: Long = 6L // disk(2) + cdStartDisk(2) + entriesThisDisk(2)
        const val ZIP64_EOCD_PRE_COUNTS_LEN: Long = 20L // recordSize(8)+made(2)+need(2)+disk(4)+cdStart(4) after sig
        const val CDH_VERSIONS_LEN: Long = 4L // versionMadeBy(2) + versionNeeded(2)
        const val CDH_MODTIME_LEN: Long = 4L // modTime(2) + modDate(2)
        const val CDH_DISK_ATTRS_LEN: Long = 8L // diskStart(2) + intAttrs(2) + extAttrs(4)

        const val ENCRYPTION_FLAG: Int = 0x0001
        const val METHOD_STORED: Int = 0
        const val METHOD_DEFLATE: Int = 8

        const val COPY_CHUNK: Int = 65_536
    }
}
