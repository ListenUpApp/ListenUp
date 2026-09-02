package com.calypsan.listenup.client.features.library.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * What a bulk add says it did.
 *
 * The number in the sentence is what the collection or shelf actually gained, not how many books
 * were selected — the two differ whenever a selected book was already a member, and the earlier
 * wording reported the selection, so adding two books that were both already in a collection
 * claimed "2 books added to collection" while nothing changed at all.
 */
class BookSelectionMessagesTest :
    FunSpec({
        test("nothing gained is said plainly, not counted as an add") {
            booksAddedToCollectionMessage(0) shouldBe "Already in that collection"
            booksAddedToShelfMessage(0) shouldBe "Already on that shelf"
        }

        test("one book is announced in the singular") {
            booksAddedToCollectionMessage(1) shouldBe "1 book added to collection"
            booksAddedToShelfMessage(1) shouldBe "1 book added to shelf"
        }

        test("several books are counted") {
            booksAddedToCollectionMessage(4) shouldBe "4 books added to collection"
            booksAddedToShelfMessage(4) shouldBe "4 books added to shelf"
        }
    })
