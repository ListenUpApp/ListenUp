package com.calypsan.listenup.client.presentation.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * One rule decides an activity row's actions (plan C7), so the long-press menu, TalkBack's and VoiceOver's custom
 * actions, the Menu key and the web's ⋯ can never offer different things.
 */
class ActivityRowActionsTest :
    FunSpec({
        fun row(
            bookId: String? = "b1",
            shelfId: String? = null,
            userId: String = "maya",
        ) = ActivityUiModel(
            id = "a1",
            userId = userId,
            type = if (shelfId != null) "shelf_created" else "finished_book",
            occurredAt = 0L,
            userDisplayName = "Maya Chen",
            bookId = bookId,
            bookTitle = bookId?.let { "Piranesi" },
            bookAuthorName = null,
            bookCoverPath = null,
            bookCoverHash = null,
            isReread = false,
            durationMs = 0L,
            milestoneValue = 0,
            milestoneUnit = null,
            shelfId = shelfId,
            shelfName = shelfId?.let { "Sci-fi" },
            isYou = false,
        )

        test("a book row offers open, the person and add to a shelf, in that order") {
            activityRowActions(row(bookId = "b1"), canShelve = true) shouldBe
                listOf(
                    ActivityRowAction.OpenBook("b1"),
                    ActivityRowAction.OpenProfile("maya"),
                    ActivityRowAction.AddToShelf("b1"),
                )
        }

        test("a book row leaves add to a shelf out where the screen cannot shelve one book") {
            activityRowActions(row(bookId = "b1"), canShelve = false) shouldBe
                listOf(ActivityRowAction.OpenBook("b1"), ActivityRowAction.OpenProfile("maya"))
        }

        test("a shelf row offers the shelf and the person, whether or not shelving is on offer") {
            val expected = listOf(ActivityRowAction.OpenShelf("sh1"), ActivityRowAction.OpenProfile("maya"))
            activityRowActions(row(bookId = null, shelfId = "sh1"), canShelve = true) shouldBe expected
            activityRowActions(row(bookId = null, shelfId = "sh1"), canShelve = false) shouldBe expected
        }

        test("a row about neither book nor shelf has no menu, since its tap already opens the person") {
            activityRowActions(row(bookId = null, shelfId = null), canShelve = true).shouldBeEmpty()
        }
    })
