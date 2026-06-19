package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.error.TagError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [TagSlug.normalize].
 *
 * Verifies the slug normalization pipeline: NFKD diacritic stripping, lowercase,
 * ampersand-to-and substitution, non-alphanum collapse, trim, and validation gates.
 */
class TagSlugTest :
    FunSpec({

        test("normalizes genre with hyphen and ampersand") {
            val result = TagSlug.normalize("Sci-Fi & Fantasy")
            result shouldBe AppResult.Success("sci-fi-and-fantasy")
        }

        test("trims surrounding whitespace") {
            val result = TagSlug.normalize("  Mystery  ")
            result shouldBe AppResult.Success("mystery")
        }

        test("strips diacritics via NFKD") {
            val result = TagSlug.normalize("naïve")
            result shouldBe AppResult.Success("naive")
        }

        test("collapses non-alphanum separator runs including slash") {
            val result = TagSlug.normalize("hello / world")
            result shouldBe AppResult.Success("hello-world")
        }

        test("empty string returns InvalidName") {
            val result = TagSlug.normalize("")
            require(result is AppResult.Failure)
            result.error.shouldBeInstanceOf<TagError.InvalidName>()
        }

        test("blank whitespace-only string returns InvalidName") {
            val result = TagSlug.normalize("   ")
            require(result is AppResult.Failure)
            result.error.shouldBeInstanceOf<TagError.InvalidName>()
        }

        test("string longer than 64 chars returns NameTooLong") {
            // 65 'a' chars → slug = "a".repeat(65) → length 65 > 64
            val result = TagSlug.normalize("a".repeat(65))
            require(result is AppResult.Failure)
            result.error.shouldBeInstanceOf<TagError.NameTooLong>()
        }

        test("string exactly 64 chars is accepted") {
            val result = TagSlug.normalize("a".repeat(64))
            result shouldBe AppResult.Success("a".repeat(64))
        }

        test("unicode non-Latin collapses to dash and trims leaving InvalidName") {
            // 日本語 → NFKD → no diacritics to strip → all non-ASCII → replaces with "-" → trims → empty
            val result = TagSlug.normalize("日本語")
            require(result is AppResult.Failure)
            result.error.shouldBeInstanceOf<TagError.InvalidName>()
        }

        test("mixed-case input is lowercased") {
            val result = TagSlug.normalize("MIXED-Case-AlReady")
            result shouldBe AppResult.Success("mixed-case-already")
        }

        test("normalize is idempotent") {
            val first = TagSlug.normalize("Sci-Fi & Fantasy")
            check(first is AppResult.Success)
            val second = TagSlug.normalize(first.data)
            second shouldBe first
        }

        test("leading and trailing dashes are trimmed") {
            // e.g. "  - topic -  " → dashes trimmed
            val result = TagSlug.normalize("  - topic -  ")
            result shouldBe AppResult.Success("topic")
        }
    })
