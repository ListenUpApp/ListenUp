package com.calypsan.listenup.server.embeddedmeta.format.mp3

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.io.SeekableSource
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException

/**
 * Regression coverage for the streaming-MP3 parse path.
 *
 * Pre-fix the parser called `source.readFully(source.length.toInt())` on
 * every MP3 — for a 537 MB audiobook (`Stieg Larsson - The Girl Who
 * Played With Fire.mp3`) that allocated 537 MB on the test heap and
 * produced `OutOfMemoryError`. For a > 2 GB MP3, `length.toInt()`
 * silently truncated to a negative size and the read failed before any
 * allocation. After the fix the parser reads only the ID3v2 region, the
 * trailing 128-byte ID3v1 footer, and a small prefix of the audio region
 * for the first MPEG frame header — never the audio body.
 *
 * The synthetic [LargePrefixSource] reports a 3 GB length but holds only
 * the real prefix (ID3v2 + MPEG frame header) in memory; reads past the
 * prefix are zero-filled without allocating. Strict accounting on
 * `totalBytesRead` proves the parser doesn't pull the audio body.
 */
class Mp3ParserStreamingTest :
    FunSpec({
        test("parser does not load whole file for a large MP3") {
            val realPrefix =
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "Streaming Test Title")
                        textFrame("TPE1", "Streaming Test Author")
                    }
                    mpegFrames(durationSeconds = 1, bitrate = 64_000)
                }
            val sentinelLength = 3L * 1024 * 1024 * 1024 // 3 GB > Int.MAX_VALUE
            val source = LargePrefixSource(realPrefix = realPrefix, totalLength = sentinelLength)

            val result = Mp3Parser().parse(source)
            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.format shouldBe AudioFormat.Mp3
            success.data.tags.title shouldBe "Streaming Test Title"
            success.data.tags.authors shouldBe listOf("Streaming Test Author")

            // Bound check: total bytes pulled must be <= ID3v2 region + duration-sniff
            // window + ID3v1 footer probe. The 64 KB sniff is a hard cap for finding
            // the first MPEG sync byte; a few hundred bytes of overhead covers the
            // 10-byte tag-header probe and the 128-byte v1 footer probe. The synthetic
            // file claims 3 GB total, so anything within an order of magnitude of that
            // would mean the parser is reading the audio body.
            val budget: Long = realPrefix.size + 64 * 1024 + 1024L
            source.totalBytesRead shouldBeLessThan budget
        }
    })

private class LargePrefixSource(
    private val realPrefix: ByteArray,
    private val totalLength: Long,
) : SeekableSource {
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
        val n = doRead(into, 0, count)
        if (n > 0) {
            pos += n
            totalBytesRead += n
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
            // Bytes past the prefix stay zero (`dst` was zero-initialised).
        }
        return available
    }
}
