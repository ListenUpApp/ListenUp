package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_time_left
import listenup.composeapp.generated.resources.home_also_in_progress
import listenup.composeapp.generated.resources.home_card_by_author
import listenup.composeapp.generated.resources.home_in_library
import listenup.composeapp.generated.resources.library_card_progress_a11y
import listenup.composeapp.generated.resources.library_card_read_by
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/** A phone rail card's width (board `Main`). */
private val RailCardWidth = 156.dp

/** The narrowest column a wide row gives a card before it drops to fewer columns. */
private val RowCardMinWidth = 140.dp

/** A wide row shows one line of at most this many cards (spec §2.5: Android tablet 6). */
private const val ROW_CARD_CAP = 6

/**
 * "Also in progress" (spec §2.5): the books in progress that are neither loaded nor in the slot. Absent entirely when
 * there are none (the caller drops the block). The header ends with In Library, which opens the Library (PR 4 adds the
 * In progress filter).
 */
@Composable
internal fun AlsoInProgressSection(
    items: List<ContinueListeningItem>,
    width: HomeWidth,
    selection: HomeSelection,
    onOpenBook: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(
            title = stringResource(Res.string.home_also_in_progress),
            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
            trailing = {
                TextButton(onClick = onOpenLibrary) { Text(stringResource(Res.string.home_in_library)) }
            },
        )
        Spacer(Modifier.height(Spacing.titleGap))
        if (width == HomeWidth.Compact) {
            BrowseCarousel(
                items = items,
                itemWidth = RailCardWidth,
                itemSpacing = Spacing.itemGap,
                contentPadding = PaddingValues(horizontal = Spacing.screenMargin),
                key = { it.bookId },
            ) { item ->
                HomeBookCard(
                    item = item,
                    isWide = false,
                    selection = selection,
                    onOpenBook = onOpenBook,
                    cardWidth = RailCardWidth,
                )
            }
        } else {
            AdaptiveCardRow(items = items, selection = selection, onOpenBook = onOpenBook)
        }
    }
}

/** One row of equal columns; the count flows with the width (rule 11), capped at [ROW_CARD_CAP]. */
@Composable
private fun AdaptiveCardRow(
    items: List<ContinueListeningItem>,
    selection: HomeSelection,
    onOpenBook: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin)) {
        val gap = Spacing.lg
        val columns = ((maxWidth + gap) / (RowCardMinWidth + gap)).toInt().coerceIn(1, ROW_CARD_CAP)
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            items.take(columns).forEach { item ->
                Box(Modifier.weight(1f)) {
                    HomeBookCard(
                        item = item,
                        isWide = true,
                        selection = selection,
                        onOpenBook = onOpenBook,
                        cardWidth = null,
                    )
                }
            }
            // Keep the cards at their column width when there are fewer books than columns.
            repeat((columns - items.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** A Home card: Library's card (C4) with Home's one label (§8.2). A `Loading` item is a same-size skeleton. */
@Composable
internal fun HomeBookCard(
    item: ContinueListeningItem,
    isWide: Boolean,
    selection: HomeSelection,
    onOpenBook: (String) -> Unit,
    cardWidth: Dp?,
) {
    when (item) {
        is ContinueListeningItem.Loading -> {
            Box(
                Modifier
                    .then(if (cardWidth != null) Modifier.width(cardWidth) else Modifier.fillMaxWidth())
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, ContentShapes.card),
            )
        }

        is ContinueListeningItem.Ready -> {
            val book = item.book
            BookCard(
                cover = book.toCoverModel(),
                onClick = { if (selection.isActive) selection.onToggle(book.bookId) else onOpenBook(book.bookId) },
                narrators = if (isWide) book.narratorNames else null,
                libraryStatus = BookCardStatus.InProgress(fraction = book.progress, timeLeftMs = book.timeRemainingMs),
                progressUnderTitle = !isWide,
                isInSelectionMode = selection.isActive,
                isSelected = book.bookId in selection.selectedIds,
                onLongPress = { selection.onEnter(book.bookId) },
                cardWidth = cardWidth,
                accessibilityLabel = homeCardLabel(book, withNarrator = isWide),
            )
        }
    }
}

/**
 * "Babel, by R. F. Kuang, 11 percent listened, 18h 40m left" (spec §2.5); wide windows add "Read by …". The time left
 * is Library's wording on every card, whatever its length (open question 6: no "Almost done").
 */
@Composable
internal fun homeCardLabel(
    book: ContinueListeningBook,
    withNarrator: Boolean,
): String =
    listOfNotNull(
        book.title,
        book.authorNames.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.home_card_by_author, it) },
        book.narratorNames
            ?.takeIf { withNarrator && it.isNotBlank() }
            ?.let { stringResource(Res.string.library_card_read_by, it) },
        stringResource(Res.string.library_card_progress_a11y, book.progressPercent),
        stringResource(Res.string.book_time_left, DurationFormatter.hoursMinutes(book.timeRemainingMs.milliseconds)),
    ).joinToString(", ")
