package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.PolymorphicSerializer
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

        // Wire compatibility: a client built before this subtype existed knows no
        // "CollectionError.ReleaseIncomplete". Decoding through the open polymorphic serializer — which
        // knows no AppError subtype at all — exercises exactly the fallback such a client takes: the
        // polymorphic default contractJson registers. It must decode, never throw, and say what arrived.
        test("a build that does not know ReleaseIncomplete decodes it as UnknownError instead of throwing") {
            val wire = contractJson.encodeToString<AppError>(CollectionError.ReleaseIncomplete(listOf("b1"), "corr-9"))

            val decoded = contractJson.decodeFromString(PolymorphicSerializer(AppError::class), wire)

            decoded.shouldBeInstanceOf<UnknownError>()
            decoded.correlationId shouldBe "corr-9"
            decoded.debugInfo shouldContain "CollectionError.ReleaseIncomplete"
        }
    })
