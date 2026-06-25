package com.calypsan.listenup.server.compression.zip

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

class ZipFormatTest :
    FunSpec({
        test("u16/u32/u64 little-endian round-trip") {
            val b = Buffer()
            b.writeU16LE(0x1234)
            b.writeU32LE(0x89AB_CDEFL)
            b.writeU64LE(0x0102_0304_0506_0708L)
            b.readU16LE() shouldBe 0x1234
            b.readU32LE() shouldBe 0x89AB_CDEFL
            b.readU64LE() shouldBe 0x0102_0304_0506_0708L
        }

        test("u32 reads back as unsigned (high bit set)") {
            val b = Buffer()
            b.writeU32LE(0xFFFF_FFFFL)
            b.readU32LE() shouldBe 0xFFFF_FFFFL
        }

        test("ZIP64 extra encodes only the overflowed fields and decodes back") {
            val extra = encodeZip64Extra(uncompSize = 5_000_000_000L, compSize = 4_500_000_000L, localOffset = null)
            val parsed = parseZip64Extra(extra)
            parsed.uncompSize shouldBe 5_000_000_000L
            parsed.compSize shouldBe 4_500_000_000L
            parsed.localOffset shouldBe null
            val head = Buffer().apply { write(extra) }
            head.readU16LE() shouldBe 0x0001 // header id
            head.readU16LE() shouldBe 16 // dataSize = 2 * 8
        }

        test("ZIP64 extra with all three fields present") {
            val extra = encodeZip64Extra(9_000_000_000L, 8_000_000_000L, 7_000_000_000L)
            val parsed = parseZip64Extra(extra)
            parsed.uncompSize shouldBe 9_000_000_000L
            parsed.compSize shouldBe 8_000_000_000L
            parsed.localOffset shouldBe 7_000_000_000L
        }

        test("parseZip64Extra finds the 0x0001 record among multiple extra records") {
            // a 4-byte unknown extra record (id 0x9999, size 0) followed by our zip64 record
            val b = Buffer()
            b.writeU16LE(0x9999)
            b.writeU16LE(0)
            b.write(encodeZip64Extra(uncompSize = 5_000_000_000L, compSize = null, localOffset = null))
            val parsed = parseZip64Extra(b.readByteArray())
            parsed.uncompSize shouldBe 5_000_000_000L
            parsed.compSize shouldBe null
        }

        test("findEocdOffset locates the EOCD signature scanning back from the end") {
            val b = Buffer()
            b.write(ByteArray(100) { 0 })
            b.writeU32LE(0x0605_4b50L) // EOCD signature at offset 100
            b.write(ByteArray(18) { 0 }) // rest of EOCD (commentLen=0)
            findEocdOffset(b.readByteArray()) shouldBe 100L
        }

        test("findEocdOffset throws on a buffer with no EOCD") {
            io.kotest.assertions.throwables.shouldThrow<MalformedZipException> {
                findEocdOffset(ByteArray(50) { 0x11 })
            }
        }
    })
