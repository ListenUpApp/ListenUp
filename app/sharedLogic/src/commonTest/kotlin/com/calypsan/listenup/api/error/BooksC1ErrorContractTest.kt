package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the round-trip behaviour, stable `@SerialName` discriminators, and body-level
 * message constants for all Books-C1 error subtypes: [BookError], [ContributorError],
 * [SeriesError], and [CoverError]. Encoding through [AppError.serializer] exercises
 * the polymorphic discriminator path.
 */
class BooksC1ErrorContractTest :
    FunSpec({

        // ── BookError ─────────────────────────────────────────────────────────

        test("should round-trip BookError.NotFound through AppError serializer") {
            val original: AppError = BookError.NotFound(correlationId = "abc", debugInfo = "bookId=book1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            val decoded = contractJson.decodeFromString(AppError.serializer(), json)
            decoded shouldBe original
        }

        test("should embed stable discriminator for BookError.NotFound") {
            val json = contractJson.encodeToString(AppError.serializer(), BookError.NotFound())
            json.contains("\"BookError.NotFound\"") shouldBe true
        }

        test("should round-trip BookError.InvalidInput through AppError serializer") {
            val original: AppError = BookError.InvalidInput(debugInfo = "title: must be 1..500 chars")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should have constant body-level message for BookError subtypes") {
            BookError.NotFound().message shouldBe BookError.NotFound(debugInfo = "x").message
            BookError.InvalidInput().message shouldBe BookError.InvalidInput(debugInfo = "x").message
        }

        test("should mark BookError subtypes as not retryable") {
            BookError.NotFound().isRetryable shouldBe false
            BookError.InvalidInput().isRetryable shouldBe false
        }

        // ── ContributorError ──────────────────────────────────────────────────

        test("should round-trip ContributorError.NotFound through AppError serializer") {
            val original: AppError = ContributorError.NotFound(debugInfo = "contributorId=c1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for ContributorError.NotFound") {
            val json = contractJson.encodeToString(AppError.serializer(), ContributorError.NotFound())
            json.contains("\"ContributorError.NotFound\"") shouldBe true
        }

        test("should round-trip ContributorError.InvalidInput through AppError serializer") {
            val original: AppError = ContributorError.InvalidInput(correlationId = "req-1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should have constant body-level message for ContributorError subtypes") {
            ContributorError.NotFound().message shouldBe ContributorError.NotFound(debugInfo = "y").message
            ContributorError.InvalidInput().message shouldBe ContributorError.InvalidInput(debugInfo = "y").message
        }

        // ── SeriesError ───────────────────────────────────────────────────────

        test("should round-trip SeriesError.NotFound through AppError serializer") {
            val original: AppError = SeriesError.NotFound(debugInfo = "seriesId=s1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for SeriesError.NotFound") {
            val json = contractJson.encodeToString(AppError.serializer(), SeriesError.NotFound())
            json.contains("\"SeriesError.NotFound\"") shouldBe true
        }

        test("should round-trip SeriesError.InvalidInput through AppError serializer") {
            val original: AppError = SeriesError.InvalidInput(correlationId = "req-2")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should have constant body-level message for SeriesError subtypes") {
            SeriesError.NotFound().message shouldBe SeriesError.NotFound(debugInfo = "z").message
            SeriesError.InvalidInput().message shouldBe SeriesError.InvalidInput(debugInfo = "z").message
        }

        // ── CoverError ────────────────────────────────────────────────────────

        test("should round-trip CoverError.NotPresent through AppError serializer") {
            val original: AppError = CoverError.NotPresent(debugInfo = "bookId=book1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for CoverError.NotPresent") {
            val json = contractJson.encodeToString(AppError.serializer(), CoverError.NotPresent())
            json.contains("\"CoverError.NotPresent\"") shouldBe true
        }

        test("should have constant body-level message for CoverError.NotPresent") {
            CoverError.NotPresent().message shouldBe CoverError.NotPresent(debugInfo = "w").message
        }
    })
