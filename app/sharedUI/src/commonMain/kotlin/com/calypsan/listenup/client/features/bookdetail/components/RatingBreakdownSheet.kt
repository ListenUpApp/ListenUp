package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.ratingSourceLabel
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.RatingLabels
import com.calypsan.listenup.client.domain.model.ScoreSource
import com.calypsan.listenup.domain.averageLabel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_rating_combined_from
import listenup.composeapp.generated.resources.book_detail_rating_refresh
import listenup.composeapp.generated.resources.book_detail_rating_score
import listenup.composeapp.generated.resources.book_detail_rating_score_detail
import listenup.composeapp.generated.resources.book_detail_rating_share
import listenup.composeapp.generated.resources.book_detail_rating_sources_title
import listenup.composeapp.generated.resources.book_detail_rating_updated_days
import listenup.composeapp.generated.resources.book_detail_rating_updated_today
import listenup.composeapp.generated.resources.book_detail_rating_updated_yesterday
import listenup.composeapp.generated.resources.rating_source_listeners
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val PERCENT = 100

/**
 * The ListenUp score's sources, one tap away from its row: the score itself ("★ 4.6 · ListenUp score ·
 * 12k ratings"), "Combined from N sources" when there are several, then one row per source — its own
 * average, its count, its share of the score as a bar, and, for an outside catalog, when it was last
 * updated. An admin ([canRefresh]) refreshes from a quiet action at the foot; [isRefreshingExternal] is
 * the view model's own in-flight flag, so a refresh that finds nothing new still re-enables it.
 *
 * @param breakdown Every enabled source's rating, highest rating count first.
 * @param canRefresh Whether to show the refresh action.
 * @param score The ListenUp score the rows add up to; null shows the rows without shares.
 * @param listeners Your listeners' average, shown as a row when [score] draws on it.
 * @param isRefreshingExternal Whether a refresh is currently in flight.
 * @param onRefresh Re-fetches every enabled source for this book now.
 * @param onDismiss Closes the sheet.
 * @param nowMs The time "Updated 3 days ago" counts from; read once when the sheet opens.
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
    nowMs: Long,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Several sources, each with a bar and a freshness line, outgrow a short phone in
                    // landscape; the sheet scrolls rather than clipping the refresh off its foot.
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.screenMargin)
                    .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
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
            score?.let { ScoreHead(it) }
            HorizontalDivider()
            breakdown.forEach { rating ->
                SourceRow(
                    label = ratingSourceLabel(rating.source),
                    average = rating.average,
                    count = rating.count,
                    share = score?.shares?.get(ScoreSource.Outside(rating.source)),
                    updated = updatedLabel(rating.fetchedAtMs, nowMs),
                )
            }
            val listenersShare = score?.shares?.get(ScoreSource.Listeners)
            if (listeners != null && listenersShare != null) {
                SourceRow(
                    label = stringResource(Res.string.rating_source_listeners),
                    average = listeners.averageHalfStars / 2,
                    count = listeners.count,
                    share = listenersShare,
                    updated = null,
                )
            }
            if (canRefresh) {
                TextButton(
                    onClick = onRefresh,
                    enabled = !isRefreshingExternal,
                    modifier = Modifier.testTag("refreshRatingsButton"),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(Res.string.book_detail_rating_refresh))
                }
            }
        }
    }
}

@Composable
private fun ScoreHead(score: CombinedScore) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Bottom) {
        Text(
            text = "★ ${averageLabel(score.average)}",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text =
                stringResource(
                    Res.string.book_detail_rating_score_detail,
                    stringResource(Res.string.book_detail_rating_score),
                    ratingCountLabel(score.count),
                ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

/**
 * One source: "Audible ★ 4.8", then "11k ratings" and its share ("62%") with a bar at that share, then
 * when it was last updated. The average is on the source's own curve, not ListenUp's.
 */
@Composable
private fun SourceRow(
    label: String,
    average: Double,
    count: Int,
    share: Double?,
    updated: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = MaterialTheme.typography.titleSmall)
            Text(text = "★ ${averageLabel(average)}", style = MaterialTheme.typography.titleSmall)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = ratingCountLabel(count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            share?.let {
                Text(
                    text = stringResource(Res.string.book_detail_rating_share, (it * PERCENT).roundToInt().toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        share?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
        updated?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Updated today", "Updated yesterday", "Updated 3 days ago"; null when the server never said. */
@Composable
private fun updatedLabel(
    fetchedAtMs: Long?,
    nowMs: Long,
): String? {
    fetchedAtMs ?: return null
    return when (val days = RatingLabels.daysSince(fetchedAtMs, nowMs)) {
        0 -> stringResource(Res.string.book_detail_rating_updated_today)
        1 -> stringResource(Res.string.book_detail_rating_updated_yesterday)
        else -> stringResource(Res.string.book_detail_rating_updated_days, days)
    }
}
