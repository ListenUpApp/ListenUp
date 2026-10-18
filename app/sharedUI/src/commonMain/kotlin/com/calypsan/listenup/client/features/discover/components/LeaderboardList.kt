package com.calypsan.listenup.client.features.discover.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.RankBadge
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.presentation.discover.leaderboardLabel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_leaderboard_rank_a11y
import listenup.composeapp.generated.resources.discover_leaderboard_show_all
import listenup.composeapp.generated.resources.discover_leaderboard_show_less
import listenup.composeapp.generated.resources.discover_leaderboard_you

/** Number of entries to show when collapsed */
private const val COLLAPSED_COUNT = 5

/**
 * Leaderboard list showing ranked users with animated position changes.
 *
 * Shows the top 5 by default with "Show all N" to see the rest. Each row is one link to the person's
 * profile, read rank first ("Rank 1, Maya Chen, 14h"), with a medal-toned [RankBadge] for the podium;
 * your own row reads "You" on `primaryContainer`.
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
        // A plain Column: the list sits inside Discover's LazyColumn, where a nested lazy list cannot measure.
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
    val haptics = LocalHaptics.current
    val rankLabel = stringResource(Res.string.discover_leaderboard_rank_a11y, entry.rank)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(MaterialTheme.shapes.large)
                .background(if (entry.isYou) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .clickable(role = Role.Button) {
                    haptics.press()
                    onClick()
                }.semantics(mergeDescendants = true) {}
                .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Animated rank badge — slides up/down when rank changes. Read as "Rank N", first in the row.
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
            modifier = Modifier.clearAndSetSemantics { contentDescription = rankLabel },
            label = "rank",
        ) { rank ->
            RankBadge(rank = rank)
        }

        Spacer(Modifier.width(14.dp))

        UserAvatar(
            userId = entry.userId,
            size = AvatarSize.Small,
            fallbackName = entry.displayName,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(Modifier.width(12.dp))

        Text(
            text = if (entry.isYou) stringResource(Res.string.discover_leaderboard_you) else entry.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (entry.isYou) FontWeight.Bold else FontWeight.Medium,
            color = if (entry.isYou) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Animated value label — fades when value changes
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = leaderboardLabel(entry, category),
            transitionSpec = {
                fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
            },
            label = "value",
        ) { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (entry.isYou) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
