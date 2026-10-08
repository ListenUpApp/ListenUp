package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsEvent
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsViewModel
import com.calypsan.listenup.core.currentEpochMilliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_rating_heading
import listenup.composeapp.generated.resources.book_detail_rating_removed
import listenup.composeapp.generated.resources.book_detail_rating_undo
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** At this width of its own, the block splits into your half and everyone's, side by side. */
internal val RatingsSplitMinWidth = 560.dp

/**
 * The rating block on Book Detail, bound to [BookRatingsViewModel]: the [BookRatingSection], the
 * [RateBookSheet] for your note, the [RatingBreakdownSheet] the score's row opens, and "Rating removed"
 * with Undo after you remove your rating. Renders nothing until the ratings are read.
 *
 * @param bookId The book being rated; scopes the [BookRatingsViewModel].
 * @param modifier Optional modifier.
 * @param isCard When true, wraps the section in a `surfaceContainerLow` card (wide layouts).
 * @param viewModel The rating state and actions for [bookId].
 */
@Composable
fun BookRatingBlock(
    bookId: String,
    modifier: Modifier = Modifier,
    isCard: Boolean = false,
    viewModel: BookRatingsViewModel = koinViewModel(parameters = { parametersOf(bookId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var isSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isBreakdownOpen by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = LocalSnackbarHostState.current
    val removed = stringResource(Res.string.book_detail_rating_removed)
    val undo = stringResource(Res.string.book_detail_rating_undo)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                BookRatingsEvent.RatingRemoved -> {
                    val result =
                        snackbarHostState.showSnackbar(
                            message = removed,
                            actionLabel = undo,
                            duration = SnackbarDuration.Short,
                        )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoClear()
                }
            }
        }
    }

    BookRatingSection(
        state = state,
        onSetStars = viewModel::setStars,
        onEditNote = { isSheetOpen = true },
        onRemove = viewModel::clear,
        onOpenSources = { isBreakdownOpen = true },
        onRefreshExternal = viewModel::refreshExternal,
        isCard = isCard,
        modifier = modifier,
    )

    val ready = state as? BookRatingsUiState.Ready
    if (isSheetOpen && ready != null) {
        RateBookSheet(
            current = ready.mine,
            onSave = viewModel::rate,
            onClear = viewModel::clear,
            onDismiss = { isSheetOpen = false },
        )
    }

    if (isBreakdownOpen && ready != null) {
        val nowMs = remember { currentEpochMilliseconds() }
        RatingBreakdownSheet(
            breakdown = ready.breakdown,
            score = ready.external,
            listeners = ready.listeners,
            canRefresh = ready.canRefresh,
            isRefreshingExternal = ready.isRefreshingExternal,
            onRefresh = viewModel::refreshExternal,
            onDismiss = { isBreakdownOpen = false },
            nowMs = nowMs,
        )
    }
}

/**
 * Book Detail's ratings, yours first: a "Ratings" heading, then [RatingsYouCard] — your stars as the
 * control — then [RatingsEveryoneRows], the ListenUp score and your listeners in labelled rows. Below
 * [RatingsSplitMinWidth] the two stack; at it and above they sit side by side as two halves. Inside a
 * card ([isCard]) "You" sits flat rather than nesting a second card.
 * [BookRatingsUiState.Loading] renders nothing, so the section never flashes an invitation you have
 * already answered.
 *
 * @param state The rating state to show.
 * @param onSetStars Saves the stars you settled on — a tap, the end of a drag, a key or TalkBack step.
 * @param onEditNote Opens the rate sheet for your note.
 * @param onRemove Removes your rating.
 * @param modifier Optional modifier.
 * @param onOpenSources Opens the sources sheet; the score's row invokes it.
 * @param onRefreshExternal Re-fetches every enabled outside source now (admin).
 * @param isCard When true, wraps the section in a `surfaceContainerLow` card, like the Readers card.
 */
@Composable
fun BookRatingSection(
    state: BookRatingsUiState,
    onSetStars: (Int) -> Unit,
    onEditNote: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSources: () -> Unit = {},
    onRefreshExternal: () -> Unit = {},
    isCard: Boolean = false,
) {
    val ready = state as? BookRatingsUiState.Ready ?: return
    val innerPadding = if (isCard) Spacing.screenMargin else 0.dp

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(Res.string.book_detail_rating_heading),
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val you: @Composable (Modifier) -> Unit = { cardModifier ->
                    RatingsYouCard(
                        mine = ready.mine,
                        onSetStars = onSetStars,
                        onEditNote = onEditNote,
                        onRemove = onRemove,
                        modifier = cardModifier,
                        isContained = !isCard,
                    )
                }
                val everyone: @Composable (Modifier) -> Unit = { rowsModifier ->
                    RatingsEveryoneRows(
                        ready = ready,
                        onOpenSources = onOpenSources,
                        onRefreshExternal = onRefreshExternal,
                        modifier = rowsModifier,
                    )
                }
                if (maxWidth >= RatingsSplitMinWidth) {
                    // A-And-Tablet: two equal halves, a hairline between them.
                    Row(
                        modifier = Modifier.height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                        verticalAlignment = Alignment.Top,
                    ) {
                        you(Modifier.weight(1f))
                        VerticalDivider()
                        everyone(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        you(Modifier)
                        everyone(Modifier)
                    }
                }
            }
        }
    }

    if (isCard) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = ContentShapes.card,
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    } else {
        Box(modifier = modifier) { content() }
    }
}
