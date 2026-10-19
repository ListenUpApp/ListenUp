package com.calypsan.listenup.client.features.library.components

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a bulk add says it did, in the `selection.*` words every platform shares.
 *
 * The number in the sentence is what the collection or shelf actually gained, not how many books
 * were selected — the two differ whenever a selected book was already a member, and the earlier
 * wording reported the selection, so adding two books that were both already in a collection
 * claimed "2 books added to collection" while nothing changed at all.
 *
 * The strings resolve through compose-resources `getString`, which needs a real Android resource
 * environment — hence JUnit4 + Robolectric, as in `BulkEditAppliedMessageTest`.
 */
@RunWith(RobolectricTestRunner::class)
class BookSelectionMessagesTest {
    @Test
    fun `nothing gained is said plainly, not counted as an add`() =
        runTest {
            booksAddedToCollectionMessage(0) shouldBe "Already in that collection"
            booksAddedToShelfMessage(0) shouldBe "Already on that shelf"
        }

    @Test
    fun `one book is announced in the singular`() =
        runTest {
            booksAddedToCollectionMessage(1) shouldBe "1 book added to collection"
            booksAddedToShelfMessage(1) shouldBe "1 book added to shelf"
        }

    @Test
    fun `several books are counted`() =
        runTest {
            booksAddedToCollectionMessage(4) shouldBe "4 books added to collection"
            booksAddedToShelfMessage(4) shouldBe "4 books added to shelf"
        }

    @Test
    fun `a new shelf or collection is named with what it holds`() =
        runTest {
            createdWithBooksMessage("Sci-fi", 1) shouldBe "Created \"Sci-fi\" with 1 book"
            createdWithBooksMessage("Sci-fi", 3) shouldBe "Created \"Sci-fi\" with 3 books"
        }
}
