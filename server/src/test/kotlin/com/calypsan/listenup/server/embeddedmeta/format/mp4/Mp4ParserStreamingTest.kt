package com.calypsan.listenup.server.embeddedmeta.format.mp4

import com.calypsan.listenup.client.core.AppResult
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
