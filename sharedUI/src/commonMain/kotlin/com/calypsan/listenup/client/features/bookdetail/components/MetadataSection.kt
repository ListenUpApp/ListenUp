package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.GenreChipRow
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Context metadata section showing series, stats, and genres.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextMetadataSection(
    seriesId: String?,
    seriesName: String?,
    rating: Double?,
    duration: Long,
    year: Int?,
    addedAt: Long?,
    genres: List<String>,
    onSeriesClick: (seriesId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Series Badge
        seriesId?.let { id ->
            seriesName?.let { name ->
                SeriesBadge(
                    seriesName = name,
                    onClick = { onSeriesClick(id) },
                )
            }
        }

        // Stats Row
        StatsRow(
            rating = rating,
            duration = duration,
            year = year,
            addedAt = addedAt,
        )

        // Genres
        if (genres.isNotEmpty()) {
            GenreChipRow(
                genres = genres,
                onGenreClick = null,
            )
        }
    }
}

/**
 * Clickable series badge.
 */
@Composable
fun SeriesBadge(
    seriesName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Text(
            text = seriesName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * Row of stat chips showing rating, duration, year, and date added.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsRow(
    rating: Double?,
    duration: Long,
    year: Int?,
    addedAt: Long? = null,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rating?.takeIf { it > 0 }?.let { r ->
            StatChip(
                icon = { Icon(Icons.Default.Star, null, Modifier.size(16.dp)) },
                text = ((r * 10).toInt() / 10.0).toString(),
            )
        }

        StatChip(
            icon = { Icon(Icons.Default.AccessTime, null, Modifier.size(16.dp)) },
            text = formatDuration(duration),
        )

        year?.takeIf { it > 0 }?.let { y ->
            StatChip(
                icon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp)) },
                text = y.toString(),
            )
        }

        addedAt?.let { timestamp ->
            StatChip(
                icon = { Icon(Icons.Default.LibraryAdd, null, Modifier.size(16.dp)) },
                text = formatAddedDate(timestamp),
            )
        }
    }
}

/**
 * Individual stat chip with icon and text.
 */
@Composable
fun StatChip(
    icon: @Composable () -> Unit,
    text: String,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon()
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Formats duration in milliseconds to human-readable string.
 */
fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    return "${hours}h ${minutes}m"
}

/**
 * Formats epoch milliseconds to a short date string for "Date Added".
 * Uses "MMM yyyy" format (e.g., "Jan 2024").
 */
private fun formatAddedDate(epochMillis: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${monthNames[localDateTime.month.ordinal]} ${localDateTime.year}"
}
