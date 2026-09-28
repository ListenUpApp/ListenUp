package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.RatingStars
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsViewModel
import com.calypsan.listenup.domain.ListenerRatingLimits
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_rating_edit
import listenup.composeapp.generated.resources.book_detail_rating_listeners
import listenup.composeapp.generated.resources.book_detail_rating_listeners_a11y
import listenup.composeapp.generated.resources.book_detail_rating_listeners_a11y_one
import listenup.composeapp.generated.resources.book_detail_rating_rate
import listenup.composeapp.generated.resources.book_detail_rating_yours
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The rating block on Book Detail, bound to [BookRatingsViewModel]: the [BookRatingSection], and
 * the [RateBookSheet] that "Rate" and "Edit" open. Renders nothing until the ratings are read.
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

    BookRatingSection(
        state = state,
        onRate = { isSheetOpen = true },
        onEdit = { isSheetOpen = true },
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
}

/**
 * Stateless rating section: your listeners' average when anyone has rated the book, then either an
 * invitation to rate it or your own stars with a way to edit them. [BookRatingsUiState.Loading]
 * renders nothing, so the section never flashes an invitation you have already answered.
 *
 * @param state The rating state to show.
 * @param onRate Opens the rate sheet when you have not rated the book.
 * @param onEdit Opens the rate sheet on your existing rating.
 * @param modifier Optional modifier.
 * @param isCard When true, wraps the section in a `surfaceContainerLow` card, like the Readers card.
 */
@Composable
fun BookRatingSection(
    state: BookRatingsUiState,
    onRate: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    isCard: Boolean = false,
) {
    val ready = state as? BookRatingsUiState.Ready ?: return
    val haptics = LocalHaptics.current
    val innerPadding = if (isCard) Spacing.screenMargin else 0.dp

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ready.listeners?.let { listeners ->
                val stars = ListenerRatingLimits.starsLabel(listeners.averageHalfStars)
                val spoken =
                    if (listeners.count == 1) {
                        stringResource(Res.string.book_detail_rating_listeners_a11y_one, stars)
                    } else {
                        stringResource(Res.string.book_detail_rating_listeners_a11y, stars, listeners.count)
                    }
                Text(
                    text =
                        stringResource(
                            Res.string.book_detail_rating_listeners,
                            "$STAR_GLYPH $stars",
                            listeners.count,
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
                )
            }

            val mine = ready.mine
            if (mine == null) {
                ListenUpButton(
                    text = stringResource(Res.string.book_detail_rating_rate),
                    onClick = onRate,
                    filled = false,
                    fillMaxWidth = false,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.book_detail_rating_yours),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    RatingStars(halfStars = mine.halfStars)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            haptics.press()
                            onEdit()
                        },
                    ) {
                        Text(
                            text = stringResource(Res.string.book_detail_rating_edit),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
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

/** The star shown before your listeners' average; TalkBack reads a plain sentence instead. */
private const val STAR_GLYPH = "★"
