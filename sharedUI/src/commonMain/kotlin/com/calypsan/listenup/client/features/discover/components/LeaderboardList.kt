package com.calypsan.listenup.client.features.discover.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.RankBadge
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_leaderboard_show_all
import listenup.composeapp.generated.resources.discover_leaderboard_show_less

/** Number of entries to show when collapsed */
private const val COLLAPSED_COUNT = 4

/**
 * Leaderboard list showing ranked users with animated position changes.
 *
 * Shows top 4 entries by default with an expand/collapse option to see more. Each row carries a
 * medal-toned [RankBadge] for the podium positions and a clean transparent row chrome.
 *
 * @param entries List of leaderboard entries to display.
 * @param category Current leaderboard category — determines which value to show.
 * @param onUserClick Callback when a user row is clicked.
 * @param modifier Modifier from parent.
 */
@Composable
fun LeaderboardList(
    entries: List<LeaderboardEntry>,
    category: LeaderboardCategory,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val hasMoreEntries = entries.size > COLLAPSED_COUNT
    val visibleEntries = if (isExpanded) entries else entries.take(COLLAPSED_COUNT)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Use regular Column instead of LazyColumn to avoid nested scrollable crash
        // when this is inside HorizontalPager inside LazyColumn (DiscoverScreen)
        visibleEntries.forEach { entry ->
            LeaderboardEntryRow(
                entry = entry,
                category = category,
                onClick = { onUserClick(entry.userId) },
            )
        }

        // Expand/Collapse button
        if (hasMoreEntries) {
            TextButton(
                onClick = { isExpanded = !isExpanded },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text =
                        if (isExpanded) {
                            stringResource(Res.string.discover_leaderboard_show_less)
                        } else {
                            stringResource(Res.string.discover_leaderboard_show_all, entries.size)
                        },
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(
                    imageVector =
                        if (isExpanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun LeaderboardEntryRow(
    entry: LeaderboardEntry,
    category: LeaderboardCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Animated rank badge — slides up/down when rank changes
        AnimatedContent(
            targetState = entry.rank,
            transitionSpec = {
                if (targetState < initialState) {
                    slideInVertically { -it } + fadeIn() togetherWith
                        slideOutVertically { it } + fadeOut()
                } else {
                    slideInVertically { it } + fadeIn() togetherWith
                        slideOutVertically { -it } + fadeOut()
                }
            },
            label = "rank",
        ) { rank ->
            RankBadge(rank = rank)
        }

        Spacer(Modifier.width(14.dp))

        UserAvatar(
            userId = entry.userId,
            size = AvatarSize.Small,
            fallbackName = entry.displayName,
        )
        Spacer(Modifier.width(12.dp))

        // User name
        Text(
            text = entry.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        // Animated value label — fades when value changes
        AnimatedContent(
            targetState = entry.labelFor(category),
            transitionSpec = {
                fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) togetherWith
                    fadeOut(animationSpec = spring(stiffness = Spring.StiffnessLow))
            },
            label = "value",
        ) { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Format a [LeaderboardEntry] value for the given [category].
 *
 * Lives here (in the UI layer) because formatting is a display concern, not a
 * domain concern. The domain entry carries raw numeric values; this extension
 * converts them to user-readable strings.
 */
private fun LeaderboardEntry.labelFor(category: LeaderboardCategory): String =
    when (category) {
        LeaderboardCategory.Time -> formatSeconds(totalSeconds)
        LeaderboardCategory.Books -> "$booksFinished books"
        LeaderboardCategory.Streak -> "$longestStreakDays days"
    }

private fun formatSeconds(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        hours == 0L -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}
