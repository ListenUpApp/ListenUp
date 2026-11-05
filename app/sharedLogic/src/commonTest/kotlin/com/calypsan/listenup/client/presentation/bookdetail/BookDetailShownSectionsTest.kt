package com.calypsan.listenup.client.presentation.bookdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookDetailShownSectionsTest :
    FunSpec({
        val stageOrder =
            listOf(
                BookDetailSection.Chapters,
                BookDetailSection.Ratings,
                BookDetailSection.About,
                BookDetailSection.Hardcover,
                BookDetailSection.Readers,
                BookDetailSection.Details,
            )
        val ready = snapshot().toReady(BookDetailAmbient()).copy(sectionOrder = stageOrder)

        test("a book that is not held shows every section, in the visit's stage order") {
            ready.copy(isHeld = false).shownSections shouldBe stageOrder
        }

        test("a held book is triage-only: About, Chapters and Details, still in stage order") {
            ready.copy(isHeld = true).shownSections shouldBe
                listOf(BookDetailSection.Chapters, BookDetailSection.About, BookDetailSection.Details)
        }

        test("the About clamp on a phone is four lines at every reading stage") {
            ready.copy(readingStage = ReadingStage.NotStarted).aboutLineLimit shouldBe 4
            ready.copy(readingStage = ReadingStage.InProgress).aboutLineLimit shouldBe 4
            ready.copy(readingStage = ReadingStage.Finished).aboutLineLimit shouldBe 4
        }
    })
