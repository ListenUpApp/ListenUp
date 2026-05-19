@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.SessionSummary
import com.calypsan.listenup.client.util.toRelativeOrMonthYear
import kotlin.time.Duration.Companion.milliseconds

/**
 * Card displaying the user's own reading history with a book.
 *
 * Shows:
 * - Last read time (relative or "Month Year" format)
 * - Completion count if multiple completions
 * - Total listening time
 *
 * Uses primaryContainer color for visual distinction.
 */
@Composable
fun YourReadingHistory(
    sessions: List<SessionSummary>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            // Calculate stats from sessions
            val completionCount = sessions.count { it.isCompleted }
            val totalListenTimeMs = sessions.sumOf { it.listenTimeMs }
            val lastSession = sessions.maxByOrNull { it.startedAt }

            // Last read message
            val lastReadText =
                if (lastSession != null) {
                    val timeRef = lastSession.finishedAt ?: lastSession.startedAt
                    "You last read this ${timeRef.toRelativeOrMonthYear()}"
                } else {
                    "No reading history"
                }

            Text(
                text = lastReadText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            // Stats line: completions and total time
            val statsText =
                buildString {
                    if (completionCount > 1) {
                        append("Read $completionCount times")
                    } else if (completionCount == 1) {
                        append("Read once")
                    } else {
                        append("In progress")
                    }

                    // Format total listening time (with sanity check for corrupted data)
                    // Max reasonable value: 1000 hours = 3,600,000,000 ms
                    val maxReasonableMs = 3_600_000_000L
                    if (totalListenTimeMs in 1 until maxReasonableMs) {
                        append(" · ")
                        val totalHours = totalListenTimeMs.milliseconds.inWholeHours
                        if (totalHours > 0) {
                            append("${totalHours}h")
                        } else {
                            val totalMinutes = totalListenTimeMs.milliseconds.inWholeMinutes
                            append("${totalMinutes}m")
                        }
                        append(" total listening time")
                    }
                    // If listen time is 0 or corrupted (> 1000 hours), don't show it
                }

            Text(
                text = statsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
