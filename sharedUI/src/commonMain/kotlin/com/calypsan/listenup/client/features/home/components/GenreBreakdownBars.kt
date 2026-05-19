package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.repository.GenreListening
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_top_genres

/**
 * Genre breakdown showing top genres with progress bars.
 *
 * Displays up to 3 genres with their percentage as a horizontal bar.
 * Each bar is scaled relative to the maximum percentage in the set.
 *
 * @param genres List of genres with listening data (pre-sorted, max 3)
 * @param modifier Modifier from parent
 */
@Composable
fun GenreBreakdownBars(
    genres: List<GenreListening>,
    modifier: Modifier = Modifier,
) {
    val maxPercentage = genres.maxOfOrNull { it.percentage } ?: 100.0

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.home_top_genres),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        genres.forEach { genre ->
            GenreBar(
                genreName = genre.genreName,
                percentage = genre.percentage,
                maxPercentage = maxPercentage,
            )
        }
    }
}

/**
 * Single genre progress bar.
 */
@Composable
private fun GenreBar(
    genreName: String,
    percentage: Double,
    maxPercentage: Double,
    modifier: Modifier = Modifier,
) {
    val fraction = (percentage / maxPercentage).coerceIn(0.0, 1.0).toFloat()

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Genre name
        Text(
            text = genreName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(80.dp),
        )

        Spacer(Modifier.width(8.dp))

        // Progress bar
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
            )
        }

        Spacer(Modifier.width(8.dp))

        // Percentage
        Text(
            text = "${percentage.toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
        )
    }
}
