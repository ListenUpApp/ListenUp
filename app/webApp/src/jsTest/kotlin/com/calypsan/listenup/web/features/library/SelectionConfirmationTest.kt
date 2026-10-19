package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.client.presentation.books.BookMultiSelectEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** A bulk add's confirmation is en.json's `selection.*` sentence word for word, as on Android and iOS. */
class SelectionConfirmationTest :
    FunSpec({
        test("adds to a shelf or collection say what was gained") {
            confirmationFor(BookMultiSelectEvent.BooksAddedToShelf(0)) shouldBe "Already on that shelf"
            confirmationFor(BookMultiSelectEvent.BooksAddedToShelf(1)) shouldBe "1 book added to shelf"
            confirmationFor(BookMultiSelectEvent.BooksAddedToShelf(3)) shouldBe "3 books added to shelf"
            confirmationFor(BookMultiSelectEvent.BooksAddedToCollection(0)) shouldBe "Already in that collection"
            confirmationFor(BookMultiSelectEvent.BooksAddedToCollection(2)) shouldBe "2 books added to collection"
        }

        test("a new shelf is named with what it holds") {
            confirmationFor(BookMultiSelectEvent.ShelfCreatedAndBooksAdded("Sci-fi", 1)) shouldBe
                "Created \"Sci-fi\" with 1 book"
            confirmationFor(BookMultiSelectEvent.CollectionCreatedAndBooksAdded("Classics", 4)) shouldBe
                "Created \"Classics\" with 4 books"
        }
    })
