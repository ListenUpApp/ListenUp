package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.client.presentation.bookdetail.SeriesShelf
import com.calypsan.listenup.client.presentation.bookdetail.SeriesShelfBook
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * The book's series as square covers in sequence (spec §3.2.2): this book outlined, and a line that says where to go —
 * "Book 1 of 5 · start here" before starting, "Next: book 2" once finished, "Book 3 of 5" otherwise. Each cover is a
 * link to its book; the heading's link opens the series.
 */
@Composable
internal fun SeriesShelfSection(
    shelf: SeriesShelf,
    stage: ReadingStage,
    onOpenBook: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    Panel(
        title = "Series · ${shelf.name}",
        trailing = {
            A(href = "/series/${shelf.seriesId}", attrs = {
                classes("bd-series-open")
                onClick {
                    it.preventDefault()
                    onOpenSeries(shelf.seriesId)
                }
            }) { Text("View series") }
        },
    ) {
        Div(attrs = { classes("bd-series-row") }) {
            shelf.books.forEach { book ->
                key(book.id) {
                    val label = book.label(shelf)
                    A(href = "/book/${book.id}", attrs = {
                        classes("bd-series-cover")
                        if (book.isThis) classes("is-this")
                        attr("aria-label", if (book.isThis) "Book $label, this book" else "Book $label")
                        if (book.isThis) attr("aria-current", "page")
                        onClick {
                            it.preventDefault()
                            onOpenBook(book.id)
                        }
                    }) {
                        // No hero key: the page's own hero cover is the flight target, and two would collide.
                        Cover(
                            title = "Book $label",
                            imageUrl = coverUrl(book.id, book.coverHash, SHELF_COVER_RUNG),
                            size = SHELF_COVER_SIZE,
                            radius = SHELF_COVER_RADIUS,
                            decorative = true,
                        )
                    }
                }
            }
        }
        P(attrs = { classes("bd-series-caption") }) { Text(shelfCaption(shelf, stage)) }
    }
}

/** Where this book sits, and where to go next. */
internal fun shelfCaption(
    shelf: SeriesShelf,
    stage: ReadingStage,
): String {
    val thisLabel = shelf.books[shelf.thisIndex].label(shelf)
    val next = shelf.nextBookId?.let { id -> shelf.books.firstOrNull { it.id == id } }
    return when {
        stage == ReadingStage.NotStarted && shelf.thisIndex == 0 -> "Book $thisLabel of ${shelf.books.size} · start here"
        stage == ReadingStage.Finished && next != null -> "Next: book ${next.label(shelf)}"
        else -> "Book $thisLabel of ${shelf.books.size}"
    }
}

/** The book's place as a person writes it, or its position on the shelf when it is unnumbered. */
private fun SeriesShelfBook.label(shelf: SeriesShelf): String = sequenceLabel ?: (shelf.books.indexOf(this) + 1).toString()

private const val SHELF_COVER_SIZE = 64

private const val SHELF_COVER_RADIUS = 12

/** 64 px at 2x lands on the server's 128 rung. */
private const val SHELF_COVER_RUNG = 128
