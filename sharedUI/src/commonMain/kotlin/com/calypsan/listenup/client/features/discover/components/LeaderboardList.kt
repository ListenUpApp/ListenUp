package com.calypsan.listenup.client.features.discover.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.domain.repository.LeaderboardCategory
import com.calypsan.listenup.client.domain.repository.LeaderboardEntry

/** Number of entries to show when collapsed */
private const val COLLAPSED_COUNT = 4

/**
 * Leaderboard list showing ranked users with animated position changes.
 *
 * Shows top 4 entries by default with an expand/collapse option to see more.
 * Uses LazyColumn with animateItem() for smooth position animations when
 * users move up or down in the rankings.
 *
 * @param entries List of leaderboard entries
 * @param category Current leaderboard category (for value labels)
 * @param onUserClick Callback when a user row is clicked
 * @param modifier Modifier from parent
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    text = if (isExpanded) "Show less" else "Show all ${entries.size}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(
                    imageVector =
                        if (isExpanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
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
    val backgroundColor =
        if (entry.isCurrentUser) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                .background(backgroundColor)
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Animated rank badge - slides up/down when rank changes
        AnimatedContent(
            targetState = entry.rank,
            transitionSpec = {
                if (targetState < initialState) {
                    // Moving up - slide in from top
                    slideInVertically { -it } + fadeIn() togetherWith
                        slideOutVertically { it } + fadeOut()
                } else {
                    // Moving down - slide in from bottom
                    slideInVertically { it } + fadeIn() togetherWith
                        slideOutVertically { -it } + fadeOut()
                }
            },
            label = "rank",
        ) { rank ->
            RankBadge(rank = rank)
        }

        Spacer(Modifier.width(12.dp))

        // User info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (entry.isCurrentUser) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Animated value label - fades when value changes
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
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color =
                    if (entry.isCurrentUser) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
        }
    }
}

@Composable
private fun RankBadge(
    rank: Int,
    modifier: Modifier = Modifier,
) {
    val (backgroundColor, textColor, emoji) =
        when (rank) {
            1 -> {
                Triple(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                    "\uD83E\uDD47", // Gold medal
                )
            }

            2 -> {
                Triple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    "\uD83E\uDD48", // Silver medal
                )
            }

            3 -> {
                Triple(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurface,
                    "\uD83E\uDD49", // Bronze medal
                )
            }

            else -> {
                Triple(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    null,
                )
            }
        }

    if (emoji != null) {
        Text(
            text = emoji,
            fontSize = 20.sp,
            modifier = modifier.size(32.dp),
        )
    } else {
        Box(
            modifier =
                modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(backgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
        }
    }
}
