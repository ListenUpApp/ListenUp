package com.calypsan.listenup.server.compression

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.util.zip.Deflater

private fun inflateBytes(bytes: Buffer): ByteArray = InflateRawSource(bytes).buffered().readByteArray()

private fun jdkDeflateRaw(
    data: ByteArray,
    level: Int,
): ByteArray {
    val d = Deflater(level, true)
    d.setInput(data)
    d.finish()
    val sink = Buffer()
    val buf = ByteArray(8192)
    while (!d.finished()) {
        val n = d.deflate(buf)
        if (n > 0) sink.write(buf, 0, n)
    }
    d.end()
    return sink.readByteArray()
}

private fun oursInflate(compressed: ByteArray): ByteArray {
    val src = Buffer().apply { write(compressed) }
    return InflateRawSource(src).buffered().readByteArray()
}

class InflateTest :
    FunSpec({
        test("inflates java.util.zip Deflater output at every level") {
            checkAll(Arb.byteArray(Arb.int(0..8192), Arb.byte())) { data ->
                for (level in intArrayOf(0, 1, 6, 9)) {
                    oursInflate(jdkDeflateRaw(data, level)) shouldBe data
                }
            }
        }

        test("inflates highly repetitive data (heavy back-references, multi-block)") {
            val data = ByteArray(200_000) { (it % 4).toByte() }
            oursInflate(jdkDeflateRaw(data, 9)) shouldBe data
        }

        test("inflates a chunked-read source (streaming)") {
            val data = ByteArray(70_000) { (it * 7 and 0xFF).toByte() }
            val compressed = jdkDeflateRaw(data, 6)
            // read 1 byte at a time to stress the incremental readAtMostTo path
            val src = Buffer().apply { write(compressed) }
            val inflater = InflateRawSource(src)
            val out = Buffer()
            val one = Buffer()
            while (true) {
                val n = inflater.readAtMostTo(one, 1)
                if (n < 0) break
                out.write(one, one.size)
            }
            out.readByteArray() shouldBe data
        }

        test("rejects a truncated stream") {
            val good = jdkDeflateRaw(ByteArray(5000) { it.toByte() }, 6)
            shouldThrow<MalformedDeflateException> { oursInflate(good.copyOf(good.size / 2)) }
        }

        test("rejects a back-reference distance before start of output (I1)") {
            val out = Buffer()
            val w = BitWriter(out)
            w.writeBits(1, 1) // BFINAL = 1
            w.writeBits(1, 2) // BTYPE = 01 (fixed)
            val (litCodes, litLens) = canonicalCodes(FIXED_LITLEN_LENGTHS)
            val (distCodes, distLens) = canonicalCodes(FIXED_DIST_LENGTHS)
            writeHuffmanCode(w, litCodes[257], litLens[257]) // length symbol 257 (len 3, 0 extra)
            writeHuffmanCode(w, distCodes[0], distLens[0]) // distance symbol 0 (distance 1, 0 extra) — but 0 bytes produced
            writeHuffmanCode(w, litCodes[256], litLens[256]) // end-of-block, so the stream parses fully (no truncation)
            w.alignToByte()
            w.flush()
            // The stream is structurally complete, so the ONLY possible error is the distance-too-far guard,
            // not "unexpected end of stream". Before the I1 fix this emitted 3 zero bytes and succeeded.
            val ex = shouldThrow<MalformedDeflateException> { inflateBytes(out) }
            ex.message shouldContain "distance"
        }

        test("rejects reserved block type 3") {
            val out = Buffer()
            val w = BitWriter(out)
            w.writeBits(1, 1) // BFINAL = 1
            w.writeBits(3, 2) // BTYPE = 11 (reserved)
            w.alignToByte()
            w.flush()
            shouldThrow<MalformedDeflateException> { inflateBytes(out) }
        }

        test("rejects a stored block with bad NLEN complement") {
            val out = Buffer()
            val w = BitWriter(out)
            w.writeBits(1, 1) // BFINAL = 1
            w.writeBits(0, 2) // BTYPE = 00 (stored)
            w.alignToByte()
            w.writeBits(5, 16) // LEN = 5
            w.writeBits(0, 16) // NLEN = 0 (wrong; correct would be 0xFFFA)
            w.writeBytes(ByteArray(5))
            w.flush()
            shouldThrow<MalformedDeflateException> { inflateBytes(out) }
        }
    })
