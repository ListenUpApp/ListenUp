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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ProfileAvatar
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_activity_feed
import listenup.composeapp.generated.resources.discover_no_activity_yet_start_listening
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Activity Feed section for the Discover screen.
 *
 * Displays recent community activities:
 * - Started/finished books
 * - Streak milestones
 * - Listening hour milestones
 * - Created shelves
 *
 * Offline-first: All data comes from Room, synced via SSE events.
 *
 * @param onBookClick Callback when a book is clicked
 * @param onShelfClick Callback when a shelf is clicked
 * @param modifier Modifier from parent
 * @param viewModel ActivityFeedViewModel injected via Koin
 */
@Composable
fun ActivityFeedSection(
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
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
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Text(
                text = stringResource(Res.string.discover_activity_feed),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
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
                        // Show activities (limited to 5 in the section)
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            s.activities.take(5).forEach { activity ->
                                ActivityItem(
                                    activity = activity,
                                    onBookClick = onBookClick,
                                    onShelfClick = onShelfClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single activity item in the feed.
 */
@Composable
private fun ActivityItem(
    activity: ActivityUiModel,
    onBookClick: (String) -> Unit,
    onShelfClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, description) =
        remember(activity) {
            getActivityIconAndDescription(activity)
        }

    val bookId = activity.bookId
    val shelfId = activity.shelfId
    val isClickable = bookId != null || shelfId != null

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
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
                ).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // User avatar - uses ProfileAvatar for offline-first avatar display
        ProfileAvatar(
            userId = activity.userId,
            displayName = activity.userDisplayName,
            avatarColor = activity.userAvatarColor,
            avatarType = activity.userAvatarType,
            avatarValue = activity.userAvatarValue,
            size = 36.dp,
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Activity content
        Column(
            modifier = Modifier.weight(1f),
        ) {
            // User name
            Text(
                text = activity.userDisplayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Activity description
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Activity icon
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
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
 * Get the icon and description for an activity.
 */
private fun getActivityIconAndDescription(activity: ActivityUiModel): Pair<ImageVector, String> =
    when (activity.type) {
        "started_book" -> {
            val prefix = if (activity.isReread) "Started re-reading" else "Started reading"
            val bookInfo = activity.bookTitle ?: "a book"
            val authorInfo = formatActivityAuthor(activity.bookAuthorName)?.let { " by $it" } ?: ""
            Icons.AutoMirrored.Filled.MenuBook to "$prefix $bookInfo$authorInfo"
        }

        "finished_book" -> {
            val bookInfo = activity.bookTitle ?: "a book"
            val authorInfo = formatActivityAuthor(activity.bookAuthorName)?.let { " by $it" } ?: ""
            Icons.Default.AutoStories to "Finished $bookInfo$authorInfo"
        }

        "listening_session" -> {
            val bookInfo = activity.bookTitle ?: "a book"
            val durationText = formatDurationMinutes(activity.durationMs)
            Icons.Default.Headphones to "Listened to $durationText of $bookInfo"
        }

        "streak_milestone" -> {
            val days = activity.milestoneValue
            Icons.Default.LocalFireDepartment to "Reached a $days day listening streak!"
        }

        "listening_milestone" -> {
            val hours = activity.milestoneValue
            Icons.Default.Headphones to "Listened for $hours hours total!"
        }

        "shelf_created" -> {
            val shelfName = activity.shelfName ?: "a shelf"
            Icons.Default.FilterList to "Created shelf \"$shelfName\""
        }

        "user_joined" -> {
            Icons.Default.PersonAdd to "Joined the server"
        }

        else -> {
            Icons.Default.Celebration to "Did something awesome!"
        }
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
