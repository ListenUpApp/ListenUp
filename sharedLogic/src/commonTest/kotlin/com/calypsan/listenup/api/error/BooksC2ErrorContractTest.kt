package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the round-trip behaviour, stable `@SerialName` discriminators, and body-level
 * message constants for the three Books-C2 error subtypes: [ContributorError.MergeSelfTarget],
 * [ContributorError.AliasNotFound], and [SeriesError.MergeSelfTarget]. Encoding through
 * [AppError.serializer] exercises the polymorphic discriminator path.
 */
class BooksC2ErrorContractTest :
    FunSpec({

        // ── ContributorError.MergeSelfTarget ──────────────────────────────────

        test("should round-trip ContributorError.MergeSelfTarget through AppError serializer") {
            val original: AppError =
                ContributorError.MergeSelfTarget(correlationId = "req-42", debugInfo = "source=c1 target=c1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for ContributorError.MergeSelfTarget") {
            val json = contractJson.encodeToString(AppError.serializer(), ContributorError.MergeSelfTarget())
            json.contains("\"ContributorError.MergeSelfTarget\"") shouldBe true
        }

        test("should have constant body-level message for ContributorError.MergeSelfTarget") {
            ContributorError.MergeSelfTarget().message shouldBe
                ContributorError.MergeSelfTarget(debugInfo = "x").message
        }

        test("should mark ContributorError.MergeSelfTarget as not retryable") {
            ContributorError.MergeSelfTarget().isRetryable shouldBe false
        }

        // ── ContributorError.AliasNotFound ────────────────────────────────────

        test("should round-trip ContributorError.AliasNotFound through AppError serializer") {
            val original: AppError =
                ContributorError.AliasNotFound(debugInfo = "alias=Bachman target=c1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for ContributorError.AliasNotFound") {
            val json = contractJson.encodeToString(AppError.serializer(), ContributorError.AliasNotFound())
            json.contains("\"ContributorError.AliasNotFound\"") shouldBe true
        }

        test("should have constant body-level message for ContributorError.AliasNotFound") {
            ContributorError.AliasNotFound().message shouldBe
                ContributorError.AliasNotFound(debugInfo = "y").message
        }

        test("should mark ContributorError.AliasNotFound as not retryable") {
            ContributorError.AliasNotFound().isRetryable shouldBe false
        }

        // ── SeriesError.MergeSelfTarget ───────────────────────────────────────

        test("should round-trip SeriesError.MergeSelfTarget through AppError serializer") {
            val original: AppError =
                SeriesError.MergeSelfTarget(debugInfo = "source=s1 target=s1")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            contractJson.decodeFromString(AppError.serializer(), json) shouldBe original
        }

        test("should embed stable discriminator for SeriesError.MergeSelfTarget") {
            val json = contractJson.encodeToString(AppError.serializer(), SeriesError.MergeSelfTarget())
            json.contains("\"SeriesError.MergeSelfTarget\"") shouldBe true
        }

        test("should have constant body-level message for SeriesError.MergeSelfTarget") {
            SeriesError.MergeSelfTarget().message shouldBe
                SeriesError.MergeSelfTarget(debugInfo = "z").message
        }

        test("should mark SeriesError.MergeSelfTarget as not retryable") {
            SeriesError.MergeSelfTarget().isRetryable shouldBe false
        }
    })
