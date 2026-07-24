package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.UnmappedStringSummary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Round-trips [UnmappedStringSummary] through [contractJson]. Catches field-name
 * drift on the curator-view aggregate returned by
 * [com.calypsan.listenup.api.GenreService.listUnmappedStrings].
 */
class UnmappedStringSummaryContractTest :
    FunSpec({

        test("should round-trip UnmappedStringSummary with populated fields") {
            val original =
                UnmappedStringSummary(
                    rawString = "Mystery & Thriller",
                    bookCount = 17,
                    firstSeenAt = 1_700_000_000L,
                )
            roundTrip<UnmappedStringSummary>(original) shouldBe original
        }

        test("should round-trip UnmappedStringSummary with bookCount = 1") {
            val original =
                UnmappedStringSummary(
                    rawString = "LitRPG",
                    bookCount = 1,
                    firstSeenAt = 1_700_000_500L,
                )
            val decoded = roundTrip<UnmappedStringSummary>(original)
            decoded shouldBe original
            decoded.rawString shouldBe "LitRPG"
            decoded.bookCount shouldBe 1
            decoded.firstSeenAt shouldBe 1_700_000_500L
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))
