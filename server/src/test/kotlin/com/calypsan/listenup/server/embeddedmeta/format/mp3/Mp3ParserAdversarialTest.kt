@file:Suppress("MagicNumber") // Binary-format constants — readability beats named constants.

package com.calypsan.listenup.server.embeddedmeta.format.mp3

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.embeddedmeta.SeekableAudioSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Adversarial memory-safety coverage for [Mp3Parser].
 *
 * The corpus that motivated Scanner Polish is gone — the 32 M4B
 * "corrupt-header" files and the 1 OOM-ing MP3 were genuinely corrupt and
 * have been cleaned up. But a malformed file driving the JVM to
 * `OutOfMemoryError` or escaping the parser with an uncaught exception is a
 * *robustness* defect independent of any specific file. This suite proves
 * structurally that no synthetic malformed MP3 can do either: every
 * declared-size field is fed an adversarial value, and the parser must
 * answer with a typed [AppResult.Failure] (or a graceful empty-tags
 * [AppResult.Success]) — never a huge allocation, never a thrown exception.
 *
 * Why a passing test *is* the proof: a "declares 5 GB tag in a 1 KB file"
 * input only survives if the guard rejects it before the `readFully`.
 * If a guard were missing, the parser would attempt the multi-GB
 * `readFully` / `copyOfRange` and the test would OOM or hang — so green here
 * means the guard held.
 *
 * Each test runs through the real [Mp3Parser] via [byteSource] (defined in
 * `Mp3ParserTest`); the malformed bytes are hand-built because the
 * fixture DSL [com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File]
 * deliberately only emits *well-formed* tags.
 */
class Mp3ParserAdversarialTest :
    FunSpec({
        val parser = Mp3Parser()

        /**
         * Build a 10-byte ID3v2 header for [version] declaring a body of
         * [declaredBodySize] sync-safe bytes. The total declared tag size the
         * parser computes is `10 + declaredBodySize`.
         */
        fun id3v2Header(
            version: Int,
            declaredBodySize: Int,
        ): ByteArray =
            byteArrayOf(
                0x49,
                0x44,
                0x33, // "ID3"
                version.toByte(),
                0x00, // minor
                0x00, // flags
                ((declaredBodySize ushr 21) and 0x7F).toByte(),
                ((declaredBodySize ushr 14) and 0x7F).toByte(),
                ((declaredBodySize ushr 7) and 0x7F).toByte(),
                (declaredBodySize and 0x7F).toByte(),
            )

        test("ID3v2 header declaring a tag far larger than the file does not OOM") {
            // Header claims the maximum a sync-safe int can encode (~256 MB body)
            // but the file is only the 10-byte header itself. A missing
            // `tagSize > source.length` guard would attempt a ~256 MB readFully.
            // Guard verified: Mp3Parser.readId3v2Region rejects via
            // `tagSize > source.length` -> returns null -> empty tags.
            val bytes = id3v2Header(version = 4, declaredBodySize = 0x0FFFFFFF)

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.format shouldBe AudioFormat.Mp3
            // No ID3v2 tag could be read; parser falls back to empty tags.
            success.data.tags.title shouldBe null
        }

        test("ID3v2 declared tag size near Int.MAX_VALUE does not OOM") {
            // The sync-safe size field is 4 x 7 bits = 28 bits, so the largest
            // tag size `peekTagSize` can ever produce is 0x0FFFFFFF + 10 — it
            // structurally cannot reach Int.MAX_VALUE, so the `Int` arithmetic
            // never overflows to a negative size. We still exercise the maximum
            // encodable value here to lock that property in: a 256 MB declared
            // tag is over the 200 MB soft limit AND over the file length, so
            // the parser rejects it instantly.
            val bytes = id3v2Header(version = 4, declaredBodySize = 0x0FFFFFFF)

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            // Returns gracefully — never a negative-size allocation.
            result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
        }

        test("ID3v2 declared tag over the 200 MB soft limit but within file length is rejected") {
            // A pathological-but-self-consistent file: header declares a
            // ~256 MB tag and the file genuinely is that long. The soft limit
            // (200 MB) is the line of defence here — without it the parser
            // would readFully ~256 MB. We assert structurally by NOT
            // materialising 256 MB: a synthetic source reports the length but
            // only holds the header, and any tag readFully past the soft limit
            // must never happen. Guard: `tagSize > ID3V2_SOFT_LIMIT_BYTES`.
            val declaredBody = 0x0FFFFFFF // ~256 MB
            val header = id3v2Header(version = 4, declaredBodySize = declaredBody)
            val claimedLength = 10L + declaredBody
            val source = HeaderOnlySource(header = header, claimedLength = claimedLength)

            val result = runBlocking { parser.parse(source) }

            // Soft limit rejects the oversized tag -> empty tags, no big read.
            result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            // Proof: the parser never asked for the multi-hundred-MB tag body.
            // The only bounded reads the parser legitimately makes here are
            // the 10-byte ID3v2 header probe, the 128-byte ID3v1 footer probe,
            // and the 64 KB MPEG-sync sniff window (SNIFF_WINDOW_BYTES). The
            // budget is that sniff window plus headroom — orders of magnitude
            // below the 256 MB the declared tag would have demanded.
            val readBudget = 64 * 1024 + 1024
            (source.maxSingleReadBytes < readBudget) shouldBe true
        }

        test("ID3v2 frame declaring a frameSize past the tag bounds does not OOM or throw") {
            // A well-formed 10-byte ID3v2.4 header whose body is one frame
            // header ("TIT2") that lies: it declares a frameSize far larger
            // than the tag. Id3v2Reader.read must bail via
            // `frameSize < 0 || frameDataEnd > tagEnd` rather than
            // `copyOfRange`-ing past the buffer (IndexOutOfBounds) — or, if
            // the addition overflows Int, copyOfRange with end < start
            // (IllegalArgumentException). Either escape would be an uncaught
            // exception; the guard must catch it.
            val frameHeader =
                byteArrayOf(
                    'T'.code.toByte(),
                    'I'.code.toByte(),
                    'T'.code.toByte(),
                    '2'.code.toByte(),
                    // Sync-safe frameSize = 0x0FFFFFFF (~256 MB), wildly past the tag.
                    0x7F,
                    0x7F,
                    0x7F,
                    0x7F,
                    0x00,
                    0x00, // frame flags
                )
            val bytes = id3v2Header(version = 4, declaredBodySize = frameHeader.size) + frameHeader

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            // Frame is skipped; parse still succeeds with empty tags.
            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.tags.title shouldBe null
        }

        test("ID3v2.3 frame with a big-endian frameSize that overflows Int on addition is handled") {
            // ID3v2.3 frame sizes are plain big-endian 32-bit, NOT sync-safe —
            // so a frame header can declare a frameSize close to Int.MAX_VALUE.
            // `frameDataEnd = frameDataStart + frameSize` then overflows Int to
            // a negative number. The guard `frameSize < 0 || frameDataEnd > tagEnd`
            // catches the negative-end case (frameDataEnd < 0 is not > tagEnd,
            // but a negative copyOfRange end < start throws) — so the parser
            // must `break` on `frameSize < 0` OR the overflowed end. Either way:
            // no uncaught exception.
            val frameHeader =
                byteArrayOf(
                    'T'.code.toByte(),
                    'I'.code.toByte(),
                    'T'.code.toByte(),
                    '2'.code.toByte(),
                    // Big-endian frameSize = 0x7FFFFFFF — near Int.MAX_VALUE.
                    0x7F,
                    0xFF.toByte(),
                    0xFF.toByte(),
                    0xFF.toByte(),
                    0x00,
                    0x00,
                )
            val bytes = id3v2Header(version = 3, declaredBodySize = frameHeader.size) + frameHeader

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            // Must not throw; parse returns gracefully.
            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.tags.title shouldBe null
        }

        test("CHAP sub-frame with a big-endian subSize that overflows Int on addition is handled") {
            // A CHAP frame whose embedded TIT2 sub-frame (ID3v2.3, plain
            // big-endian size) declares a subSize near Int.MAX_VALUE.
            // `Id3v2Reader.extractChapterTitle` does
            // `subframes.copyOfRange(dataStart, dataOffset + subSize)`; the
            // addition overflows Int to a negative end. The guard
            // `subSize <= 0 || dataEnd > subframes.size` must also catch the
            // overflowed end, else copyOfRange throws with `end < start`.
            // CHAP body: elementId\0 + startMs(4) + endMs(4) + startOff(4)
            //            + endOff(4) + TIT2 sub-frame header (10 bytes).
            val chapBody =
                "ch1".toByteArray(Charsets.ISO_8859_1) +
                    byteArrayOf(0x00) + // elementId terminator
                    ByteArray(16) + // startMs + endMs + startOffset + endOffset
                    // Embedded TIT2 sub-frame header with an overflowing size.
                    byteArrayOf(
                        'T'.code.toByte(),
                        'I'.code.toByte(),
                        'T'.code.toByte(),
                        '2'.code.toByte(),
                        0x7F,
                        0xFF.toByte(),
                        0xFF.toByte(),
                        0xFF.toByte(), // BE subSize ~ Int.MAX_VALUE
                        0x00,
                        0x00, // sub-frame flags
                    )
            // Outer CHAP frame header (ID3v2.3, big-endian frame size).
            val chapFrame =
                byteArrayOf(
                    'C'.code.toByte(),
                    'H'.code.toByte(),
                    'A'.code.toByte(),
                    'P'.code.toByte(),
                    ((chapBody.size ushr 24) and 0xFF).toByte(),
                    ((chapBody.size ushr 16) and 0xFF).toByte(),
                    ((chapBody.size ushr 8) and 0xFF).toByte(),
                    (chapBody.size and 0xFF).toByte(),
                    0x00,
                    0x00,
                ) + chapBody
            val bytes = id3v2Header(version = 3, declaredBodySize = chapFrame.size) + chapFrame

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            // Must not throw; the malformed sub-frame is skipped.
            result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
        }

        test("truncated ID3v2 file — header promises more bytes than are present — does not throw") {
            // Header declares a 500-byte body but the file carries only 50
            // bytes past the header. `tagSize (510) > source.length (60)` so
            // readId3v2Region returns null before any oversized read.
            val header = id3v2Header(version = 4, declaredBodySize = 500)
            val bytes = header + ByteArray(50) // 60 bytes total, header promised 510

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            val success = result.shouldBeInstanceOf<AppResult.Success<EmbeddedAudioMetadata>>()
            success.data.tags.title shouldBe null
        }

        test("file shorter than the 10-byte ID3v2 header is handled gracefully") {
            // 4 bytes only — below ID3V2_HEADER_SIZE. readId3v2Region must
            // bail on `source.length < ID3V2_HEADER_SIZE` before readFully(10).
            val bytes = byteArrayOf(0x49, 0x44, 0x33, 0x04)

            val result = runBlocking { parser.parse(byteSource(bytes)) }

            // No format magic for a 4-byte stub means dispatch never reaches
            // here through EmbeddedMetadataParser, but Mp3Parser called
            // directly must still not throw.
            result.shouldBeInstanceOf<AppResult<EmbeddedAudioMetadata>>()
        }
    })

/**
 * Synthetic [SeekableAudioSource] that reports a large [claimedLength] but
 * only physically holds the [header] bytes. Reads inside the header are
 * served verbatim; reads past it are zero-filled *without ever
 * materialising the multi-hundred-MB body* — exactly like the
 * `Mp3ParserStreamingTest` sources. The MP3 parser legitimately probes a
 * small 128-byte ID3v1 footer near the end, so a read past the header is
 * not by itself a defect; the proof of memory-safety is
 * [maxSingleReadBytes], which records the largest single `readFully` — a
 * missing soft-limit guard would show up as a multi-hundred-MB value here.
 */
private class HeaderOnlySource(
    private val header: ByteArray,
    private val claimedLength: Long,
) : SeekableAudioSource {
    var maxSingleReadBytes: Int = 0
        private set

    private var pos: Long = 0

    override val length: Long get() = claimedLength

    override fun position(): Long = pos

    override fun seek(offset: Long) {
        require(offset in 0..claimedLength) { "seek out of range: $offset" }
        pos = offset
    }

    override fun read(
        into: ByteArray,
        count: Int,
    ): Int {
        if (pos >= claimedLength) return -1
        maxSingleReadBytes = maxOf(maxSingleReadBytes, count)
        val n = (claimedLength - pos).coerceAtMost(count.toLong()).toInt()
        copyHeaderRegion(into, n)
        pos += n
        return n
    }

    override fun readFully(count: Int): ByteArray {
        maxSingleReadBytes = maxOf(maxSingleReadBytes, count)
        if (pos + count > claimedLength) {
            throw IOException("EOF: pos=$pos count=$count length=$claimedLength")
        }
        val buf = ByteArray(count)
        copyHeaderRegion(buf, count)
        pos += count
        return buf
    }

    override fun close() { /* no-op */ }

    /** Copy the overlap with [header] into [dst]; bytes past it stay zero. */
    private fun copyHeaderRegion(
        dst: ByteArray,
        count: Int,
    ) {
        if (pos < header.size) {
            val fromHeader = minOf(count, header.size - pos.toInt())
            System.arraycopy(header, pos.toInt(), dst, 0, fromHeader)
        }
    }
}
