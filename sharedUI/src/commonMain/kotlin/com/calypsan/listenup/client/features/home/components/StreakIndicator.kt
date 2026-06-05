package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.contributorAvatarShape

/**
 * Streak indicator \u2014 a scallop badge with a flame, the current streak, and the longest as a subtitle.
 *
 * @param currentStreak Current listening streak in days
 * @param longestStreak Longest ever streak in days
 * @param modifier Modifier from parent
 */
@Composable
fun StreakIndicator(
    currentStreak: Int,
    longestStreak: Int,
    modifier: Modifier = Modifier,
) {
    val title =
        when {
            currentStreak == 0 && longestStreak > 0 -> "Best: $longestStreak-day streak"
            currentStreak == 1 -> "1-day streak"
            else -> "$currentStreak-day streak"
        }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(contributorAvatarShape())
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.LocalFireDepartment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(26.dp),
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (longestStreak > 0 && currentStreak > 0) {
                Text(
                    text = "Best: $longestStreak days",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
