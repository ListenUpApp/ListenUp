package com.calypsan.listenup.client.presentation.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The Activity feed's live row — "Kyle Harnett is listening to Red Rising" — reads and acts like every other book
 * row, so its sentence and its actions come from the same shared rules every client renders.
 */
class ListeningNowRowTest :
    FunSpec({
        fun listener(authorName: String? = "Pierce Brown") =
            ListeningNowUiModel(
                userId = "kyle",
                userDisplayName = "Kyle Harnett",
                bookId = "red-rising",
                bookTitle = "Red Rising",
                bookAuthorName = authorName,
                bookCoverPath = null,
                bookCoverHash = null,
                startedAt = 1_000L,
            )

        test("the sentence says they are listening to the book, by its author") {
            val parts = listeningNowParts(listener())

            parts.predicate shouldBe "is listening to"
            parts.highlight shouldBe "Red Rising"
            parts.suffix shouldBe " by Pierce Brown"
            "Kyle Harnett ${parts.predicate} ${parts.highlight.orEmpty()}${parts.suffix}" shouldBe
                "Kyle Harnett is listening to Red Rising by Pierce Brown"
        }

        test("a book with several authors names the first, as every other row does") {
            listeningNowParts(listener(authorName = "Pierce Brown, Tim Gerard Reynolds")).suffix shouldBe
                " by Pierce Brown et al."
            listeningNowParts(listener(authorName = null)).suffix shouldBe ""
        }

        test("a live row offers the book, the person and add to a shelf, like a book row") {
            listeningNowRowActions(listener(), canShelve = true) shouldBe
                listOf(
                    ActivityRowAction.OpenBook("red-rising"),
                    ActivityRowAction.OpenProfile("kyle"),
                    ActivityRowAction.AddToShelf("red-rising"),
                )
            listeningNowRowActions(listener(), canShelve = false) shouldBe
                listOf(ActivityRowAction.OpenBook("red-rising"), ActivityRowAction.OpenProfile("kyle"))
        }

        test("one live row per person: its identity is the person") {
            listener().id shouldBe "listening-now:kyle"
        }
    })
