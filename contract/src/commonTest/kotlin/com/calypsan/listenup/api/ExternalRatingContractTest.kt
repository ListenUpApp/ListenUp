package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.error.RatingError
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.api.sync.ExternalRatingSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.domain.averageLabel
import com.calypsan.listenup.domain.compactCount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

class ExternalRatingContractTest :
    FunSpec({
        test("ExternalRatingSyncPayload round-trips through JSON") {
            val payload =
                ExternalRatingSyncPayload(
                    id = "x1",
                    bookId = "b1",
                    source = ExternalRatingSource.AUDIBLE,
                    average = 4.6,
                    count = 12_345,
                    enabled = true,
                    revision = 3L,
                    deletedAt = null,
                )
            contractJson.decodeFromString<ExternalRatingSyncPayload>(contractJson.encodeToString(payload)) shouldBe payload
        }

        test("an unknown source from a newer server decodes rather than failing the whole page") {
            // Follow the repo's existing unknown-enum fallback convention (see UnknownEnumValueFallbackTest).
            contractJson.decodeFromString<ExternalRatingSource>("\"STORYGRAPH\"") shouldBe ExternalRatingSource.UNKNOWN
        }

        test("RatingSourceStatus round-trips") {
            val status =
                RatingSourceStatus(
                    source = ExternalRatingSource.AUDIBLE,
                    enabled = true,
                    lastFetchedAt = 10L,
                    lastError = null,
                )
            contractJson.decodeFromString<RatingSourceStatus>(contractJson.encodeToString(status)) shouldBe status
        }

        test("RatingError.SourceUnavailable is retryable") {
            RatingError.SourceUnavailable().isRetryable shouldBe true
        }

        test("counts are said the same way everywhere") {
            compactCount(812) shouldBe "812"
            compactCount(1_234) shouldBe "1.2k"
            compactCount(12_345) shouldBe "12k"
            compactCount(999_999) shouldBe "1M"
            compactCount(1_250_000) shouldBe "1.3M"
        }

        test("an average is said with one decimal everywhere, even a whole one") {
            averageLabel(4.4) shouldBe "4.4"
            averageLabel(4.0) shouldBe "4.0"
            averageLabel(4.45) shouldBe "4.5"
            averageLabel(4.449) shouldBe "4.4"
            averageLabel(5.0) shouldBe "5.0"
            averageLabel(0.0) shouldBe "0.0"
        }

        test("the domain is in the catalog") {
            SyncDomains.all shouldContain SyncDomains.BOOK_EXTERNAL_RATINGS
            SyncDomains.BOOK_EXTERNAL_RATINGS.name shouldBe "book_external_ratings"
        }
    })
