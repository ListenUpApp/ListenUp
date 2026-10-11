package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.BookRatingMutation
import com.calypsan.listenup.api.dto.RateBookRequest
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.api.sync.ListenerRatingSource
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.domain.ListenerRatingLimits
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

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

        test("a ListenUp rating still says its source on the wire") {
            val payload =
                BookRatingSyncPayload(
                    id = "r1",
                    bookId = "b1",
                    userId = "u1",
                    halfStars = 7,
                    note = null,
                    ratedAt = 10L,
                    updatedAt = 20L,
                    revision = 3L,
                )
            contractJson.encodeToString(payload) shouldContain """"source":"LISTENUP""""
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

        test("a rating draft is a change only when it differs from the rating the sheet opened on") {
            // Unrated book: the sheet opens on no stars and no note.
            ListenerRatingLimits.isDraftChanged(null, null, halfStars = 0, note = "") shouldBe false
            ListenerRatingLimits.isDraftChanged(null, null, halfStars = 6, note = "") shouldBe true
            ListenerRatingLimits.isDraftChanged(null, null, halfStars = 0, note = "Hm") shouldBe true
            // Rated book: putting the same values back is no change.
            ListenerRatingLimits.isDraftChanged(8, "Great", halfStars = 8, note = "Great") shouldBe false
            ListenerRatingLimits.isDraftChanged(8, null, halfStars = 8, note = "") shouldBe false
            ListenerRatingLimits.isDraftChanged(8, "Great", halfStars = 7, note = "Great") shouldBe true
            ListenerRatingLimits.isDraftChanged(8, "Great", halfStars = 8, note = "Great!") shouldBe true
        }

        test("the book_ratings domain is in the catalog") {
            SyncDomains.all shouldContain SyncDomains.BOOK_RATINGS
            SyncDomains.BOOK_RATINGS.name shouldBe "book_ratings"
        }

        test("a rating's source round-trips, and a frame without one is a ListenUp rating") {
            val imported =
                BookRatingSyncPayload(
                    id = "r1",
                    bookId = "b1",
                    userId = "u1",
                    halfStars = 9,
                    note = null,
                    ratedAt = 10L,
                    updatedAt = 20L,
                    revision = 3L,
                    source = ListenerRatingSource.HARDCOVER,
                )
            contractJson.decodeFromString<BookRatingSyncPayload>(contractJson.encodeToString(imported)) shouldBe imported

            val oldFrame =
                """{"id":"r1","bookId":"b1","userId":"u1","halfStars":9,"note":null,"ratedAt":10,"updatedAt":20,"revision":3}"""
            contractJson.decodeFromString<BookRatingSyncPayload>(oldFrame).source shouldBe ListenerRatingSource.LISTENUP
        }

        test("a source this build has never heard of reads as a ListenUp rating") {
            val future =
                """{"id":"r1","bookId":"b1","userId":"u1","halfStars":9,"note":null,"ratedAt":10,"updatedAt":20,"revision":3,"source":"GOODREADS"}"""
            contractJson.decodeFromString<BookRatingSyncPayload>(future).source shouldBe ListenerRatingSource.LISTENUP
        }
    })
