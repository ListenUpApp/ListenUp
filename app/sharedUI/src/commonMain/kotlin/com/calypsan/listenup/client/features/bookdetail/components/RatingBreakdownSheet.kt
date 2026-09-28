package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.util.ratingSourceLabel
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.domain.averageLabel
import com.calypsan.listenup.domain.compactCount
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_rating_refresh
import listenup.composeapp.generated.resources.book_detail_rating_source_row
import listenup.composeapp.generated.resources.book_detail_rating_sources_title
import org.jetbrains.compose.resources.stringResource

/**
 * The per-source outside-rating breakdown, one tap away from [BookRatingSection]'s headline: one
 * row per source in [breakdown] ("Audible · 4.5 · 8.1k"), plus a "Refresh ratings" action when
 * [canRefresh] (admin or root). [isRefreshingExternal] is the view model's own in-flight flag
 * (true from the moment the RPC is sent until the server answers, whether or not any score
 * changed) — the button's busy/disabled state is bound to it directly rather than guessed at
 * locally, so a refresh that finds nothing new still re-enables the button.
 *
 * @param breakdown Every enabled source's rating, highest rating count first.
 * @param canRefresh Whether to show the refresh action.
 * @param isRefreshingExternal Whether a refresh is currently in flight.
 * @param onRefresh Re-fetches every enabled source for this book now.
 * @param onDismiss Closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingBreakdownSheet(
    breakdown: List<ExternalRating>,
    canRefresh: Boolean,
    isRefreshingExternal: Boolean,
    onRefresh: () -> Unit,
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
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.book_detail_rating_sources_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                breakdown.forEach { rating ->
                    Text(
                        text =
                            stringResource(
                                Res.string.book_detail_rating_source_row,
                                ratingSourceLabel(rating.source),
                                "${averageLabel(rating.average)} · ${compactCount(rating.count)}",
                            ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (canRefresh) {
                ListenUpButton(
                    text = stringResource(Res.string.book_detail_rating_refresh),
                    onClick = onRefresh,
                    isLoading = isRefreshingExternal,
                    filled = false,
                    modifier = Modifier.testTag("refreshRatingsButton"),
                )
            }
        }
    }
}
