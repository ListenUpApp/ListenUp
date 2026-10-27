package com.calypsan.listenup.client.presentation.bookdetail

/**
 * Book Detail's two wide columns (spec §3.2.4: Android tablet ≥ 840 dp, web desktop ≥ 1024 px). Each column keeps the
 * stage order it was given, and a section never changes column.
 *
 * @property main The reading column: Chapters and About (Story World joins it when it exists).
 * @property side The social and reference column: Readers, Ratings, Hardcover and Details.
 */
data class BookDetailColumns(
    val main: List<BookDetailSection>,
    val side: List<BookDetailSection>,
)

/** Splits a stage [order] into the two wide columns. Pure. */
fun bookDetailColumns(order: List<BookDetailSection>): BookDetailColumns {
    val (main, side) = order.partition { it.isMainColumn() }
    return BookDetailColumns(main = main, side = side)
}

private fun BookDetailSection.isMainColumn(): Boolean =
    when (this) {
        BookDetailSection.Chapters, BookDetailSection.About -> true
        BookDetailSection.Readers, BookDetailSection.Ratings, BookDetailSection.Hardcover, BookDetailSection.Details -> false
    }
