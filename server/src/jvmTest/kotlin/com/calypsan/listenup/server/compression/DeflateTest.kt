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
    })
