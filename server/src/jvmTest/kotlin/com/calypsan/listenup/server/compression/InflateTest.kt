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
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.util.zip.Deflater

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
    })
