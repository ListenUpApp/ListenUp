package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString

class MetadataErrorSerializationTest :
    FunSpec({
        test("ChapterCountMismatch round-trips through the contract JSON as a typed AppError") {
            val original: AppError =
                MetadataError.ChapterCountMismatch(
                    correlationId = "corr-1",
                    debugInfo = "Local 12 vs Audible 38 for ASIN B001.",
                )

            val json = contractJson.encodeToString(original)
            val decoded = contractJson.decodeFromString<AppError>(json)

            decoded.shouldBeInstanceOf<MetadataError.ChapterCountMismatch>()
            decoded.code shouldBe "METADATA_CHAPTER_COUNT_MISMATCH"
            decoded.isRetryable shouldBe false
            decoded.debugInfo shouldBe "Local 12 vs Audible 38 for ASIN B001."
            decoded.message shouldBe
                "This edition's chapter count doesn't match your audiobook, so chapter names can't be applied."
        }

        test("UnsafeUrl round-trips through the contract JSON as a typed AppError") {
            val original: AppError =
                MetadataError.UnsafeUrl(
                    correlationId = "corr-2",
                    debugInfo = "cover URL rejected: must be a public HTTPS destination",
                )

            val json = contractJson.encodeToString(original)
            val decoded = contractJson.decodeFromString<AppError>(json)

            decoded.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
            decoded.code shouldBe "METADATA_UNSAFE_URL"
            decoded.isRetryable shouldBe false
            decoded.debugInfo shouldBe "cover URL rejected: must be a public HTTPS destination"
            decoded.message shouldBe "That image URL can't be used."
        }
    })
