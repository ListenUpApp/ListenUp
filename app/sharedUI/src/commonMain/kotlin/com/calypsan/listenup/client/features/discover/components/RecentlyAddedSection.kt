package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AdaptiveCardGrid
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.discover.addedLine
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiBook
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import kotlin.time.Clock
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_in_library
import listenup.composeapp.generated.resources.discover_recently_added
import listenup.composeapp.generated.resources.discover_show_newest_in_library_a11y
import org.jetbrains.compose.resources.stringResource

private val RECENT_CARD_WIDTH = 132.dp
private val RECENT_MIN_CELL = 120.dp

/**
 * "Recently added", Discover's last section: the newest books on the server, each saying when it was
 * added ("Added 2 days ago"), and an "In Library" link that opens the Library sorted newest first.
 *
 * Loading, Error and an empty list render nothing (PR 6).
 *
 * @param wide A wide window: every book in a wrapping grid (plan Q2). Otherwise a horizontal rail.
 */
@Composable
internal fun RecentlyAddedSection(
    state: RecentlyAddedUiState,
    onBookClick: (String) -> Unit,
    onOpenLibraryNewest: () -> Unit,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
    selectedBookIds: Set<String> = emptySet(),
    onBookLongPress: ((String) -> Unit)? = null,
    wide: Boolean = false,
    nowMs: Long = remember { Clock.System.now().toEpochMilliseconds() },
) {
    val ready = state as? RecentlyAddedUiState.Ready ?: return
    if (ready.isEmpty) return

    Column(modifier = modifier) {
        val linkLabel = stringResource(Res.string.discover_show_newest_in_library_a11y)
        SectionTitle(
            title = stringResource(Res.string.discover_recently_added),
            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
            trailing = {
                TextButton(
                    onClick = onOpenLibraryNewest,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = linkLabel },
                ) {
                    Text(stringResource(Res.string.common_in_library))
                }
            },
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        val card: @Composable (RecentlyAddedUiBook, Dp) -> Unit = { book, width ->
            BookCard(
                cover = book.toCoverModel(),
                onClick = { onBookClick(book.id) },
                captions = listOf(addedLine(book.createdAt, nowMs)),
                cardWidth = width,
                isInSelectionMode = isInSelectionMode,
                isSelected = book.id in selectedBookIds,
                onLongPress = onBookLongPress?.let { cb -> { cb(book.id) } },
            )
        }
        if (wide) {
            AdaptiveCardGrid(
                items = ready.books,
                minCellWidth = RECENT_MIN_CELL,
                key = { it.id },
                modifier = Modifier.padding(horizontal = Spacing.screenMargin),
            ) { book, width -> card(book, width) }
        } else {
            BrowseCarousel(
                items = ready.books,
                itemWidth = RECENT_CARD_WIDTH,
                itemSpacing = Spacing.md,
                key = { it.id },
            ) { card(it, RECENT_CARD_WIDTH) }
        }
    }
}
