package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.GenreShare
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_percent
import org.jetbrains.compose.resources.stringResource

/**
 * Genre breakdown showing top genres with progress bars.
 *
 * Displays up to 3 [GenreShare] entries with a horizontal bar scaled relative
 * to the genre with the most listening seconds.
 *
 * @param genres List of genres with listening seconds (pre-sorted, max 3)
 * @param modifier Modifier from parent
 */
@Composable
fun GenreBreakdownBars(
    genres: List<GenreShare>,
    modifier: Modifier = Modifier,
) {
    // Share of total listening across the shown genres — matches the design's summing percentages.
    val totalSeconds = genres.sumOf { it.totalSeconds }.toDouble().coerceAtLeast(1.0)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        genres.forEach { genre ->
            val share = (genre.totalSeconds.toDouble() / totalSeconds).coerceIn(0.0, 1.0)
            GenreBar(
                genreName = genre.genreName,
                fraction = share.toFloat(),
                percent = (share * 100).toInt(),
            )
        }
    }
}

/**
 * Single genre row: name + share bar + percentage.
 */
@Composable
private fun GenreBar(
    genreName: String,
    fraction: Float,
    percent: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = genreName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(120.dp),
        )

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(10.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(MaterialTheme.colorScheme.primary),
            )
        }

        Text(
            text = stringResource(Res.string.common_percent, percent),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End,
        )
    }
}
