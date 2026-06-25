package com.calypsan.listenup.server.compression

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

class Crc32Test :
    FunSpec({
        test("matches java.util.zip.CRC32 over random inputs") {
            checkAll(Arb.byteArray(Arb.int(0..4096), Arb.byte())) { bytes ->
                val ours = Crc32().apply { update(bytes) }.value
                val jdk =
                    java.util.zip
                        .CRC32()
                        .apply { update(bytes) }
                        .value
                ours shouldBe jdk
            }
        }

        test("chunked update equals whole update") {
            val bytes = ByteArray(1000) { (it * 31 + 7).toByte() }
            val whole = Crc32().apply { update(bytes) }.value
            val chunked =
                Crc32()
                    .apply {
                        update(bytes, 0, 400)
                        update(bytes, 400, 600)
                    }.value
            whole shouldBe chunked
        }

        test("empty input is 0") {
            Crc32().value shouldBe 0L
        }
    })
