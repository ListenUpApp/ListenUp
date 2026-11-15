package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.theme.Spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.testTag
import com.calypsan.listenup.client.design.components.InlineRetry
import com.calypsan.listenup.client.design.components.RailSkeleton
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import listenup.composeapp.generated.resources.discover_error_picks
import listenup.composeapp.generated.resources.discover_no_new_books_yet
import listenup.composeapp.generated.resources.discover_started_everything
import listenup.composeapp.generated.resources.home_browse_library
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
private val SHUFFLE_SIZE = 48.dp

/** Enough placeholders to run past the edge of any window, as the picks will; the row clips the rest. */
private const val PICK_SKELETON_CARDS = 10

/** The picks' row, for tests to find and scroll. */
internal const val SOMETHING_NEW_ROW_TAG = "something-new-row"

/**
 * "Something new": books the reader has never started, first in their series (or standalone), so the
 * shelf keeps offering a way in. Each pick says which book of its series it is and how long it runs;
 * Shuffle draws ten different books.
 *
 * Every state keeps the heading: a skeleton while drawing, a line and Try again (a fresh draw) on
 * failure, "You've started every book here." with Browse the Library, or a plain line for an empty library.
 *
 * The picks are one row at every width, never a wrapping grid: an uncontained carousel (M3 Carousel,
 * "items that are a single size and flow past the edge"), so the next cover peeks at the edge and says
 * there is more. Ten picks in a grid wrapped unevenly on a wide window.
 *
 * @param wide A wide window: a labelled Shuffle. Otherwise an icon Shuffle.
 */
@Composable
internal fun SomethingNewSection(
    state: DiscoverBooksUiState,
    onShuffle: () -> Unit,
    onRetry: () -> Unit,
    onBrowseLibrary: (() -> Unit)?,
    onBookClick: (String) -> Unit,
    isInSelectionMode: Boolean,
    selectedBookIds: Set<String>,
    onBookLongPress: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    // Shuffle draws again; with nothing to draw (all started) or a failure (Try again is the action) it steps aside.
    val canShuffle = state is DiscoverBooksUiState.Ready || state is DiscoverBooksUiState.Loading
    val shuffleSlot: @Composable () -> Unit = { ShuffleButton(onShuffle = onShuffle, labelled = wide) }
    Column(modifier = modifier) {
        SectionTitle(
            title = stringResource(Res.string.discover_something_new),
            subtitle = stringResource(Res.string.discover_something_new_subtitle),
            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
            trailing = shuffleSlot.takeIf { canShuffle },
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        when (state) {
            DiscoverBooksUiState.Loading -> {
                RailSkeleton(
                    cardWidth = PICK_CARD_WIDTH,
                    captionLines = 3,
                    count = PICK_SKELETON_CARDS,
                    modifier = Modifier.padding(horizontal = Spacing.screenMargin).testTag("picks-skeleton"),
                )
            }

            is DiscoverBooksUiState.Error -> {
                InlineRetry(
                    line = stringResource(Res.string.discover_error_picks),
                    onRetry = onRetry,
                    modifier = Modifier.padding(horizontal = Spacing.screenMargin),
                )
            }

            DiscoverBooksUiState.AllStarted -> {
                StartedEverything(onBrowseLibrary = onBrowseLibrary)
            }

            is DiscoverBooksUiState.Ready -> {
                if (state.isEmpty) {
                    Text(
                        text = stringResource(Res.string.discover_no_new_books_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.screenMargin),
                    )
                } else {
                    PickCards(
                        books = state.books,
                        onBookClick = onBookClick,
                        isInSelectionMode = isInSelectionMode,
                        selectedBookIds = selectedBookIds,
                        onBookLongPress = onBookLongPress,
                    )
                }
            }
        }
    }
}

/** "You've started every book here." and, where the shell offers it, **Browse the Library**. */
@Composable
private fun StartedEverything(onBrowseLibrary: (() -> Unit)?) {
    val haptics = LocalHaptics.current
    Column(
        modifier = Modifier.padding(horizontal = Spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = stringResource(Res.string.discover_started_everything),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onBrowseLibrary != null) {
            FilledTonalButton(
                onClick = {
                    haptics.press()
                    onBrowseLibrary()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(Res.string.home_browse_library))
            }
        }
    }
}

/** The picks as one row that scrolls sideways, at every width. */
@Composable
private fun PickCards(
    books: List<DiscoverUiBook>,
    onBookClick: (String) -> Unit,
    isInSelectionMode: Boolean,
    selectedBookIds: Set<String>,
    onBookLongPress: ((String) -> Unit)?,
) {
    BrowseCarousel(
        items = books,
        itemWidth = PICK_CARD_WIDTH,
        itemSpacing = Spacing.md,
        key = { it.id },
        modifier = Modifier.testTag(SOMETHING_NEW_ROW_TAG),
    ) { book ->
        BookCard(
            cover = book.toCoverModel(),
            onClick = { onBookClick(book.id) },
            captions =
                listOfNotNull(
                    pickSeriesLine(book.seriesName, book.sequenceLabel),
                    DurationFormatter.hoursMinutes(book.durationMs.milliseconds),
                ),
            cardWidth = PICK_CARD_WIDTH,
            isInSelectionMode = isInSelectionMode,
            isSelected = book.id in selectedBookIds,
            onLongPress = onBookLongPress?.let { cb -> { cb(book.id) } },
        )
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
            Icon(
                ListenUpIcons.ShuffleFilled,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(Res.string.discover_shuffle))
        }
    } else {
        FilledTonalIconButton(
            onClick = onShuffle,
            modifier = Modifier.size(SHUFFLE_SIZE),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(ListenUpIcons.ShuffleFilled, contentDescription = description)
        }
    }
}
