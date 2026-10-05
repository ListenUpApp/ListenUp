package com.calypsan.listenup.client.features.seriesdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.series_continue_book
import listenup.composeapp.generated.resources.series_continue_title
import listenup.composeapp.generated.resources.series_continue_where
import listenup.composeapp.generated.resources.series_start_book
import listenup.composeapp.generated.resources.series_start_title
import org.jetbrains.compose.resources.stringResource

/**
 * Brand "Continue" pill. Hidden when the whole series is finished.
 *
 * A flat series says "Continue Book 3" / "Start Book 1". A parent page names the book instead —
 * "Continue The Hero of Ages" over "Mistborn Era 1 · Book 3", or "Start The Final Empire" on a series
 * not yet begun — because "Book 3" is ambiguous when the books come from four series.
 */
@Composable
internal fun ContinueButton(
    state: SeriesDetailUiState.Ready,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val targetId = state.resumeTarget ?: return
    val target = state.books.firstOrNull { it.id == targetId } ?: return
    // The verb is the ViewModel's, shared with iOS and web: the series is under way once any book is
    // begun or finished, even when the next one has no progress of its own.
    val hasStarted = state.resumeBook?.hasStarted == true
    val resume = state.resumeBook?.takeIf { state.isGrouped }

    val title: String
    val where: String?
    if (resume != null) {
        title =
            stringResource(
                if (hasStarted) Res.string.series_continue_title else Res.string.series_start_title,
                resume.title,
            )
        where = resume.sequence?.let { stringResource(Res.string.series_continue_where, resume.seriesName, it) }
            ?: resume.seriesName
    } else {
        val index = state.books.indexOfFirst { it.id == targetId }
        val positionLabel = target.seriesSequenceLabel ?: (index + 1).toString()
        title =
            if (hasStarted) {
                stringResource(Res.string.series_continue_book, positionLabel)
            } else {
                stringResource(Res.string.series_start_book, positionLabel)
            }
        where = null
    }

    Row(
        modifier =
            modifier
                .heightIn(min = 58.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    haptics.press()
                    onBookClick(targetId.value)
                }.padding(horizontal = Spacing.screenMargin, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (where != null) {
                Text(
                    text = where,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
            }
        }
    }
}
