package com.calypsan.listenup.client.features.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.library.components.LibraryInboxPill
import com.calypsan.listenup.client.features.library.components.LibrarySectionGroup
import com.calypsan.listenup.client.features.library.components.LibraryStatusChoice
import com.calypsan.listenup.client.features.library.components.SortTrigger
import com.calypsan.listenup.client.features.library.components.libraryCountLine
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory

/**
 * The Android tablet Library's two header bands (spec §3.1.3, board `Library-AndroidTablet`), at ≥ 840dp.
 *
 * - **Band 1:** the title over "248 books · 41 days of listening", the four sections, and the Inbox pill at the end.
 * - **Band 2 (Books only):** Filter and sort at the end ("All · Title, A–Z"; the status lives in its menu). While
 *   books are being picked both bands hold still: the trigger and the pill are stilled in place.
 *
 * The title is the one display moment (M3 Expressive, type: emphasis goes to one element); everything else is the
 * phone's own components, laid out wider.
 *
 * @param onOpenInbox opens the inbox; null when this host has no route to it, so no pill
 */
@Suppress("LongParameterList")
@Composable
internal fun LibraryTabletHeader(
    state: LibraryUiState.Loaded,
    selectedSection: LibrarySection,
    onSelectSection: (LibrarySection) -> Unit,
    heldCount: Int,
    previewBookIds: List<String>,
    onOpenInbox: (() -> Unit)?,
    onEvent: (LibraryUiEvent) -> Unit,
    isInSelectionMode: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier.fillMaxWidth().padding(
                    start = Spacing.screenMargin,
                    end = Spacing.screenMargin,
                    top = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = ShellDestination.Library.title,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = libraryCountLine(all = state.statusCounts.all, totalMs = state.totalDurationMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // The group pads itself by the screen margin, which is the gap from the title.
            LibrarySectionGroup(
                selected = selectedSection,
                onSelect = onSelectSection,
                modifier = Modifier.weight(1f),
            )
            // While books are picked the pill is out of reach but keeps its place, so the sections never jump into it.
            if (onOpenInbox != null) {
                LibraryInboxPill(
                    heldCount = heldCount,
                    previewBookIds = previewBookIds,
                    onOpenInbox = onOpenInbox,
                    enabled = !isInSelectionMode,
                )
            }
        }

        if (selectedSection == LibrarySection.Books) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp, end = Spacing.screenMargin),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                // Filter and sort stays while books are picked, stilled, so the grid below never moves: filtering
                // mid-selection would change what is being picked.
                SortTrigger(
                    state = state.booksSortState,
                    categories = SortCategory.booksCategories,
                    ignoreArticles = state.ignoreTitleArticles,
                    showArticleToggle = state.booksSortState.category == SortCategory.TITLE,
                    onCategorySelected = { onEvent(LibraryUiEvent.BooksCategoryChanged(it)) },
                    onDirectionToggle = { onEvent(LibraryUiEvent.BooksDirectionToggled) },
                    onToggleArticles = { onEvent(LibraryUiEvent.ToggleIgnoreTitleArticles) },
                    status =
                        LibraryStatusChoice(
                            selected = state.statusFilter,
                            counts = state.statusCounts,
                            onSelect = { onEvent(LibraryUiEvent.StatusFilterChanged(it)) },
                        ),
                    enabled = !isInSelectionMode,
                )
            }
        }
    }
}
