package com.calypsan.listenup.server.compression

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
import java.util.zip.Inflater

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
            val data = ByteArray(200_000) { ((it * 13) and 0xFF).toByte() }
            jdkInflateRaw(oursDeflate(data, 0)) shouldBe data
        }
    })
