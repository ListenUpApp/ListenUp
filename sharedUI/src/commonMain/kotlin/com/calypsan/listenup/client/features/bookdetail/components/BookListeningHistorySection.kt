package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.history.BookListeningHistory
import com.calypsan.listenup.client.domain.history.DayBucket
import com.calypsan.listenup.client.domain.history.EventEntry
import com.calypsan.listenup.client.presentation.bookdetail.BookListeningHistoryUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookListeningHistoryViewModel
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Per-book listening-history section on the BookDetail screen — collapsible by default.
 *
 * Renders a day-grouped timeline of the user's listening spans for this book: a date
 * header with the day's total, followed by newest-first event rows showing the time
 * range, optional speed callout, and optional device label.
 *
 * The section is always rendered (even in Loading state) so the header is visible and
 * the user can choose to expand or collapse without waiting on data.
 *
 * @param bookId The book whose listening history to display.
 * @param modifier Optional modifier applied to the outer column.
 * @param isCard When true, wraps the section in a [Surface] with [MaterialTheme.colorScheme.surfaceContainerLow]
 *   background and [ContentShapes.card] shape — used by the wide layout. When false (the default),
 *   renders frameless, preserving the compact layout's appearance.
 * @param viewModel The ViewModel scoped to [bookId]; resolved via Koin by default.
 */
@Composable
fun BookListeningHistorySection(
    bookId: String,
    modifier: Modifier = Modifier,
    isCard: Boolean = false,
    viewModel: BookListeningHistoryViewModel = koinViewModel(parameters = { parametersOf(bookId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }

    val horizontalPadding = if (isCard) Spacing.screenMargin else 16.dp

    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp)) {
            SectionHeader(expanded = expanded, onToggle = { expanded = !expanded })
            if (expanded) {
                when (val s = state) {
                    BookListeningHistoryUiState.Loading -> LoadingPlaceholder()
                    BookListeningHistoryUiState.Empty -> EmptyMessage()
                    is BookListeningHistoryUiState.Data -> DayBuckets(s.history)
                    is BookListeningHistoryUiState.Error -> ErrorMessage(isRetryable = s.isRetryable)
                }
            }
        }
    }

    if (isCard) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = ContentShapes.card,
            modifier = modifier,
            content = content,
        )
    } else {
        Box(modifier = modifier) {
            content()
        }
    }
}

@Composable
private fun SectionHeader(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Your listening history",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }
    }
}

@Composable
private fun LoadingPlaceholder() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun EmptyMessage() {
    Text(
        text = "No listening history yet — start the book to see your sessions here.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

@Composable
private fun ErrorMessage(isRetryable: Boolean) {
    Text(
        text = if (isRetryable) "Couldn't load history. Try again later." else "Couldn't load history.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

@Composable
private fun DayBuckets(history: BookListeningHistory) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        history.daily.forEach { bucket ->
            DayBucketCard(bucket)
        }
    }
}

@Composable
private fun DayBucketCard(bucket: DayBucket) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = bucket.relativeLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = formatListeningDuration(bucket.totalSeconds),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            bucket.events.forEach { event -> EventRow(event) }
        }
    }
}

@Composable
private fun EventRow(event: EventEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTimeRange(event.startedAt, event.endedAt),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (event.playbackSpeed != 1.0f) {
            Text(
                text = "${event.playbackSpeed}x",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        event.deviceLabel?.let { label ->
            val truncated = if (label.length > 12) label.take(12) + "…" else label
            Text(
                text = truncated,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatListeningDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "<1m"
    }
}

private fun formatTimeRange(
    startedAtMs: Long,
    endedAtMs: Long,
): String {
    val tz = TimeZone.currentSystemDefault()
    val start = Instant.fromEpochMilliseconds(startedAtMs).toLocalDateTime(tz).time
    val end = Instant.fromEpochMilliseconds(endedAtMs).toLocalDateTime(tz).time
    return "${formatLocalTime(start)} → ${formatLocalTime(end)}"
}

private fun formatLocalTime(time: LocalTime): String {
    val hour12 = ((time.hour + 11) % 12) + 1
    val minute = time.minute.toString().padStart(2, '0')
    val amPm = if (time.hour < 12) "AM" else "PM"
    return "$hour12:$minute $amPm"
}
