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

        test("encodeZip64Extra returns an empty array when all fields are null") {
            encodeZip64Extra(uncompSize = null, compSize = null, localOffset = null).size shouldBe 0
        }

        test("findEocdOffset throws (not OOB) on a sub-22-byte buffer") {
            io.kotest.assertions.throwables
                .shouldThrow<MalformedZipException> { findEocdOffset(ByteArray(0)) }
            io.kotest.assertions.throwables
                .shouldThrow<MalformedZipException> { findEocdOffset(ByteArray(3) { 0 }) }
        }

        test("parseZip64Extra on an empty array returns all null without throwing") {
            val parsed = parseZip64Extra(ByteArray(0))
            parsed.uncompSize shouldBe null
            parsed.compSize shouldBe null
            parsed.localOffset shouldBe null
        }

        test("parseZip64Extra on a truncated 0x0001 record returns all null (no OOB)") {
            // id=0x0001, dataSize claims 16 bytes (two fields), but only 4 data bytes present.
            val truncated = byteArrayOf(0x01, 0x00, 0x10, 0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
            val parsed = parseZip64Extra(truncated)
            parsed.uncompSize shouldBe null
            parsed.compSize shouldBe null
            parsed.localOffset shouldBe null
        }

        test("parseZip64ExtraFor assigns a lone compSize value (sentinel-aware, not positional)") {
            // A CDH where only compSize == 0xFFFFFFFF, so the extra carries exactly ONE 8-byte value
            // (the real compressed size). A positional parse would mis-assign it to uncompSize.
            val extra = encodeZip64Extra(uncompSize = null, compSize = 4_500_000_000L, localOffset = null)
            val parsed = parseZip64ExtraFor(extra, hasUncomp = false, hasComp = true, hasOffset = false)
            parsed.compSize shouldBe 4_500_000_000L
            parsed.uncompSize shouldBe null
            parsed.localOffset shouldBe null
        }

        test("parseZip64ExtraFor assigns values in (uncomp, comp, offset) order to the sentinel fields") {
            // uncompSize and localOffset are sentinels (comp is a real 32-bit value): the extra holds two
            // values, the first → uncompSize, the second → localOffset.
            val extra = encodeZip64Extra(uncompSize = 9_000_000_000L, compSize = null, localOffset = 7_000_000_000L)
            val parsed = parseZip64ExtraFor(extra, hasUncomp = true, hasComp = false, hasOffset = true)
            parsed.uncompSize shouldBe 9_000_000_000L
            parsed.compSize shouldBe null
            parsed.localOffset shouldBe 7_000_000_000L
        }
    })
