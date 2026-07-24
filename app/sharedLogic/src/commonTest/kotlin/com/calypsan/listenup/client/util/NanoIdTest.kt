package com.calypsan.listenup.client.util

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for NanoId generator.
 *
 * Tests cover:
 * - Default ID length (21 characters)
 * - Custom ID length
 * - URL-safe character set
 * - Prefixed ID format
 * - Uniqueness (probabilistic)
 */
class NanoIdTest :
    FunSpec({
        // URL-safe alphabet used by NanoId
        val validChars = "_-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

        // ========== Default Generation Tests ==========

        test("generate returns 21 character ID by default") {
            val id = NanoId.generate()
            id.length shouldBe 21
        }

        test("generate returns URL-safe characters only") {
            val id = NanoId.generate()
            id.all { it in validChars } shouldBe true
        }

        test("generate returns different IDs on each call") {
            val ids = (1..100).map { NanoId.generate() }.toSet()
            withClue("All 100 generated IDs should be unique") { ids.size shouldBe 100 }
        }

        // ========== Custom Size Tests ==========

        test("generate with custom size returns correct length") {
            val id = NanoId.generate(size = 10)
            id.length shouldBe 10
        }

        test("generate with size 1 returns single character") {
            val id = NanoId.generate(size = 1)
            id.length shouldBe 1
            (id[0] in validChars) shouldBe true
        }

        test("generate with large size returns correct length") {
            val id = NanoId.generate(size = 100)
            id.length shouldBe 100
            id.all { it in validChars } shouldBe true
        }

        // ========== Prefixed Generation Tests ==========

        test("generate with prefix returns prefixed ID") {
            val id = NanoId.generate(prefix = "evt")
            id.startsWith("evt-") shouldBe true
            id.length shouldBe 25 // "evt-" (4 chars) + 21 chars
        }

        test("generate with prefix and custom size returns correct format") {
            val id = NanoId.generate(prefix = "usr", size = 10)
            id.startsWith("usr-") shouldBe true
            id.length shouldBe 14 // "usr-" (4 chars) + 10 chars
        }

        test("generate with different prefixes produces different formats") {
            val evtId = NanoId.generate(prefix = "evt")
            val usrId = NanoId.generate(prefix = "usr")
            val bookId = NanoId.generate(prefix = "book")

            evtId.startsWith("evt-") shouldBe true
            usrId.startsWith("usr-") shouldBe true
            bookId.startsWith("book-") shouldBe true
        }

        test("prefixed ID contains only valid characters after prefix") {
            val id = NanoId.generate(prefix = "test")
            val suffix = id.removePrefix("test-")
            suffix.all { it in validChars } shouldBe true
        }

        // ========== Character Distribution Tests ==========

        test("generate uses full alphabet range") {
            // Generate many IDs and check that we see most characters
            val allChars =
                (1..1000)
                    .map { NanoId.generate() }
                    .flatMap { it.toList() }
                    .toSet()

            // With 1000 IDs of 21 chars each (21000 chars total), we should see
            // most of the 64 character alphabet. Allow for some statistical variance.
            withClue("Should use most of the 64-char alphabet") { (allChars.size >= 50) shouldBe true }
        }

        // ========== Uniqueness Tests ==========

        test("generate produces unique IDs in batch") {
            val ids = (1..1000).map { NanoId.generate() }
            val uniqueIds = ids.toSet()
            withClue("All generated IDs should be unique") { uniqueIds.size shouldBe ids.size }
        }

        test("prefixed generate produces unique IDs") {
            val ids = (1..100).map { NanoId.generate(prefix = "test") }
            val uniqueIds = ids.toSet()
            withClue("All prefixed IDs should be unique") { uniqueIds.size shouldBe ids.size }
        }
    })
