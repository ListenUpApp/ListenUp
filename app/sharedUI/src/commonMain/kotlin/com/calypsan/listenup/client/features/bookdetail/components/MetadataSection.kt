package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
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
import com.calypsan.listenup.client.core.DurationFormatter
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Tone variants for [StatChip]. */
enum class StatChipTone {
    /** Neutral surface — default for most chips. */
    Surface,

    /** Tertiary container — for the lead/accent chip. */
    Tertiary,
}

/**
 * Row of stat chips showing rating, duration, year, and date added.
 *
 * @param onHeroBand When true, the chips are recoloured to read on the wide hero's
 *   [MaterialTheme.colorScheme.primaryContainer] band — content in `onPrimaryContainer` tones, the
 *   accent "Added" chip a `primary` fill — instead of the default surface-background styling used by
 *   the compact layout's StatsRow.
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
    onHeroBand: Boolean = false,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Order: Added (accent) → Duration → Year, with Rating last when present.
        addedAt?.let { timestamp ->
            StatChip(
                icon = { Icon(Icons.Default.LibraryAdd, null, Modifier.size(16.dp)) },
                text = formatAddedDate(timestamp),
                tone = StatChipTone.Tertiary,
                onHeroBand = onHeroBand,
            )
        }

        StatChip(
            icon = { Icon(Icons.Default.AccessTime, null, Modifier.size(16.dp)) },
            text = DurationFormatter.hoursMinutesAlways(duration.milliseconds),
            onHeroBand = onHeroBand,
        )

        year?.takeIf { it > 0 }?.let { y ->
            StatChip(
                icon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp)) },
                text = y.toString(),
                onHeroBand = onHeroBand,
            )
        }

        rating?.takeIf { it > 0 }?.let { r ->
            StatChip(
                icon = { Icon(Icons.Default.Star, null, Modifier.size(16.dp)) },
                text = ((r * 10).toInt() / 10.0).toString(),
                onHeroBand = onHeroBand,
            )
        }
    }
}

/**
 * Individual stat chip with icon and text.
 *
 * @param onHeroBand When true, the chip is coloured for the wide hero's `primaryContainer` band: the
 *   accent ([StatChipTone.Tertiary]) chip becomes a solid `primary` fill (the lead chip), the neutral
 *   chips a faint `onPrimaryContainer` wash — both legible against the colour band. When false (the
 *   default, used by the compact layout) the chip keeps its surface-background styling.
 */
@Composable
fun StatChip(
    icon: @Composable () -> Unit,
    text: String,
    tone: StatChipTone = StatChipTone.Surface,
    onHeroBand: Boolean = false,
) {
    // On the colour band: the accent (Tertiary) chip leads with a solid primary fill; neutral chips
    // read as a faint onPrimaryContainer wash so they sit quietly on the band. Off the band (compact
    // layout) the chips keep their surface-background styling.
    val containerColor =
        when (tone) {
            StatChipTone.Surface -> {
                if (onHeroBand) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            }

            StatChipTone.Tertiary -> {
                if (onHeroBand) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                }
            }
        }
    val contentColor =
        when (tone) {
            StatChipTone.Surface -> {
                if (onHeroBand) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            }

            StatChipTone.Tertiary -> {
                if (onHeroBand) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                }
            }
        }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        contentColor = contentColor,
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
 * Formats epoch milliseconds to a short date string for "Date Added".
 * Uses "MMM yyyy" format (e.g., "Jan 2024").
 */
private fun formatAddedDate(epochMillis: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${monthNames[localDateTime.month.ordinal]} ${localDateTime.year}"
}
