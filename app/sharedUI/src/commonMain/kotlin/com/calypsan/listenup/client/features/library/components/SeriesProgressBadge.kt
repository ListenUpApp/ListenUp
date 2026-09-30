package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.SeriesProgress
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.series_complete
import listenup.composeapp.generated.resources.series_not_started
import listenup.composeapp.generated.resources.series_x_of_y
import org.jetbrains.compose.resources.stringResource

/**
 * How far through a series the listener is: a "Complete" pill, a quiet "Not started", or an
 * "X of Y" bar — the same three states as iOS's `SeriesProgressBadge`, in M3 Expressive tones.
 *
 * @param progress Finished and total book counts for the series.
 * @param modifier Modifier for the badge.
 */
@Composable
fun SeriesProgressBadge(
    progress: SeriesProgress,
    modifier: Modifier = Modifier,
) {
    when {
        progress.isComplete -> {
            CompletePill(modifier)
        }

        progress.isNotStarted -> {
            Text(
                text = stringResource(Res.string.series_not_started),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = modifier,
            )
        }

        else -> {
            PartialBar(progress, modifier)
        }
    }
}

@Composable
private fun CompletePill(modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 24.dp).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(Res.string.series_complete),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PartialBar(
    progress: SeriesProgress,
    modifier: Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = 230.dp)
                    .height(6.dp)
                    .clip(CircleShape),
        )
        Text(
            text = stringResource(Res.string.series_x_of_y, progress.finishedCount, progress.totalCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
