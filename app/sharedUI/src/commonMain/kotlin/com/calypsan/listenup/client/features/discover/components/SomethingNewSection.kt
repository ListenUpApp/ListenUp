package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.AdaptiveCardGrid
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.discover.pickSeriesLine
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUiBook
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_shuffle
import listenup.composeapp.generated.resources.discover_shuffle_a11y
import listenup.composeapp.generated.resources.discover_something_new
import listenup.composeapp.generated.resources.discover_something_new_subtitle
import org.jetbrains.compose.resources.stringResource

private val PICK_CARD_WIDTH = 156.dp
private val PICK_MIN_CELL = 150.dp
private val SHUFFLE_SIZE = 48.dp

/**
 * "Something new": books the reader has never started, first in their series (or standalone), so the
 * shelf keeps offering a way in. Each pick says which book of its series it is and how long it runs;
 * Shuffle draws ten different books.
 *
 * Loading, Error and an empty draw render nothing (PR 6 owns those states).
 *
 * @param wide A wide window: every pick in a wrapping grid (plan Q2) and a labelled Shuffle. Otherwise a
 *   horizontal rail and an icon Shuffle.
 */
@Composable
internal fun SomethingNewSection(
    state: DiscoverBooksUiState,
    onShuffle: () -> Unit,
    onBookClick: (String) -> Unit,
    isInSelectionMode: Boolean,
    selectedBookIds: Set<String>,
    onBookLongPress: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val ready = state as? DiscoverBooksUiState.Ready ?: return
    if (ready.isEmpty) return

    Column(modifier = modifier) {
        SectionTitle(
            title = stringResource(Res.string.discover_something_new),
            subtitle = stringResource(Res.string.discover_something_new_subtitle),
            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
            trailing = { ShuffleButton(onShuffle = onShuffle, labelled = wide) },
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        val card: @Composable (DiscoverUiBook, Dp) -> Unit = { book, width ->
            BookCard(
                cover = book.toCoverModel(),
                onClick = { onBookClick(book.id) },
                captions =
                    listOf(
                        pickSeriesLine(book.seriesName, book.sequenceLabel),
                        DurationFormatter.hoursMinutes(book.durationMs.milliseconds),
                    ),
                cardWidth = width,
                isInSelectionMode = isInSelectionMode,
                isSelected = book.id in selectedBookIds,
                onLongPress = onBookLongPress?.let { cb -> { cb(book.id) } },
            )
        }
        if (wide) {
            AdaptiveCardGrid(
                items = ready.books,
                minCellWidth = PICK_MIN_CELL,
                key = { it.id },
                modifier = Modifier.padding(horizontal = Spacing.screenMargin),
            ) { book, width -> card(book, width) }
        } else {
            BrowseCarousel(
                items = ready.books,
                itemWidth = PICK_CARD_WIDTH,
                itemSpacing = Spacing.md,
                key = { it.id },
            ) { card(it, PICK_CARD_WIDTH) }
        }
    }
}

/** A filled tonal Shuffle: the icon alone on a phone, labelled where there is room. Named for what it does. */
@Composable
private fun ShuffleButton(
    onShuffle: () -> Unit,
    labelled: Boolean,
) {
    val description = stringResource(Res.string.discover_shuffle_a11y)
    if (labelled) {
        FilledTonalButton(
            onClick = onShuffle,
            modifier =
                Modifier.clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        onShuffle()
                        true
                    }
                },
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(Res.string.discover_shuffle))
        }
    } else {
        FilledTonalIconButton(
            onClick = onShuffle,
            modifier = Modifier.size(SHUFFLE_SIZE),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Filled.Shuffle, contentDescription = description)
        }
    }
}
