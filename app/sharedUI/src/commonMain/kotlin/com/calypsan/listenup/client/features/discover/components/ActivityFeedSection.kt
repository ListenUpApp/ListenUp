@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import kotlin.time.Clock
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_activity_feed
import listenup.composeapp.generated.resources.discover_no_activity_yet_start_listening
import listenup.composeapp.generated.resources.discover_time_ago_days
import listenup.composeapp.generated.resources.discover_time_ago_hours
import listenup.composeapp.generated.resources.discover_time_ago_minutes
import listenup.composeapp.generated.resources.discover_time_ago_now
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Cap the section to the most recent few activities. */
private const val ACTIVITY_LIMIT = 5

/**
 * Activity Feed section for the Discover screen.
 *
 * Displays recent community activities:
 * - Started/finished books
 * - Streak milestones
 * - Listening hour milestones
 * - Created shelves
 *
 * Offline-first: All data comes from Room, synced via firehose events.
 *
 * @param onBookClick Callback when a book is clicked
 * @param onShelfClick Callback when a shelf is clicked
 * @param onUserClick Callback when a user's avatar is clicked (navigates to their profile)
 * @param modifier Modifier from parent
 * @param viewModel ActivityFeedViewModel injected via Koin
 */
@Composable
fun ActivityFeedSection(
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityFeedViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header
            Text(
                text = stringResource(Res.string.discover_activity_feed),
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )

            when (val s = state) {
                is ActivityFeedUiState.Loading -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ListenUpLoadingIndicatorSmall()
                    }
                }

                is ActivityFeedUiState.Error -> {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is ActivityFeedUiState.Ready -> {
                    if (s.isEmpty) {
                        Text(
                            text = stringResource(Res.string.discover_no_activity_yet_start_listening),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        s.activities.take(ACTIVITY_LIMIT).forEach { activity ->
                            ActivityItem(
                                activity = activity,
                                onBookClick = onBookClick,
                                onShelfClick = onShelfClick,
                                onUserClick = onUserClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single activity row: avatar + a one-line sentence ("**who** action **book**") with the actor and
 * the book/shelf name emphasized (the latter in the brand colour) + a relative timestamp beneath.
 */
@Composable
private fun ActivityItem(
    activity: ActivityUiModel,
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parts = remember(activity) { activityParts(activity) }

    val actorColor = MaterialTheme.colorScheme.onSurface
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val highlightColor = MaterialTheme.colorScheme.primary
    val line =
        remember(parts, actorColor, bodyColor, highlightColor) {
            activityLine(
                actor = activity.userDisplayName,
                parts = parts,
                actorColor = actorColor,
                bodyColor = bodyColor,
                highlightColor = highlightColor,
            )
        }

    val bookId = activity.bookId
    val shelfId = activity.shelfId
    val isClickable = bookId != null || shelfId != null

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (isClickable) {
                        Modifier.clickable {
                            when {
                                bookId != null -> onBookClick(bookId)
                                shelfId != null -> onShelfClick(shelfId)
                            }
                        }
                    } else {
                        Modifier
                    },
                ).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(
            userId = activity.userId,
            size = AvatarSize.Medium,
            onClick = { onUserClick(activity.userId) },
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relativeTime(activity.occurredAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Structured pieces of an activity sentence: the [predicate] (e.g. "finished") and an optional
 * [highlight] (the book or shelf name) rendered in the brand colour, plus an optional [suffix]
 * (e.g. " by Author") shown as plain trailing text.
 */
private data class ActivityParts(
    val predicate: String,
    val highlight: String?,
    val suffix: String,
)

private const val A_BOOK = "a book"

private fun activityParts(activity: ActivityUiModel): ActivityParts {
    val authorSuffix = formatActivityAuthor(activity.bookAuthorName)?.let { " by $it" }.orEmpty()
    return when (activity.type) {
        "started_book" -> {
            val predicate = if (activity.isReread) "started re-reading" else "started reading"
            ActivityParts(predicate, activity.bookTitle ?: A_BOOK, authorSuffix)
        }

        "finished_book" -> {
            ActivityParts("finished", activity.bookTitle ?: A_BOOK, authorSuffix)
        }

        "listening_session" -> {
            val durationText = formatDurationMinutes(activity.durationMs)
            ActivityParts("listened to $durationText of", activity.bookTitle ?: A_BOOK, "")
        }

        "streak_milestone" -> {
            ActivityParts("reached a ${activity.milestoneValue}-day listening streak", null, "")
        }

        "listening_milestone" -> {
            ActivityParts("listened for ${activity.milestoneValue} hours total", null, "")
        }

        "shelf_created" -> {
            ActivityParts("created the shelf", activity.shelfName ?: "a shelf", "")
        }

        "user_joined" -> {
            ActivityParts("joined the server", null, "")
        }

        else -> {
            ActivityParts("did something awesome", null, "")
        }
    }
}

/** Build the activity sentence: bold actor, plain predicate, brand-coloured highlight, plain suffix. */
private fun activityLine(
    actor: String,
    parts: ActivityParts,
    actorColor: androidx.compose.ui.graphics.Color,
    bodyColor: androidx.compose.ui.graphics.Color,
    highlightColor: androidx.compose.ui.graphics.Color,
): AnnotatedString =
    buildAnnotatedString {
        withStyle(SpanStyle(color = actorColor, fontWeight = FontWeight.Bold)) {
            append(actor)
        }
        withStyle(SpanStyle(color = bodyColor)) {
            append(" ${parts.predicate}")
        }
        parts.highlight?.let { highlight ->
            append(" ")
            withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                append(highlight)
            }
        }
        if (parts.suffix.isNotEmpty()) {
            withStyle(SpanStyle(color = bodyColor)) {
                append(parts.suffix)
            }
        }
    }

/**
 * Format author name for activity feed display.
 * Shows "FirstAuthor et al." when there are multiple authors.
 */
private fun formatActivityAuthor(authorName: String?): String? {
    if (authorName.isNullOrBlank()) return null

    // Check for multiple authors (comma-separated)
    val authors = authorName.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    return if (authors.size <= 1) authorName else "${authors.first()} et al."
}

/**
 * Format duration in milliseconds to a human-readable string.
 * Examples: "30 seconds", "5 minutes", "1 hour", "1 hour 30 minutes"
 */
private fun formatDurationMinutes(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000).toInt()
    val totalMinutes = totalSeconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return when {
        totalMinutes == 0 -> "$totalSeconds second${if (totalSeconds != 1) "s" else ""}"
        hours == 0 -> "$minutes minute${if (minutes != 1) "s" else ""}"
        minutes == 0 -> "$hours hour${if (hours != 1) "s" else ""}"
        else -> "$hours hour${if (hours != 1) "s" else ""} $minutes minute${if (minutes != 1) "s" else ""}"
    }
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L

/** Compact relative timestamp ("Just now", "5m ago", "3h ago", "2d ago") from an epoch-ms instant. */
@Composable
private fun relativeTime(occurredAtMs: Long): String {
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val minutes = (nowMs - occurredAtMs).coerceAtLeast(0L) / MS_PER_MINUTE
    return when {
        minutes < 1L -> {
            stringResource(Res.string.discover_time_ago_now)
        }

        minutes < MINUTES_PER_HOUR -> {
            stringResource(Res.string.discover_time_ago_minutes, minutes.toInt())
        }

        minutes < MINUTES_PER_HOUR * HOURS_PER_DAY -> {
            stringResource(Res.string.discover_time_ago_hours, (minutes / MINUTES_PER_HOUR).toInt())
        }

        else -> {
            stringResource(
                Res.string.discover_time_ago_days,
                (minutes / (MINUTES_PER_HOUR * HOURS_PER_DAY)).toInt(),
            )
        }
    }
}
