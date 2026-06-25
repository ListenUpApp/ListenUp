package com.calypsan.listenup.server.compression

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.util.zip.Inflater

/** A [RawSink] that records whether [close] reached it (a [Buffer]'s own close is a no-op). */
private class CloseTrackingSink(
    private val delegate: RawSink,
) : RawSink {
    var closed = false
        private set

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) = delegate.write(source, byteCount)

    override fun flush() = delegate.flush()

    override fun close() {
        closed = true
        delegate.close()
    }
}

private fun oursDeflate(
    data: ByteArray,
    level: Int,
): ByteArray {
    val out = Buffer()
    DeflateRawSink(out, level).buffered().use {
        it.write(Buffer().apply { write(data) }, data.size.toLong())
    }
    return out.readByteArray()
}

private fun jdkInflateRaw(compressed: ByteArray): ByteArray {
    val inf = Inflater(true)
    inf.setInput(compressed)
    val out = Buffer()
    val buf = ByteArray(8192)
    while (!inf.finished()) {
        val n = inf.inflate(buf)
        if (n == 0 && inf.needsInput()) break
        if (n > 0) out.write(buf, 0, n)
    }
    inf.end()
    return out.readByteArray()
}

class DeflateTest :
    FunSpec({
        test("level 0: java.util.zip Inflater reads our output") {
            checkAll(Arb.byteArray(Arb.int(0..8192), Arb.byte())) { data ->
                jdkInflateRaw(oursDeflate(data, 0)) shouldBe data
            }
        }

        test("level 0: our deflate → our inflate round-trips") {
            checkAll(Arb.byteArray(Arb.int(0..8192), Arb.byte())) { data ->
                val src = Buffer().apply { write(oursDeflate(data, 0)) }
                InflateRawSource(src).buffered().readByteArray() shouldBe data
            }
        }

        test("level 0: empty input produces a valid empty stream") {
            jdkInflateRaw(oursDeflate(ByteArray(0), 0)) shouldBe ByteArray(0)
        }

        test("level 0: a >64KiB input spans multiple stored blocks") {
            val data = ByteArray(200_000) { (it * 13 and 0xFF).toByte() }
            jdkInflateRaw(oursDeflate(data, 0)) shouldBe data
        }

        test("close propagates to the underlying sink (no fd leak)") {
            val tracking = CloseTrackingSink(Buffer())
            DeflateRawSink(tracking, 0).buffered().use {
                it.write(Buffer().apply { write(byteArrayOf(1, 2, 3)) }, 3)
            }
            tracking.closed shouldBe true
        }

        test("rejects an out-of-range level") {
            shouldThrow<IllegalArgumentException> { DeflateRawSink(Buffer(), 10) }
            shouldThrow<IllegalArgumentException> { DeflateRawSink(Buffer(), -1) }
        }

        test("levels 1/6/9: java.util.zip Inflater reads our output, and round-trips") {
            checkAll(Arb.byteArray(Arb.int(0..16384), Arb.byte())) { data ->
                for (level in intArrayOf(1, 6, 9)) {
                    jdkInflateRaw(oursDeflate(data, level)) shouldBe data
                    val src = Buffer().apply { write(oursDeflate(data, level)) }
                    InflateRawSource(src).buffered().readByteArray() shouldBe data
                }
            }
        }

        test("compresses repetitive data smaller than input, and round-trips") {
            val data = ByteArray(100_000) { (it % 8).toByte() }
            val compressed = oursDeflate(data, 6)
            (compressed.size < data.size) shouldBe true
            jdkInflateRaw(compressed) shouldBe data
        }

        test("incompressible random data does not pathologically expand") {
            val rnd = ByteArray(50_000)
            var s = 0x1234_5678
            for (i in rnd.indices) {
                s = s * 1_103_515_245 + 12345
                rnd[i] = (s ushr 16).toByte()
            }
            val compressed = oursDeflate(rnd, 9)
            (compressed.size <= rnd.size + rnd.size / 1000 + 64) shouldBe true
            jdkInflateRaw(compressed) shouldBe rnd
        }

        test("edge inputs at every level: empty, single byte, all-same") {
            for (data in listOf(ByteArray(0), byteArrayOf(42), ByteArray(1000) { 7 })) {
                for (level in intArrayOf(0, 1, 6, 9)) {
                    jdkInflateRaw(oursDeflate(data, level)) shouldBe data
                }
            }
        }

        test("multi-MB mixed-entropy input round-trips through java.util.zip and ours") {
            // ~4 MiB of long-range repetition (distances toward 32 KiB across many windows) interleaved
            // with PRNG noise (a dense literal distribution) — a backup-shaped input the ≤16 KB oracle
            // can't reach. Forces a real dynamic block + large-distance matches end-to-end.
            val size = 4 * 1024 * 1024
            val data = ByteArray(size)
            var s = 0x9E37_79B9.toInt()
            for (i in data.indices) {
                s = s * 1_103_515_245 + 12345
                data[i] = if (i > 20_000 && s ushr 24 and 0x3 != 0) data[i - 20_000] else (s ushr 16).toByte()
            }
            val compressed = oursDeflate(data, 6)
            (compressed.size < data.size) shouldBe true
            jdkInflateRaw(compressed) shouldBe data
            val src = Buffer().apply { write(compressed) }
            InflateRawSource(src).buffered().readByteArray() shouldBe data
        }

        test("emits output incrementally as blocks fill, before close") {
            val tracking = Buffer()
            val sink = DeflateRawSink(tracking, 6).buffered()
            // write well over one BLOCK_INPUT_SIZE of compressible data WITHOUT closing
            val chunk = ByteArray(3 * 1024 * 1024) { (it % 32).toByte() }
            sink.write(Buffer().apply { write(chunk) }, chunk.size.toLong())
            sink.flush()
            // a non-streaming impl would have written nothing until close(); streaming has emitted block(s)
            (tracking.size > 0L) shouldBe true
            sink.close()
        }

        test("back-references span block boundaries (cross-window match)") {
            // > 2 * BLOCK_INPUT_SIZE so the matcher must reference the previous block's bytes. The copy
            // distance is < 32768 (DEFLATE's hard window) yet large enough that positions near a 1 MiB
            // block boundary reference the prior block — a genuine cross-window back-reference.
            val size = 5 * 1024 * 1024
            val data = ByteArray(size)
            var s = 0x2545_F491
            for (i in data.indices) {
                s = s * 1_103_515_245 + 12345
                // mostly copy from ~30 KiB back (forces matches into the previous window), some fresh noise
                data[i] = if (i > 30_000 && s ushr 24 and 0x7 != 0) data[i - 30_000] else (s ushr 16).toByte()
            }
            val compressed = oursDeflate(data, 6)
            (compressed.size < data.size) shouldBe true
            jdkInflateRaw(compressed) shouldBe data
            InflateRawSource(Buffer().apply { write(compressed) }).buffered().readByteArray() shouldBe data
        }

        test("round-trips when fed via many small write() calls") {
            val data = ByteArray(2 * 1024 * 1024) { (it * 7 xor (it ushr 3) and 0xFF).toByte() }
            val out = Buffer()
            DeflateRawSink(out, 6).buffered().use { sink ->
                var off = 0
                while (off < data.size) {
                    val n = minOf(1000, data.size - off)
                    sink.write(Buffer().apply { write(data, off, off + n) }, n.toLong())
                    off += n
                }
            }
            val compressed = out.readByteArray()
            jdkInflateRaw(compressed) shouldBe data
            InflateRawSource(Buffer().apply { write(compressed) }).buffered().readByteArray() shouldBe data
        }

        test("heterogeneous multi-block stream: dynamic then stored then dynamic round-trips") {
            // repetitive(1 MiB) → dynamic(non-final); incompressible(1 MiB) → stored(non-final);
            // repetitive(0.5 MiB) → dynamic(final). Exercises the dynamic→stored partial-bit carry, the
            // stored→dynamic transition, and a non-block-size-multiple total — the mid-stream stored
            // fallback a real backup hits on its already-compressed (m4b/mp3) regions.
            val rep1 = ByteArray(1024 * 1024) { (it % 8).toByte() }
            val incompressible =
                ByteArray(1024 * 1024).also {
                    var s = 0x5BD1_E995
                    for (i in it.indices) {
                        s = s * 1_103_515_245 + 12345
                        it[i] = (s ushr 16).toByte()
                    }
                }
            val rep2 = ByteArray(512 * 1024) { (it % 8 + 1).toByte() }
            val data = rep1 + incompressible + rep2
            val compressed = oursDeflate(data, 6)
            jdkInflateRaw(compressed) shouldBe data
            InflateRawSource(Buffer().apply { write(compressed) }).buffered().readByteArray() shouldBe data
        }

        test("level 0: a multi-window (>2 blocks) input round-trips with BFINAL only on the last") {
            val data = ByteArray(2 * 1024 * 1024 + 777) { (it * 31 and 0xFF).toByte() }
            jdkInflateRaw(oursDeflate(data, 0)) shouldBe data
            InflateRawSource(Buffer().apply { write(oursDeflate(data, 0)) }).buffered().readByteArray() shouldBe data
        }
    })
