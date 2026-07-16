package com.calypsan.listenup.client.features.storyworld

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.storyworld.composer.AnchorSelection
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.story_world_anchor_always_visible
import listenup.composeapp.generated.resources.story_world_anchor_beginning
import listenup.composeapp.generated.resources.story_world_anchor_choose_position
import listenup.composeapp.generated.resources.story_world_anchor_end_of_chapter
import listenup.composeapp.generated.resources.story_world_anchor_picker_footer
import listenup.composeapp.generated.resources.story_world_anchor_playhead_title
import listenup.composeapp.generated.resources.story_world_reveal_after

private val PLAYHEAD_TILE_SIZE = 46.dp
private val BOOK_ROW_TILE_SIZE = 34.dp
private val FOOTER_TEXT_SIZE = 12.5.sp

/** One selectable book of the world, for anchor picking. */
data class AnchorBook(
    val id: String,
    val title: String,
    val sequenceLabel: String?,
    val durationMs: Long,
)

/**
 * Bottom sheet for choosing WHERE a Story World log entry becomes visible while listening — the
 * [AnchorSelection] the composer records against the entry. Pure UI: takes data and callbacks so
 * the composer screen (the ViewModel-backed host) can drive it; this file owns no ViewModel.
 *
 * @param current The anchor currently selected — drives which row/card renders as checked.
 * @param books The world's books in reading order, offered for "Beginning of the book".
 * @param playheadSnapshot The live playhead anchor to offer, or null when not listening to a world book.
 * @param endOfChapterOption The "end of current chapter" anchor to offer, or null when unavailable.
 * @param currentSummary [current]'s localized summary, shown under the sheet title.
 * @param playheadSummary The playhead card's subtitle (e.g. "The Way of Kings · 2h 14m in"), resolved by the host.
 * @param onSelect Called with the chosen [AnchorSelection] — the host applies it and dismisses the sheet.
 * @param onChoosePosition Opens the position scrubber sheet for a custom position.
 * @param onDismiss Dismisses the sheet without changing the anchor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnchorPickerSheet(
    current: AnchorSelection,
    books: List<AnchorBook>,
    playheadSnapshot: AnchorSelection.Playhead?,
    endOfChapterOption: AnchorSelection.EndOfCurrentChapter?,
    currentSummary: String,
    playheadSummary: String?,
    onSelect: (AnchorSelection) -> Unit,
    onChoosePosition: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenMargin, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AnchorPickerHeader(currentSummary = currentSummary)

            if (playheadSnapshot != null && playheadSummary != null) {
                PlayheadCard(
                    summary = playheadSummary,
                    selected = current is AnchorSelection.Playhead,
                    onClick = { onSelect(playheadSnapshot) },
                )
            }

            AnchorOptionsGroup(
                current = current,
                books = books,
                endOfChapterOption = endOfChapterOption,
                onSelect = onSelect,
                onChoosePosition = onChoosePosition,
            )

            AnchorPickerFooter()

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AnchorPickerHeader(currentSummary: String) {
    Text(text = stringResource(Res.string.story_world_reveal_after), style = MaterialTheme.typography.titleLarge)
    Text(
        text = currentSummary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Highlighted "where you are now" card — the live playhead anchor, when available. */
@Composable
private fun PlayheadCard(
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(16.dp)
    val cardModifier = Modifier.fillMaxWidth()
    Surface(
        onClick = onClick,
        modifier =
            if (selected) {
                cardModifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    cardShape,
                )
            } else {
                cardModifier
            },
        shape = cardShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(PLAYHEAD_TILE_SIZE)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.story_world_anchor_playhead_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The neutral group of non-playhead options: beginning, end-of-chapter, always-visible, custom. */
@Composable
private fun AnchorOptionsGroup(
    current: AnchorSelection,
    books: List<AnchorBook>,
    endOfChapterOption: AnchorSelection.EndOfCurrentChapter?,
    onSelect: (AnchorSelection) -> Unit,
    onChoosePosition: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BeginningOfBookRow(current = current, books = books, onSelect = onSelect)

            if (endOfChapterOption != null) {
                AnchorOptionRow(
                    label = stringResource(Res.string.story_world_anchor_end_of_chapter),
                    selected = current is AnchorSelection.EndOfCurrentChapter,
                    onClick = { onSelect(endOfChapterOption) },
                )
            }

            AnchorOptionRow(
                label = stringResource(Res.string.story_world_anchor_always_visible),
                selected = current is AnchorSelection.AlwaysVisible,
                onClick = { onSelect(AnchorSelection.AlwaysVisible) },
            )

            AnchorOptionRow(
                label = stringResource(Res.string.story_world_anchor_choose_position),
                selected = current is AnchorSelection.Custom,
                onClick = onChoosePosition,
            )
        }
    }
}

/**
 * "Beginning of the book" row. A single-book world selects that book immediately on tap; a
 * multi-book world expands an inset list of its books instead of picking sight-unseen.
 */
@Composable
private fun BeginningOfBookRow(
    current: AnchorSelection,
    books: List<AnchorBook>,
    onSelect: (AnchorSelection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val singleBook = books.singleOrNull()
    val selected = current is AnchorSelection.BeginningOfBook

    AnchorOptionRow(
        label = stringResource(Res.string.story_world_anchor_beginning),
        selected = selected,
        onClick = {
            if (singleBook != null) {
                onSelect(AnchorSelection.BeginningOfBook(singleBook.id))
            } else if (books.size > 1) {
                expanded = !expanded
            }
        },
        trailing = {
            if (books.size > 1) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    if (expanded && books.size > 1) {
        Column(modifier = Modifier.fillMaxWidth()) {
            books.forEach { book ->
                InsetBookRow(
                    book = book,
                    selected = current is AnchorSelection.BeginningOfBook && current.bookId == book.id,
                    onClick = { onSelect(AnchorSelection.BeginningOfBook(book.id)) },
                )
            }
        }
    }
}

/** One inset book row inside the expanded "Beginning of the book" list. */
@Composable
private fun InsetBookRow(
    book: AnchorBook,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 52.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(BOOK_ROW_TILE_SIZE)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (book.sequenceLabel ?: book.title).take(1).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** One selectable radio-style row: a leading check/circle glyph, the label, and an optional trailing slot. */
@Composable
private fun AnchorOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing()
        }
    }
}

/** Reassuring footer explaining the spoiler-safe reveal mechanism. */
@Composable
private fun AnchorPickerFooter() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = Icons.Outlined.VisibilityOff,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.story_world_anchor_picker_footer),
            fontSize = FOOTER_TEXT_SIZE,
            lineHeight = FOOTER_TEXT_SIZE * 1.4f,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
