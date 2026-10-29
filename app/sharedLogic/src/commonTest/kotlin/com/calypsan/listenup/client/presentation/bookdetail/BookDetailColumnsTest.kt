package com.calypsan.listenup.client.presentation.bookdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookDetailColumnsTest :
    FunSpec({
        test("in progress: Chapters and About in the main column; the rest in the side, each in stage order") {
            val order =
                listOf(
                    BookDetailSection.Chapters,
                    BookDetailSection.About,
                    BookDetailSection.Readers,
                    BookDetailSection.Ratings,
                    BookDetailSection.Hardcover,
                    BookDetailSection.Details,
                )
            bookDetailColumns(order) shouldBe
                BookDetailColumns(
                    main = listOf(BookDetailSection.Chapters, BookDetailSection.About),
                    side =
                        listOf(
                            BookDetailSection.Readers,
                            BookDetailSection.Ratings,
                            BookDetailSection.Hardcover,
                            BookDetailSection.Details,
                        ),
                )
        }

        test("not started puts About first in main and Ratings first in side") {
            val order =
                listOf(
                    BookDetailSection.About,
                    BookDetailSection.Ratings,
                    BookDetailSection.Hardcover,
                    BookDetailSection.Readers,
                    BookDetailSection.Chapters,
                    BookDetailSection.Details,
                )
            val columns = bookDetailColumns(order)
            columns.main.first() shouldBe BookDetailSection.About
            columns.side.first() shouldBe BookDetailSection.Ratings
        }

        test("a section never changes column, whatever the stage") {
            val finished =
                listOf(
                    BookDetailSection.Ratings,
                    BookDetailSection.Hardcover,
                    BookDetailSection.Readers,
                    BookDetailSection.Chapters,
                    BookDetailSection.About,
                    BookDetailSection.Details,
                )
            bookDetailColumns(finished).main shouldBe
                listOf(BookDetailSection.Chapters, BookDetailSection.About)
        }
    })
