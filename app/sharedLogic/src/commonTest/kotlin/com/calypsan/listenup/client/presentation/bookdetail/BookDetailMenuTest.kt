package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.TestData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** Book Detail's More menu (spec §2.7): one rule for Android, iOS and web. */
class BookDetailMenuTest :
    FunSpec({
        fun ready(
            stage: ReadingStage = ReadingStage.NotStarted,
            isAdmin: Boolean = false,
            canEditMetadata: Boolean = false,
            isHeld: Boolean = false,
        ) = BookDetailUiState.Ready(
            book = TestData.bookDetail(id = "b1"),
            readingStage = stage,
            isComplete = stage == ReadingStage.Finished,
            isAdmin = isAdmin,
            canEditMetadata = canEditMetadata,
            isHeld = isHeld,
        )

        fun BookDetailUiState.Ready.menu() = bookDetailMoreMenu(this)

        test("a member who has not started gets Share, then Mark as finished alone") {
            ready().menu() shouldBe
                listOf(
                    BookDetailMenuGroup(heading = null, items = listOf(BookDetailMenuItem.Share)),
                    BookDetailMenuGroup(BookDetailMenuHeading.Progress, listOf(BookDetailMenuItem.MarkFinished)),
                )
        }

        test("in progress, Progress holds all three, in the spec's order") {
            ready(ReadingStage.InProgress).menu()[1].items shouldBe
                listOf(BookDetailMenuItem.MarkFinished, BookDetailMenuItem.Restart, BookDetailMenuItem.MarkNotStarted)
        }

        test("finished, Mark as finished leaves the menu and the two resets stay") {
            ready(ReadingStage.Finished).menu()[1].items shouldBe
                listOf(BookDetailMenuItem.Restart, BookDetailMenuItem.MarkNotStarted)
        }

        test("Edit metadata adds the Edit group; without it there is none") {
            ready(canEditMetadata = true).menu().map { it.heading } shouldBe
                listOf(null, BookDetailMenuHeading.Progress, BookDetailMenuHeading.Edit)
            ready(canEditMetadata = true).menu()[2].items shouldBe
                listOf(BookDetailMenuItem.EditDetails, BookDetailMenuItem.MatchDetails, BookDetailMenuItem.EditChapters)
            ready().menu().none { it.heading == BookDetailMenuHeading.Edit } shouldBe true
        }

        test("an admin gets Admin, then Delete alone and last, the only destructive group") {
            val groups = ready(isAdmin = true, canEditMetadata = true).menu()
            groups.map { it.heading } shouldBe
                listOf(null, BookDetailMenuHeading.Progress, BookDetailMenuHeading.Edit, BookDetailMenuHeading.Admin, null)
            groups[3].items shouldBe listOf(BookDetailMenuItem.AddToCollection)
            groups.last() shouldBe
                BookDetailMenuGroup(heading = null, items = listOf(BookDetailMenuItem.DeleteFromServer), isDestructive = true)
            groups.count { it.isDestructive } shouldBe 1
        }

        test("a member never gets Add to collection or Delete") {
            val items = ready(ReadingStage.InProgress, canEditMetadata = true).menu().flatMap { it.items }
            (BookDetailMenuItem.AddToCollection in items) shouldBe false
            (BookDetailMenuItem.DeleteFromServer in items) shouldBe false
        }

        test("a held book has no menu at all, even for an admin") {
            ready(isAdmin = true, canEditMetadata = true, isHeld = true).menu().shouldBeEmpty()
        }

        test("no group is ever empty") {
            listOf(ReadingStage.NotStarted, ReadingStage.InProgress, ReadingStage.Finished).forEach { stage ->
                listOf(true, false).forEach { admin ->
                    ready(stage, isAdmin = admin).menu().none { it.items.isEmpty() } shouldBe true
                }
            }
        }

        test("isChangingProgress is any of the three progress writes in flight") {
            ready().isChangingProgress shouldBe false
            ready().copy(isMarkingComplete = true).isChangingProgress shouldBe true
            ready().copy(isDiscardingProgress = true).isChangingProgress shouldBe true
            ready().copy(isRestarting = true).isChangingProgress shouldBe true
        }
    })
