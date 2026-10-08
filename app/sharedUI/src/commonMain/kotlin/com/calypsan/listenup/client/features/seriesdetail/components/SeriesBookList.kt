package com.calypsan.listenup.client.features.seriesdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.CountBadge
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesBookSection
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesSectionKind
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_collapse
import listenup.composeapp.generated.resources.common_expand
import listenup.composeapp.generated.resources.series_also_in
import listenup.composeapp.generated.resources.series_books
import listenup.composeapp.generated.resources.series_books_in_series
import listenup.composeapp.generated.resources.series_show_all
import org.jetbrains.compose.resources.stringResource

/** How the book list draws a book: a full-width row on a phone, a cover card in the wide grid. */
internal enum class SeriesBookLayout { Rows, Cards }

/** What the book list does when tapped. */
internal data class SeriesBookListActions(
    val onBookClick: (String) -> Unit,
    val onSeriesClick: (String) -> Unit,
    val onToggleSection: (String) -> Unit,
)

/**
 * Where the group headings pin: [gridState] is the grid they scroll in, and [topInset] how far below
 * the grid's top edge the window's own top bar reaches — the status bar, on the edge-to-edge page.
 */
internal data class StickyHeadings(
    val gridState: LazyGridState,
    val topInset: Dp,
)

/**
 * The series page's books. A flat series lists them under "Books in series", exactly as before the
 * hierarchy; a parent groups them under each sub-series heading (each a link to that series, each
 * foldable) and ends with "Also in Cosmere". A folded group shows its heading and "Show all N".
 *
 * Group headings stick under the status bar while their books scroll beneath, as they do on web.
 */
internal fun LazyGridScope.seriesBookList(
    state: SeriesDetailUiState.Ready,
    layout: SeriesBookLayout,
    actions: SeriesBookListActions,
    gutter: Dp,
    sticky: StickyHeadings,
) {
    item(key = "books-header", span = { GridItemSpan(maxLineSpan) }) {
        BooksHeader(
            isGrouped = state.isGrouped,
            count = state.books.size,
            modifier = Modifier.padding(start = gutter + 4.dp, end = gutter, top = 10.dp),
        )
    }
    if (!state.isGrouped) {
        bookItems(
            state = state,
            books = state.books,
            keyPrefix = "flat",
            sectionSeriesId = null,
            layout = layout,
            actions = actions,
            gutter = gutter,
        )
        return
    }
    state.bookSections.forEach { section ->
        stickyHeader(key = HEADING_KEY_PREFIX + section.key) { index ->
            StickyHeadingSurface(sticky = sticky, index = index) {
                SeriesSectionHeading(
                    section = section,
                    isLink = section.seriesId != state.seriesId,
                    actions = actions,
                    modifier = Modifier.padding(start = gutter + 4.dp * section.depth, end = gutter),
                )
            }
        }
        if (section.isCollapsed) {
            item(key = "show-all:${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                ShowAllButton(
                    count = section.bookCount,
                    onClick = { actions.onToggleSection(section.seriesId) },
                    modifier = Modifier.padding(horizontal = gutter),
                )
            }
        } else {
            bookItems(
                state = state,
                books = section.books,
                keyPrefix = section.key,
                sectionSeriesId = section.seriesId,
                layout = layout,
                actions = actions,
                gutter = gutter,
            )
        }
    }
}

private const val HEADING_KEY_PREFIX = "heading:"

/**
 * A group heading's opaque backing, and the shift that keeps its text below the status bar once it
 * pins. The shift is placement only — the grid still sees the heading's own height — so rows never
 * jump as a heading arrives at the top; the backing fills the status bar's strip above it.
 */
@Composable
private fun StickyHeadingSurface(
    sticky: StickyHeadings,
    index: Int,
    content: @Composable () -> Unit,
) {
    val color = MaterialTheme.colorScheme.surface
    val insetPx = with(LocalDensity.current) { sticky.topInset.roundToPx() }
    var shift by remember { mutableIntStateOf(0) }
    Layout(
        content = content,
        modifier =
            Modifier
                // Above the rows it slides over: a heading shifted below its own slot overlaps the next one.
                .zIndex(1f)
                .drawBehind { drawRect(color, size = Size(size.width, size.height + shift)) },
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints)
        layout(placeable.width, placeable.height) {
            val items = sticky.gridState.layoutInfo.visibleItemsInfo
            shift =
                stickyHeadingShift(
                    headingOffset = items.firstOrNull { it.index == index }?.run { offset.y } ?: 0,
                    nextHeadingOffset =
                        items
                            .firstOrNull { item ->
                                item.index > index &&
                                    (item.key as? String)?.startsWith(HEADING_KEY_PREFIX) == true
                            }?.run { offset.y },
                    headingHeight = placeable.height,
                    topInset = insetPx,
                )
            placeable.place(0, shift)
        }
    }
}

/**
 * How far below its slot a group heading draws its text, in px, so the text never sits under the
 * window's top bar ([topInset] tall).
 *
 * The heading's text wants to sit at [topInset] — but no lower than its own slot, and no lower than
 * just above the next heading, which pushes it out as it arrives (and itself settles at [topInset]).
 * [headingOffset] is the slot's top in the grid, as placed (0, or less while being pushed, once it
 * pins); [nextHeadingOffset] is the next heading's slot, when one is on screen.
 */
internal fun stickyHeadingShift(
    headingOffset: Int,
    nextHeadingOffset: Int?,
    headingHeight: Int,
    topInset: Int,
): Int {
    val textTop =
        nextHeadingOffset?.let { next -> minOf(topInset, maxOf(topInset, next) - headingHeight) } ?: topInset
    return (textTop - headingOffset).coerceIn(0, topInset)
}

private fun LazyGridScope.bookItems(
    state: SeriesDetailUiState.Ready,
    books: List<BookListItem>,
    keyPrefix: String,
    sectionSeriesId: String?,
    layout: SeriesBookLayout,
    actions: SeriesBookListActions,
    gutter: Dp,
) {
    itemsIndexed(books, key = { _, book -> "$keyPrefix/${book.id.value}" }) { index, book ->
        // A grouped book's number is its place in the group's own series, not in the page's.
        val sequence =
            if (sectionSeriesId == null) {
                book.seriesSequenceLabel
            } else {
                book.series.firstOrNull { it.seriesId == sectionSeriesId }?.sequenceLabel
            }
        val positionLabel = sequence ?: (index + 1).toString()
        val progress = state.bookProgress[book.id]
        val finished = book.id in state.finishedBookIds
        val highlighted = book.id == state.resumeTarget && progress != null
        when (layout) {
            SeriesBookLayout.Rows -> {
                SeriesBookRow(
                    book = book,
                    positionLabel = positionLabel,
                    finished = finished,
                    progress = progress,
                    highlighted = highlighted,
                    onClick = { actions.onBookClick(book.id.value) },
                    modifier = Modifier.padding(horizontal = gutter),
                )
            }

            SeriesBookLayout.Cards -> {
                SeriesBookCard(
                    book = book,
                    positionLabel = positionLabel,
                    finished = finished,
                    progress = progress,
                    highlighted = highlighted,
                    onClick = { actions.onBookClick(book.id.value) },
                )
            }
        }
    }
}

@Composable
private fun BooksHeader(
    isGrouped: Boolean,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(if (isGrouped) Res.string.series_books else Res.string.series_books_in_series),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.semantics { heading() },
        )
        CountBadge(count = count)
    }
}

/**
 * A group's heading: the sub-series' name (or "Also in X" for a series' own books) and its count.
 * Tapping it opens that series; the trailing button folds or unfolds the group.
 */
@Composable
internal fun SeriesSectionHeading(
    section: SeriesBookSection,
    isLink: Boolean,
    actions: SeriesBookListActions,
    modifier: Modifier = Modifier,
) {
    val nested = section.depth > 1
    Row(
        modifier = modifier.fillMaxWidth().padding(top = if (nested) 2.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeadingLink(
            section = section,
            onClick = { actions.onSeriesClick(section.seriesId) }.takeIf { isLink },
            modifier = Modifier.weight(1f),
        )
        if (section.isCollapsible) {
            FoldToggle(section = section, onToggle = { actions.onToggleSection(section.seriesId) })
        }
    }
}

@Composable
private fun HeadingLink(
    section: SeriesBookSection,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val nested = section.depth > 1
    val title =
        if (section.kind == SeriesSectionKind.SUB_SERIES) {
            section.title
        } else {
            stringResource(Res.string.series_also_in, section.title)
        }
    val clickModifier =
        onClick?.let { click ->
            Modifier.clickable {
                haptics.press()
                click()
            }
        } ?: Modifier
    Row(
        modifier = modifier.heightIn(min = 48.dp).then(clickModifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = title,
                style = if (nested) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (nested) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = bookCountLabel(section.bookCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FoldToggle(
    section: SeriesBookSection,
    onToggle: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val label = stringResource(if (section.isCollapsed) Res.string.common_expand else Res.string.common_collapse)
    IconButton(
        onClick = {
            haptics.press()
            onToggle()
        },
    ) {
        Icon(
            imageVector = if (section.isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
            contentDescription = "$label ${section.title}",
        )
    }
}

@Composable
private fun ShowAllButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    FilledTonalButton(
        onClick = {
            haptics.press()
            onClick()
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(stringResource(Res.string.series_show_all, count))
    }
}
