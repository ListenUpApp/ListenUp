package com.calypsan.listenup.client.handoff

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ViewedBookTrackerTest :
    FunSpec({

        val first = BookId("book-1")
        val second = BookId("book-2")

        test("a shown book is what the tracker reports") {
            val tracker = ViewedBookTracker()

            tracker.onBookShown(first)

            tracker.viewedBookId.value shouldBe first
        }

        test("hiding the book that is showing clears it") {
            val tracker = ViewedBookTracker()
            tracker.onBookShown(first)

            tracker.onBookHidden(first)

            tracker.viewedBookId.value shouldBe null
        }

        test("a late departure cannot clear the screen that replaced it") {
            // ⛔ The race this exists to lose safely. Compose disposes the outgoing screen AFTER the
            // incoming one appears, so an id-less clear would wipe the newer claim and the handoff
            // would offer nothing while a book was plainly on screen.
            val tracker = ViewedBookTracker()
            tracker.onBookShown(first)
            tracker.onBookShown(second)

            tracker.onBookHidden(first)

            tracker.viewedBookId.value shouldBe second
        }
    })
