package com.calypsan.listenup.server.embeddedmeta.format.mp4

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.embeddedmeta.SeekableAudioSource
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp4File
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThan as intShouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException

/**
 * Regression coverage for the streaming-MP4 parse path.
 *
 * Uses a synthetic [SeekableAudioSource] that reports a multi-gigabyte
 * length (simulating a real M4B audiobook) but only carries the metadata
 * region in memory; any read into the synthetic mdat returns zeros via a
 * small reusable buffer, with strict accounting on total bytes pulled.
 *
 * Pre-fix behavior (commented in the assertion comment): the parser
 * called `source.readFully(source.length.toInt())` on this source, which
 * would overflow `Int` on lengths > 2 GB and, before that, try to
 * allocate the full claimed file size as a `ByteArray`. After the fix
 * the parser reads only the moov region (a few KB for the synthetic
 * fixture) plus tiny atom headers.
 */
class Mp4ParserStreamingTest :
    FunSpec({
        test("parser handles non-fast-start MP4 with mdat size > 2GB (Stormlight regression)") {
            // Real-world layout from `/mnt/Igni/Audiobooks/Brandon Sanderson/.../The Way of Kings.m4b`:
            // `ftyp` (32 bytes) → `mdat` (size 0x950eddb5 ≈ 2.5 GB) → `moov` at the end.
            // The streaming walker must traverse past the mdat header and read moov from the
            // file-absolute offset its size implies. Pre-fix the walker rejected the mdat header
            // because `size32 = 0x950eddb5` was interpreted as a *signed* Int (negative) and
            // failed the `< 8` guard, returning null and producing AUDIO_META_CORRUPT_HEADER on
            // 32 of 1,123 real M4Bs.
            val moov =
                buildMp4File {
                    ftyp(brand = "M4B ")
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 60_000)
                        udta { meta { tag(atomType = "©nam", value = "Way of Kings Test") } }
                    }
                }.let { fullPrefix ->
                    // buildMp4File emits ftyp + moov + mdat; we want only the moov bytes for our
                    // synthetic file, so split out the moov region. The first atom is ftyp at
                    // offset 0; the second atom is moov.
                    val ftypSize =
                        ((fullPrefix[0].toInt() and 0xFF) shl 24) or
                            ((fullPrefix[1].toInt() and 0xFF) shl 16) or
                            ((fullPrefix[2].toInt() and 0xFF) shl 8) or
                            (fullPrefix[3].toInt() and 0xFF)
                    val moovSize =
                        ((fullPrefix[ftypSize].toInt() and 0xFF) shl 24) or
                            ((fullPrefix[ftypSize + 1].toInt() and 0xFF) shl 16) or
                            ((fullPrefix[ftypSize + 2].toInt() and 0xFF) shl 8) or
                            (fullPrefix[ftypSize + 3].toInt() and 0xFF)
                    fullPrefix.copyOfRange(ftypSize, ftypSize + moovSize)
                }
            // Synthetic file: ftyp (32 bytes) + mdat header (8 bytes claiming 2.6 GB) + moov.
            val mdatPayloadSize = 2_700_000_000L // > Int.MAX_VALUE
            val mdatTotalSize = mdatPayloadSize + 8 // including 8-byte header
            val ftypBytes =
                byteArrayOf(
                    0,
                    0,
                    0,
                    0x20.toByte(),
                    'f'.code.toByte(),
                    't'.code.toByte(),
                    'y'.code.toByte(),
                    'p'.code.toByte(),
                    'M'.code.toByte(),
                    '4'.code.toByte(),
                    'B'.code.toByte(),
                    ' '.code.toByte(),
                    0,
                    0,
                    0,
                    0,
                    'M'.code.toByte(),
                    '4'.code.toByte(),
                    'B'.code.toByte(),
                    ' '.code.toByte(),
                    'i'.code.toByte(),
                    's'.code.toByte(),
                    'o'.code.toByte(),
                    'm'.code.toByte(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0, // padding to 32 bytes
                )
            val mdatHeader =
                byteArrayOf(
                    ((mdatTotalSize ushr 24) and 0xFF).toByte(),
                    ((mdatTotalSize ushr 16) and 0xFF).toByte(),
                    ((mdatTotalSize ushr 8) and 0xFF).toByte(),
                    (mdatTotalSize and 0xFFL).toByte(),
                    'm'.code.toByte(),
                    'd'.code.toByte(),
                    'a'.code.toByte(),
                    't'.code.toByte(),
                )
            val moovOffset = ftypBytes.size + mdatTotalSize
            val totalLength = moovOffset + moov.size
            val source =
                NonFastStartSource(
                    ftyp = ftypBytes,
                    mdatHeader = mdatHeader,
                    moovOffset = moovOffset,
                    moov = moov,
                    totalLength = totalLength,
                )

            val result = Mp4Parser().parse(source)
            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.format shouldBe AudioFormat.Mp4
            success.data.tags.title shouldBe "Way of Kings Test"

            // Bound check: total bytes read must be tiny (ftyp + mdat header + moov), not 2.7 GB.
            val budget: Long = (ftypBytes.size + mdatHeader.size + moov.size + 1024).toLong()
            source.totalBytesRead shouldBeLessThan budget
        }

        test("parser does not allocate the full file length when mdat is huge") {
            val realPrefix =
                buildMp4File {
                    ftyp(brand = "M4B ", compatibleBrands = listOf("M4B ", "isom"))
                    moov {
                        mvhd(timescale = 1000, durationInTimescale = 60_000)
                        udta {
                            meta {
                                tag(atomType = "©nam", value = "Streaming Test Title")
                                tag(atomType = "©ART", value = "Test Author")
                            }
                        }
                    }
                }
            val sentinelLength = 8L * 1024 * 1024 * 1024 // 8 GB synthetic file
            val source = HugeMdatSource(realPrefix = realPrefix, totalLength = sentinelLength)

            val result = Mp4Parser().parse(source)
            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.format shouldBe AudioFormat.Mp4
            success.data.tags.title shouldBe "Streaming Test Title"
            success.data.tags.authors shouldBe listOf("Test Author")

            // Bound check: total bytes pulled must be << real prefix size.
            // Streaming walker reads top-level atom headers (8-16 bytes each)
            // until it finds moov, then reads moov payload once. For this
            // fixture that's a few hundred bytes in headers + moov size.
            val budget: Long = (realPrefix.size + 1024).toLong()
            source.totalBytesRead shouldBeLessThan budget
            // Hard upper bound: anything claiming > Int.MAX_VALUE would
            // overflow before reaching the parser; the streaming path
            // never asks for the synthetic length.
            source.maxSingleReadBytes intShouldBeLessThan Int.MAX_VALUE
        }
    })

/**
 * Synthetic [SeekableAudioSource] simulating a non-fast-start MP4 layout
 * (`ftyp` → `mdat` larger than 2 GB → `moov` at the end). Holds only the
 * tiny header + moov regions in memory; the synthetic mdat payload is
 * never materialised. Tracks total bytes pulled to assert the parser
 * doesn't read the audio region.
 */
private class NonFastStartSource(
    private val ftyp: ByteArray,
    private val mdatHeader: ByteArray,
    private val moovOffset: Long,
    private val moov: ByteArray,
    private val totalLength: Long,
) : SeekableAudioSource {
    var totalBytesRead: Long = 0
        private set

    private var pos: Long = 0

    override val length: Long get() = totalLength

    override fun position(): Long = pos

    override fun seek(offset: Long) {
        require(offset in 0..totalLength) { "seek out of range: $offset" }
        pos = offset
    }

    override fun read(
        into: ByteArray,
        count: Int,
    ): Int {
        if (pos >= totalLength) return -1
        val n = (totalLength - pos).coerceAtMost(count.toLong()).toInt()
        copyInto(into, 0, n)
        pos += n
        totalBytesRead += n
        return n
    }

    override fun readFully(count: Int): ByteArray {
        if (pos + count > totalLength) throw IOException("EOF: pos=$pos count=$count length=$totalLength")
        val buf = ByteArray(count)
        copyInto(buf, 0, count)
        pos += count
        totalBytesRead += count
        return buf
    }

    override fun close() { /* no-op */ }

    private fun copyInto(
        dst: ByteArray,
        dstOffset: Int,
        count: Int,
    ) {
        val ftypEnd = ftyp.size.toLong()
        val mdatHeaderEnd = ftypEnd + mdatHeader.size
        var written = 0
        while (written < count) {
            val cur = pos + written
            val remaining = count - written
            when {
                cur < ftypEnd -> {
                    val copyN = minOf(remaining.toLong(), ftypEnd - cur).toInt()
                    System.arraycopy(ftyp, cur.toInt(), dst, dstOffset + written, copyN)
                    written += copyN
                }

                cur < mdatHeaderEnd -> {
                    val copyN = minOf(remaining.toLong(), mdatHeaderEnd - cur).toInt()
                    System.arraycopy(mdatHeader, (cur - ftypEnd).toInt(), dst, dstOffset + written, copyN)
                    written += copyN
                }

                cur >= moovOffset -> {
                    val copyN = minOf(remaining.toLong(), moov.size.toLong() - (cur - moovOffset)).toInt()
                    System.arraycopy(moov, (cur - moovOffset).toInt(), dst, dstOffset + written, copyN)
                    written += copyN
                }

                else -> {
                    // Synthetic mdat payload: zeros (dst is already zero-initialised).
                    val copyN = minOf(remaining.toLong(), moovOffset - cur).toInt()
                    written += copyN
                }
            }
        }
    }
}

/**
 * Synthetic [SeekableAudioSource] that lies about its total length.
 *
 * Bytes inside [realPrefix] are returned verbatim; bytes anywhere past
 * its end are returned as zeros without ever materialising a giant
 * ByteArray. Used to prove that the parser never asks for those bytes.
 */
private class HugeMdatSource(
    private val realPrefix: ByteArray,
    private val totalLength: Long,
) : SeekableAudioSource {
    var totalBytesRead: Long = 0
        private set
    var maxSingleReadBytes: Int = 0
        private set

    private var pos: Long = 0

    override val length: Long get() = totalLength

    override fun position(): Long = pos

    override fun seek(offset: Long) {
        require(offset in 0..totalLength) { "seek out of range: $offset" }
        pos = offset
    }

    override fun read(
        into: ByteArray,
        count: Int,
    ): Int {
        val n = doRead(into, 0, count)
        if (n > 0) {
            pos += n
            totalBytesRead += n
            maxSingleReadBytes = maxOf(maxSingleReadBytes, n)
        }
        return n
    }

    override fun readFully(count: Int): ByteArray {
        require(count >= 0) { "negative count: $count" }
        if (pos + count > totalLength) throw IOException("EOF: pos=$pos count=$count length=$totalLength")
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = doRead(buf, read, count - read)
            if (n <= 0) throw IOException("short read: pos=$pos read=$read of $count")
            read += n
        }
        pos += count
        totalBytesRead += count
        maxSingleReadBytes = maxOf(maxSingleReadBytes, count)
        return buf
    }

    override fun close() { /* no-op */ }

    private fun doRead(
        dst: ByteArray,
        dstOffset: Int,
        max: Int,
    ): Int {
        if (pos >= totalLength) return -1
        val available = (totalLength - pos).coerceAtMost(max.toLong()).toInt()
        if (pos < realPrefix.size) {
            val fromPrefix = minOf(available, realPrefix.size - pos.toInt())
            System.arraycopy(realPrefix, pos.toInt(), dst, dstOffset, fromPrefix)
            // Anything past the prefix we read inside this call stays zero
            // (dst was zero-initialised); just return the requested count.
            return available
        }
        // Past the real prefix — synthetic zeros without allocating.
        return available
    }
}
