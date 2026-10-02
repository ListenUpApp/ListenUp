package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

/**
 * [CollectionError.ReleaseIncomplete]: the typed answer to a release that freed some books but not
 * all. It names the books that stayed held so a client can write through only the ones that left.
 */
class CollectionErrorReleaseIncompleteTest :
    FunSpec({
        test("ReleaseIncomplete round-trips through the contract JSON with the books that stayed held") {
            val original: AppError =
                CollectionError.ReleaseIncomplete(
                    failedBookIds = listOf("b2", "b5"),
                    correlationId = "corr-1",
                    debugInfo = "membership write failed",
                )

            val decoded = contractJson.decodeFromString<AppError>(contractJson.encodeToString(original))

            decoded.shouldBeInstanceOf<CollectionError.ReleaseIncomplete>()
            decoded shouldBe original
            decoded.failedBookIds shouldBe listOf("b2", "b5")
        }

        test("ReleaseIncomplete survives inside an AppResult failure, the shape releaseBooks returns") {
            val original: AppResult<Unit> = AppResult.Failure(CollectionError.ReleaseIncomplete(listOf("b1")))

            val decoded = contractJson.decodeFromString<AppResult<Unit>>(contractJson.encodeToString(original))

            decoded shouldBe original
        }

        test("ReleaseIncomplete is retryable, with a constant user-facing message and a stable code") {
            val error = CollectionError.ReleaseIncomplete(failedBookIds = listOf("b1"))

            error.isRetryable shouldBe true
            error.code shouldBe "COLLECTION_RELEASE_INCOMPLETE"
            error.message shouldBe "Some books couldn't be released. They're still in the inbox."
            error.message shouldEndWith "."
        }

        test("a correlation id stamps onto ReleaseIncomplete without losing the failed books") {
            val stamped = (CollectionError.ReleaseIncomplete(listOf("b1")) as AppError).withCorrelationId("req-1")

            stamped.shouldBeInstanceOf<CollectionError.ReleaseIncomplete>()
            stamped.correlationId shouldBe "req-1"
            stamped.failedBookIds shouldBe listOf("b1")
        }

        // Wire compatibility: a client built before ReleaseIncomplete existed meets it inside the
        // AppResult that releaseBooks returns. That shape — not a bare AppError — is the one that must
        // not throw, so this decodes a hand-written Failure carrying a CollectionError subtype this build
        // has never heard of, through the AppResult serializer itself.
        test("a CollectionError subtype this build does not know decodes inside an AppResult as UnknownError") {
            val wire =
                """
                {"type":"Failure","error":{"type":"CollectionError.SomethingNewer","failedBookIds":["b1"],
                 "correlationId":"corr-9","code":"COLLECTION_SOMETHING_NEWER","isRetryable":true}}
                """.trimIndent()

            val decoded = contractJson.decodeFromString(AppResult.serializer(Unit.serializer()), wire)

            val error = decoded.shouldBeInstanceOf<AppResult.Failure>().error
            error.shouldBeInstanceOf<UnknownError>()
            error.correlationId shouldBe "corr-9"
            error.code shouldBe "COLLECTION_SOMETHING_NEWER"
            error.debugInfo shouldContain "CollectionError.SomethingNewer"
        }
    })
