package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.client.presentation.bookdetail.SeriesShelf
import com.calypsan.listenup.client.presentation.bookdetail.SeriesShelfBook
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_series_cover_a11y
import listenup.composeapp.generated.resources.book_detail_series_cover_this_a11y
import listenup.composeapp.generated.resources.book_detail_series_heading
import listenup.composeapp.generated.resources.book_detail_series_next
import listenup.composeapp.generated.resources.book_detail_series_position
import listenup.composeapp.generated.resources.book_detail_series_start_here
import org.jetbrains.compose.resources.stringResource

/** A series cover's side (board: 64 dp squares). */
private val ShelfCoverSize = 64.dp

/** The cover's corner, and the ring's, which sits 2 dp outside it. */
private val ShelfCoverShape = RoundedCornerShape(14.dp)
private val ShelfRingShape = RoundedCornerShape(18.dp)

/**
 * The book's series as square covers in sequence (spec §3.2.2): this book ringed, and a line that says where to go —
 * "Book 1 of 5 · start here" before starting, "Next: book 2" once finished, "Book 3 of 5" otherwise. Each cover opens
 * its book; the heading opens the series.
 */
@Composable
internal fun SeriesShelfSection(
    shelf: SeriesShelf,
    stage: ReadingStage,
    onBookClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ContentShapes.card,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.screenMargin), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(Res.string.book_detail_series_heading, shelf.name),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .semantics { heading() }
                        .clickable(role = Role.Button) {
                            haptics.press()
                            onSeriesClick(shelf.seriesId)
                        }.padding(vertical = 8.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shelf.books, key = { it.id }) { book ->
                    ShelfCover(
                        book = book,
                        label = book.label(shelf),
                        onClick = {
                            haptics.press()
                            onBookClick(book.id)
                        },
                    )
                }
            }
            Text(
                text = shelfCaption(shelf, stage),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where this book sits, and where to go next. */
@Composable
private fun shelfCaption(
    shelf: SeriesShelf,
    stage: ReadingStage,
): String {
    val thisLabel = shelf.books[shelf.thisIndex].label(shelf)
    val next = shelf.nextBookId?.let { id -> shelf.books.firstOrNull { it.id == id } }
    return when {
        stage == ReadingStage.NotStarted && shelf.thisIndex == 0 -> {
            stringResource(Res.string.book_detail_series_start_here, thisLabel, shelf.books.size)
        }

        stage == ReadingStage.Finished && next != null -> {
            stringResource(Res.string.book_detail_series_next, next.label(shelf))
        }

        else -> {
            stringResource(Res.string.book_detail_series_position, thisLabel, shelf.books.size)
        }
    }
}

/** The book's place as a person writes it, or its position on the shelf when it is unnumbered. */
private fun SeriesShelfBook.label(shelf: SeriesShelf): String =
    sequenceLabel ?: (shelf.books.indexOf(this) + 1).toString()

@Composable
private fun ShelfCover(
    book: SeriesShelfBook,
    label: String,
    onClick: () -> Unit,
) {
    val name =
        if (book.isThis) {
            stringResource(Res.string.book_detail_series_cover_this_a11y, label)
        } else {
            stringResource(Res.string.book_detail_series_cover_a11y, label)
        }
    val ring = if (book.isThis) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, ShelfRingShape) else Modifier
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .then(ring)
                .padding(4.dp)
                .clip(ShelfCoverShape)
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = name
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                },
    ) {
        BookCoverImage(
            bookId = book.id,
            coverPath = book.coverPath,
            coverHash = book.coverHash,
            contentDescription = null,
            modifier = Modifier.size(ShelfCoverSize).clip(ShelfCoverShape),
        )
    }
}
