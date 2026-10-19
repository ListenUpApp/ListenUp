package com.calypsan.listenup.client.features.discover.components

import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * One model decides an activity row's actions (plan C7), so the long-press menu, TalkBack's custom
 * actions and the Menu key can never offer different things.
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
            activityRowActions(row(bookId = "b1")) shouldBe
                listOf(
                    ActivityRowAction.OpenBook("b1"),
                    ActivityRowAction.OpenProfile("maya"),
                    ActivityRowAction.AddToShelf("b1"),
                )
        }

        test("a shelf row offers the shelf and the person") {
            activityRowActions(row(bookId = null, shelfId = "sh1")) shouldBe
                listOf(ActivityRowAction.OpenShelf("sh1"), ActivityRowAction.OpenProfile("maya"))
        }

        test("a row about neither book nor shelf has no menu, since its tap already opens the person") {
            activityRowActions(row(bookId = null, shelfId = null)).shouldBeEmpty()
        }
    })
