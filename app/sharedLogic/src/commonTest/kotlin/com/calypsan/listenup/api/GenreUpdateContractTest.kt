package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.GenreUpdate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Round-trips [GenreUpdate] through [contractJson] and pins its `init { require }`
 * validation. Catches field-name drift, default-null preservation, and the four
 * field-bound invariants — blank name, oversize description, malformed hex color,
 * and sortOrder outside `MIN_SORT..MAX_SORT`.
 */
class GenreUpdateContractTest :
    FunSpec({

        // ── Round-trip ────────────────────────────────────────────────────────

        test("should round-trip GenreUpdate with all fields populated") {
            val original =
                GenreUpdate(
                    name = "Epic Fantasy",
                    description = "Long-form fantasy with grand stakes.",
                    color = "#abcdef",
                    sortOrder = 42,
                )
            roundTrip<GenreUpdate>(original) shouldBe original
        }

        test("should preserve null when GenreUpdate has no fields populated") {
            val original = GenreUpdate()
            val decoded = roundTrip<GenreUpdate>(original)
            decoded shouldBe original
            decoded.name shouldBe null
            decoded.description shouldBe null
            decoded.color shouldBe null
            decoded.sortOrder shouldBe null
        }

        // ── init { require } validation ───────────────────────────────────────

        test("should throw when GenreUpdate name is blank") {
            shouldThrow<IllegalArgumentException> {
                GenreUpdate(name = "")
            }
        }

        test("should throw when GenreUpdate name is whitespace only") {
            shouldThrow<IllegalArgumentException> {
                GenreUpdate(name = "   ")
            }
        }

        test("should throw when GenreUpdate name exceeds MAX_NAME") {
            shouldThrow<IllegalArgumentException> {
                GenreUpdate(name = "a".repeat(GenreUpdate.MAX_NAME + 1))
            }
        }

        test("should throw when GenreUpdate description exceeds MAX_DESCRIPTION") {
            shouldThrow<IllegalArgumentException> {
                GenreUpdate(description = "x".repeat(GenreUpdate.MAX_DESCRIPTION + 1))
            }
        }

        test("should throw when GenreUpdate color is not a valid hex color") {
            shouldThrow<IllegalArgumentException> {
                GenreUpdate(color = "red")
            }
        }

        test("should throw when GenreUpdate color is missing the leading hash") {
            shouldThrow<IllegalArgumentException> {
                GenreUpdate(color = "abcdef")
            }
        }

        test("should throw when GenreUpdate sortOrder is below MIN_SORT") {
            shouldThrow<IllegalArgumentException> {
                GenreUpdate(sortOrder = GenreUpdate.MIN_SORT - 1)
            }
        }

        test("should throw when GenreUpdate sortOrder is above MAX_SORT") {
            shouldThrow<IllegalArgumentException> {
                GenreUpdate(sortOrder = GenreUpdate.MAX_SORT + 1)
            }
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))
