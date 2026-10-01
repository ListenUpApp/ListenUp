package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpSearchField
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.RatingLabels
import com.calypsan.listenup.client.presentation.error.localizedString
import com.calypsan.listenup.client.presentation.hardcover.HardcoverCandidateRow
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchEvent
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchViewModel
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSearchState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.hardcover_match_audiobook
import listenup.composeapp.generated.resources.hardcover_match_book_missing
import listenup.composeapp.generated.resources.hardcover_match_by_author
import listenup.composeapp.generated.resources.hardcover_match_current
import listenup.composeapp.generated.resources.hardcover_match_failed
import listenup.composeapp.generated.resources.hardcover_match_linked
import listenup.composeapp.generated.resources.hardcover_match_linked_with_detail
import listenup.composeapp.generated.resources.hardcover_match_matching
import listenup.composeapp.generated.resources.hardcover_match_no_results_detail
import listenup.composeapp.generated.resources.hardcover_match_no_results_title
import listenup.composeapp.generated.resources.hardcover_match_other_results
import listenup.composeapp.generated.resources.hardcover_match_pick_a11y
import listenup.composeapp.generated.resources.hardcover_match_ratings
import listenup.composeapp.generated.resources.hardcover_match_ratings_none
import listenup.composeapp.generated.resources.hardcover_match_ratings_one
import listenup.composeapp.generated.resources.hardcover_match_remove
import listenup.composeapp.generated.resources.hardcover_match_remove_detail
import listenup.composeapp.generated.resources.hardcover_match_results
import listenup.composeapp.generated.resources.hardcover_match_search_for
import listenup.composeapp.generated.resources.hardcover_match_search_placeholder
import listenup.composeapp.generated.resources.hardcover_match_searching
import listenup.composeapp.generated.resources.hardcover_match_title
import listenup.composeapp.generated.resources.hardcover_match_unknown_author
import listenup.composeapp.generated.resources.hardcover_match_weak_detail
import listenup.composeapp.generated.resources.hardcover_match_weak_title
import listenup.composeapp.generated.resources.hardcover_try_again
import listenup.composeapp.generated.resources.hardcover_undo
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private val ResultMinWidth = 340.dp
private val ResultGap = 2.dp
private val GroupCorner = 20.dp
private val InnerCorner = 4.dp
private val MiniCoverWidth = 28.dp
private val MiniCoverHeight = 42.dp
private val EmptyGlyphSize = 72.dp
private val SkeletonLineHeight = 12.dp
private const val SKELETON_ROWS = 3
private const val SHOWN_NAMES = 2

/**
 * Find on Hardcover for one book: opened from Needs a match, or from Book Detail's "Find on
 * Hardcover" and "Change match". Picking a result links it in one tap and closes; [onLinked] then
 * says what it is matched to, with Undo — on the shell's snackbar, since this screen is gone by then.
 * Remove match leaves the book needing one.
 *
 * @param bookId The ListenUp book being matched; scopes the [HardcoverMatchViewModel].
 * @param onNavigateBack Close the screen.
 * @param onLinked Close the screen and say [message], offering [undoLabel] to run [undo].
 */
@Composable
fun HardcoverMatchScreen(
    bookId: String,
    onNavigateBack: () -> Unit,
    onLinked: (message: String, undoLabel: String, undo: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HardcoverMatchViewModel = koinViewModel(parameters = { parametersOf(bookId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val linked by rememberUpdatedState(onLinked)
    val back by rememberUpdatedState(onNavigateBack)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HardcoverMatchEvent.Linked -> {
                    linked(linkedMessage(event.picked), getString(Res.string.hardcover_undo), viewModel::undoLink)
                }

                HardcoverMatchEvent.MatchRemoved -> {
                    back()
                }

                is HardcoverMatchEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.error.localizedString())
                }
            }
        }
    }
    ListenUpScaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.hardcover_match_title),
                onBack = onNavigateBack,
            )
        },
    ) { padding ->
        HardcoverMatchContent(
            state = state,
            onQueryChange = viewModel::onQueryChange,
            onSearch = viewModel::search,
            onSearchFor = viewModel::searchFor,
            onPick = viewModel::link,
            onRemoveMatch = viewModel::removeMatch,
            modifier = Modifier.padding(padding),
        )
    }
}

/** "Matched to Project Hail Mary (Audiobook, 2021)": what was picked, told apart as the row told it. */
private suspend fun linkedMessage(picked: HardcoverCandidateRow): String {
    val detail =
        listOfNotNull(
            if (picked.hasAudiobookEdition) getString(Res.string.hardcover_match_audiobook) else null,
            picked.releaseYear?.toString(),
        ).joinToString(", ")
    return if (detail.isEmpty()) {
        getString(Res.string.hardcover_match_linked, picked.title)
    } else {
        getString(Res.string.hardcover_match_linked_with_detail, picked.title, detail)
    }
}

/**
 * Find on Hardcover's body for [state]. The results come grouped: the book's own author first under
 * "By {author}", everything else quieter under "Other results" — or, when nothing is by the author,
 * a hint and one better search. Results flow into as many columns as the width affords (sharedUI
 * rule 11); everything else spans them.
 */
@Composable
internal fun HardcoverMatchContent(
    state: HardcoverMatchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchFor: (String) -> Unit,
    onPick: (hcBookId: Long) -> Unit,
    onRemoveMatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        HardcoverMatchUiState.Loading -> {
            FullScreenLoadingIndicator(modifier = modifier)
        }

        HardcoverMatchUiState.BookMissing -> {
            CenteredNote(stringResource(Res.string.hardcover_match_book_missing), modifier)
        }

        is HardcoverMatchUiState.Ready -> {
            ReadyContent(state, onQueryChange, onSearch, onSearchFor, onPick, onRemoveMatch, modifier)
        }
    }
}

@Composable
private fun ReadyContent(
    state: HardcoverMatchUiState.Ready,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchFor: (String) -> Unit,
    onPick: (hcBookId: Long) -> Unit,
    onRemoveMatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The same count GridCells.Adaptive arrives at, so a one-column list draws as one segmented group.
        val columns = columnsFor(maxWidth - Spacing.lg * 2)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = ResultMinWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(ResultGap),
            verticalArrangement = Arrangement.spacedBy(ResultGap),
        ) {
            fullWidth {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    ListenUpSearchField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        onSubmit = onSearch,
                        placeholder = stringResource(Res.string.hardcover_match_search_placeholder),
                        onClear = { onQueryChange("") },
                    )
                    MatchingLine(state)
                    state.currentMatch?.let {
                        CurrentMatch(
                            it,
                            isRemoving = state.isRemoving,
                            onRemove = onRemoveMatch,
                        )
                    }
                }
            }
            searchBody(state, columns, onSearch, onSearchFor, onPick)
        }
    }
}

private fun LazyGridScope.searchBody(
    state: HardcoverMatchUiState.Ready,
    columns: Int,
    onSearch: () -> Unit,
    onSearchFor: (String) -> Unit,
    onPick: (hcBookId: Long) -> Unit,
) {
    when (val search = state.search) {
        HardcoverSearchState.Searching -> {
            fullWidth { SearchingSkeleton() }
        }

        HardcoverSearchState.NoResults -> {
            fullWidth { NoResults(query = state.query, suggestions = state.suggestions, onSearchFor = onSearchFor) }
        }

        is HardcoverSearchState.Failed -> {
            fullWidth {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CenteredNote(stringResource(Res.string.hardcover_match_failed))
                    TextButton(onClick = onSearch, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.hardcover_try_again))
                    }
                }
            }
        }

        is HardcoverSearchState.Results -> {
            val enabled = state.linkingId == null && !state.isRemoving
            if (search.byAuthor.isEmpty()) {
                if (state.bookAuthors.isNotBlank()) {
                    fullWidth { WeakResultsHint(state.bookAuthors, state.suggestions, onSearchFor) }
                }
                groupHeader { GroupHeader(stringResource(Res.string.hardcover_match_results), strong = false) }
                resultGroup(search.others, columns, strong = false, state.linkingId, enabled, onPick)
            } else {
                groupHeader {
                    GroupHeader(stringResource(Res.string.hardcover_match_by_author, state.bookAuthors), strong = true)
                }
                resultGroup(search.byAuthor, columns, strong = true, state.linkingId, enabled, onPick)
                if (search.others.isNotEmpty()) {
                    groupHeader {
                        GroupHeader(
                            stringResource(Res.string.hardcover_match_other_results),
                            strong = false,
                        )
                    }
                    resultGroup(search.others, columns, strong = false, state.linkingId, enabled, onPick)
                }
            }
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.groupHeader(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Box(modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.sm)) { content() }
    }
}

private fun LazyGridScope.resultGroup(
    rows: List<HardcoverCandidateRow>,
    columns: Int,
    strong: Boolean,
    linkingId: Long?,
    enabled: Boolean,
    onPick: (hcBookId: Long) -> Unit,
) {
    itemsIndexed(rows, key = { _, row -> "${row.hcBookId}:$strong" }) { index, row ->
        CandidateRow(
            row = row,
            strong = strong,
            shape = groupShape(index, rows.size, columns),
            isLinking = linkingId == row.hcBookId,
            enabled = enabled,
            onPick = { onPick(row.hcBookId) },
        )
    }
}

/** How many columns [GridCells.Adaptive] gives [width] at [ResultMinWidth] and [ResultGap]. */
private fun columnsFor(width: Dp): Int = ((width + ResultGap) / (ResultMinWidth + ResultGap)).toInt().coerceAtLeast(1)

/**
 * One column reads as a Material 3 Expressive segmented list: rounded ends, small inner corners.
 * Several columns are a grid of cards, each with the group corner.
 */
private fun groupShape(
    index: Int,
    count: Int,
    columns: Int,
): Shape {
    if (columns > 1) return RoundedCornerShape(GroupCorner)
    val top = if (index == 0) GroupCorner else InnerCorner
    val bottom = if (index == count - 1) GroupCorner else InnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun GroupHeader(
    text: String,
    strong: Boolean,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.lg).semantics { heading() },
    )
}

/** "Matching **Project Hail Mary** · Andy Weir", beside a small cover: the book this search is for. */
@Composable
private fun MatchingLine(state: HardcoverMatchUiState.Ready) {
    val strong = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    val lead = stringResource(Res.string.hardcover_match_matching, state.bookTitle)
    Row(
        modifier = Modifier.padding(horizontal = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCoverImage(
            bookId = state.bookId,
            coverPath = state.coverPath,
            coverHash = state.coverHash,
            contentDescription = null,
            title = state.bookTitle,
            author = state.bookAuthors,
            modifier = Modifier.size(width = MiniCoverWidth, height = MiniCoverHeight),
        )
        Text(
            text =
                buildAnnotatedString {
                    val at = lead.indexOf(state.bookTitle)
                    if (at < 0) {
                        append(lead)
                    } else {
                        append(lead.substring(0, at))
                        withStyle(strong) { append(state.bookTitle) }
                        append(lead.substring(at + state.bookTitle.length))
                    }
                    if (state.bookAuthors.isNotBlank()) append(" · ${state.bookAuthors}")
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CurrentMatch(
    current: HardcoverMatchedBook,
    isRemoving: Boolean,
    onRemove: () -> Unit,
) {
    Surface(
        shape = ContentShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                stringResource(Res.string.hardcover_match_current),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(current.title.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            byline(current.authors, current.releaseYear)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(Res.string.hardcover_match_remove_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ListenUpButton(
                text = stringResource(Res.string.hardcover_match_remove),
                onClick = onRemove,
                isLoading = isRemoving,
                filled = false,
                fillMaxWidth = false,
                danger = true,
            )
        }
    }
}

/**
 * One result. A strong one (by the book's author) sits on a tonal fill with a bold title; the rest are
 * drawn quieter, outlined on the page. Its format, year and ratings tell the real book from a summary.
 */
@Composable
private fun CandidateRow(
    row: HardcoverCandidateRow,
    strong: Boolean,
    shape: Shape,
    isLinking: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val a11y = stringResource(Res.string.hardcover_match_pick_a11y, row.title)
    Surface(
        onClick = {
            haptics.press()
            onPick()
        },
        enabled = enabled,
        shape = shape,
        color = if (strong) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        border =
            if (strong) {
                null
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest)
            },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = a11y },
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = Spacing.lg, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CandidateText(row = row, strong = strong, modifier = Modifier.weight(1f))
            if (isLinking) {
                ListenUpLoadingIndicatorSmall()
            } else {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A result's title, authors, and the tells: format, year and ratings. */
@Composable
private fun CandidateText(
    row: HardcoverCandidateRow,
    strong: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = row.title,
            style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
        )
        Text(
            text =
                row.authors.take(SHOWN_NAMES).joinToString(", ").ifBlank {
                    stringResource(Res.string.hardcover_match_unknown_author)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.hasAudiobookEdition) FormatTag(stringResource(Res.string.hardcover_match_audiobook))
            Text(
                text =
                    listOfNotNull(
                        row.releaseYear?.toString(),
                        ratingsLine(row.ratingsCount),
                    ).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (strong && (row.ratingsCount ?: 0) > 0) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The edition's format, as a small outlined tag. */
@Composable
private fun FormatTag(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** Nothing by the book's author: say so calmly, and offer the fuller search as one tap. */
@Composable
private fun WeakResultsHint(
    author: String,
    suggestions: List<String>,
    onSearchFor: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(GroupCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(Res.string.hardcover_match_weak_title, author),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(Res.string.hardcover_match_weak_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SuggestionChips(suggestions, onSearchFor)
        }
    }
}

@Composable
private fun NoResults(
    query: String,
    suggestions: List<String>,
    onSearchFor: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Box(
            modifier =
                Modifier
                    .size(
                        EmptyGlyphSize,
                    ).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            stringResource(Res.string.hardcover_match_no_results_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(Res.string.hardcover_match_no_results_detail, query),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        SuggestionChips(suggestions, onSearchFor, centered = true)
    }
}

/** Each suggestion as a pill that searches for it; its spoken label says so ("Search “Andy Weir”"). */
@Composable
private fun SuggestionChips(
    suggestions: List<String>,
    onSearchFor: (String) -> Unit,
    centered: Boolean = false,
) {
    if (suggestions.isEmpty()) return
    FlowRow(
        horizontalArrangement =
            Arrangement.spacedBy(
                Spacing.sm,
                if (centered) Alignment.CenterHorizontally else Alignment.Start,
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        suggestions.forEach { suggestion ->
            val spoken = stringResource(Res.string.hardcover_match_search_for, suggestion)
            PillChip(
                label = suggestion,
                onClick = { onSearchFor(suggestion) },
                leadingIcon = Icons.AutoMirrored.Outlined.ManageSearch,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        }
    }
}

/** Asking Hardcover: a wavy progress line over three placeholder rows shaped like results. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchingSkeleton() {
    val searching = stringResource(Res.string.hardcover_match_searching)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.padding(top = Spacing.sm)) {
        LinearWavyProgressIndicator(
            modifier =
                Modifier.fillMaxWidth().padding(horizontal = Spacing.sm).semantics {
                    contentDescription =
                        searching
                },
        )
        Column(verticalArrangement = Arrangement.spacedBy(ResultGap)) {
            repeat(SKELETON_ROWS) { index ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, groupShape(index, SKELETON_ROWS, 1))
                            .padding(horizontal = 20.dp, vertical = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    listOf(200.dp, 110.dp, 170.dp).forEach { width ->
                        Box(
                            modifier =
                                Modifier
                                    .width(width)
                                    .height(SkeletonLineHeight)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ratingsLine(count: Int?): String =
    when {
        count == null || count <= 0 -> stringResource(Res.string.hardcover_match_ratings_none)
        count == 1 -> stringResource(Res.string.hardcover_match_ratings_one)
        else -> stringResource(Res.string.hardcover_match_ratings, RatingLabels.compactCount(count))
    }

/** "Andy Weir, Ray Porter · 2021": the first two credited names (Hardcover lists narrators too), then the year. */
internal fun byline(
    authors: List<String>,
    releaseYear: Int?,
): String? =
    listOfNotNull(authors.take(SHOWN_NAMES).joinToString(", ").ifBlank { null }, releaseYear?.toString())
        .joinToString(" · ")
        .ifBlank { null }

@Composable
private fun CenteredNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.sectionGap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
