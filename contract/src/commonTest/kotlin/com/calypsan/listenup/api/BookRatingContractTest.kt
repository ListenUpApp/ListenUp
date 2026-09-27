package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.BookRatingMutation
import com.calypsan.listenup.api.dto.RateBookRequest
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.domain.ListenerRatingLimits
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

class BookRatingContractTest :
    FunSpec({
        test("BookRatingSyncPayload round-trips through JSON") {
            val payload =
                BookRatingSyncPayload(
                    id = "r1",
                    bookId = "b1",
                    userId = "u1",
                    halfStars = 7,
                    note = "Loved the ending.",
                    ratedAt = 10L,
                    updatedAt = 20L,
                    revision = 3L,
                    deletedAt = null,
                )
            contractJson.decodeFromString<BookRatingSyncPayload>(contractJson.encodeToString(payload)) shouldBe payload
        }

        test("a note is stored trimmed, and a blank note means no note") {
            ListenerRatingLimits.normalizeNote("  Gripping.  ") shouldBe "Gripping."
            ListenerRatingLimits.normalizeNote("   ") shouldBe null
            ListenerRatingLimits.normalizeNote(null) shouldBe null
        }

        test("a 280-character note padded with spaces is still accepted") {
            RateBookRequest(candidateId = "c", halfStars = 6, note = "  " + "x".repeat(280) + "  ").halfStars shouldBe 6
        }

        test("RateBookRequest rejects a rating outside one to five stars") {
            shouldThrow<IllegalArgumentException> { RateBookRequest(candidateId = "c", halfStars = 1, note = null) }
            shouldThrow<IllegalArgumentException> { RateBookRequest(candidateId = "c", halfStars = 11, note = null) }
        }

        test("RateBookRequest rejects a blank candidateId") {
            shouldThrow<IllegalArgumentException> { RateBookRequest(candidateId = "", halfStars = 6, note = null) }
            shouldThrow<IllegalArgumentException> { RateBookRequest(candidateId = "   ", halfStars = 6, note = null) }
        }

        test("RateBookRequest rejects a note longer than the cap") {
            shouldThrow<IllegalArgumentException> {
                RateBookRequest(candidateId = "c", halfStars = 6, note = "x".repeat(281))
            }
        }

        test("both mutations round-trip as the sealed type") {
            val set: BookRatingMutation = BookRatingMutation.Set(bookId = "b1", candidateId = "c", halfStars = 8, note = null)
            val clear: BookRatingMutation = BookRatingMutation.Clear(bookId = "b1")
            contractJson.decodeFromString<BookRatingMutation>(contractJson.encodeToString(set)) shouldBe set
            contractJson.decodeFromString<BookRatingMutation>(contractJson.encodeToString(clear)) shouldBe clear
        }

        test("stars are said the same way everywhere") {
            ListenerRatingLimits.starsLabel(8.0) shouldBe "4"
            ListenerRatingLimits.starsLabel(7.0) shouldBe "3.5"
            ListenerRatingLimits.starsLabel(7.4) shouldBe "3.5"
            ListenerRatingLimits.starsLabel(7.6) shouldBe "4"
        }

        test("the book_ratings domain is in the catalog") {
            SyncDomains.all shouldContain SyncDomains.BOOK_RATINGS
            SyncDomains.BOOK_RATINGS.name shouldBe "book_ratings"
        }
    })
