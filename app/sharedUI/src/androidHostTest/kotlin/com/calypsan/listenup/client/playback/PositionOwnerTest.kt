package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.domain.playback.TimelineFileInput
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Pins the service's one rule for filing a transport position: it belongs to the active book only while the
 * transport actually holds that book's media. Between `activateBook(B)` and Media3 applying B's queue, the
 * player still holds A at A's place; a pause, tick or error landing then must not be saved as B's.
 */
class PositionOwnerTest :
    FunSpec({
        val bookA = BookId("book-a")
        val bookB = BookId("book-b")

        fun timelineOf(
            bookId: BookId,
            vararg fileIds: String,
        ) = PlaybackTimeline.build(
            bookId = bookId,
            files = fileIds.map { TimelineFileInput(it, "$it.m4b", "m4b", 3_600_000L, 1L, null, "https://s/$it") },
        )

        test("the active book owns the position while the transport plays one of its files") {
            positionOwner(bookB, timelineOf(bookB, "b-1", "b-2"), transportMediaId = "b-2") shouldBe bookB
        }

        test("the transport still holding A's file is not B's place, though the manager already says B") {
            positionOwner(bookB, timelineOf(bookB, "b-1"), transportMediaId = "a-1").shouldBeNull()
        }

        test("a timeline prepared for another book cannot map the active book's position") {
            positionOwner(bookA, timelineOf(bookB, "b-1"), transportMediaId = "a-1").shouldBeNull()
        }

        test("no book active, no owner") {
            positionOwner(null, timelineOf(bookB, "b-1"), transportMediaId = "b-1").shouldBeNull()
        }

        test("a transport that names no media item gives no evidence against the active book") {
            positionOwner(bookB, timelineOf(bookB, "b-1"), transportMediaId = null) shouldBe bookB
            positionOwner(bookB, null, transportMediaId = "b-1") shouldBe bookB
        }
    })
