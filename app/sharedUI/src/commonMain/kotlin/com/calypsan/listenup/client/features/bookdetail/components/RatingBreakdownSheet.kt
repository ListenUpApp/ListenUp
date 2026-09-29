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
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.ratingSourceLabel
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ScoreSource
import com.calypsan.listenup.domain.averageLabel
import com.calypsan.listenup.domain.compactCount
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_rating_combined_from
import listenup.composeapp.generated.resources.book_detail_rating_refresh
import listenup.composeapp.generated.resources.book_detail_rating_source_row
import listenup.composeapp.generated.resources.book_detail_rating_source_row_share
import listenup.composeapp.generated.resources.book_detail_rating_sources_title
import listenup.composeapp.generated.resources.rating_source_listeners
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The ListenUp score's breakdown, one tap away from [BookRatingSection]'s headline: "Combined from
 * N sources" when [score] has more than one, one row per outside source in [breakdown] with its
 * share of the score ("Audible · 4.7 · 1k · 38%"), a "Your listeners" row when they are part of
 * it ("Your listeners · 4.0 · 3 · 20%"), plus a "Refresh ratings" action when
 * [canRefresh] (admin or root). [isRefreshingExternal] is the view model's own in-flight flag
 * (true from the moment the RPC is sent until the server answers, whether or not any score
 * changed) — the button's busy/disabled state is bound to it directly rather than guessed at
 * locally, so a refresh that finds nothing new still re-enables the button.
 *
 * @param breakdown Every enabled source's rating, highest rating count first.
 * @param canRefresh Whether to show the refresh action.
 * @param score The ListenUp score the rows add up to; null shows the rows without shares.
 * @param listeners Your listeners' average, shown as a row when [score] draws on it.
 * @param isRefreshingExternal Whether a refresh is currently in flight.
 * @param onRefresh Re-fetches every enabled source for this book now.
 * @param onDismiss Closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingBreakdownSheet(
    breakdown: List<ExternalRating>,
    canRefresh: Boolean,
    score: CombinedScore? = null,
    listeners: ListenerAverage? = null,
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
                    .padding(horizontal = Spacing.screenMargin)
                    .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = stringResource(Res.string.book_detail_rating_sources_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (score != null && score.sourceCount >= 2) {
                    Text(
                        text =
                            stringResource(
                                Res.string.book_detail_rating_combined_from,
                                score.sourceCount.toString(),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                breakdown.forEach { rating ->
                    SourceRow(
                        label = ratingSourceLabel(rating.source),
                        average = rating.average,
                        count = rating.count,
                        share = score?.shares?.get(ScoreSource.Outside(rating.source)),
                    )
                }
                val listenersShare = score?.shares?.get(ScoreSource.Listeners)
                if (listeners != null && listenersShare != null) {
                    SourceRow(
                        label = stringResource(Res.string.rating_source_listeners),
                        average = listeners.averageHalfStars / 2,
                        count = listeners.count,
                        share = listenersShare,
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

/**
 * One source's row: "Audible · 4.7 · 1k", and its share of the score as a whole percent when it
 * has one ("Audible · 4.7 · 1k · 38%"). The average is on the source's own curve, not ListenUp's.
 */
@Composable
private fun SourceRow(
    label: String,
    average: Double,
    count: Int,
    share: Double?,
) {
    Text(
        text =
            if (share == null) {
                stringResource(
                    Res.string.book_detail_rating_source_row,
                    label,
                    "${averageLabel(average)} · ${compactCount(count)}",
                )
            } else {
                stringResource(
                    Res.string.book_detail_rating_source_row_share,
                    label,
                    averageLabel(average),
                    compactCount(count),
                    (share * PERCENT).roundToInt().toString(),
                )
            },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private const val PERCENT = 100
