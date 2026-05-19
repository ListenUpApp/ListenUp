package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_best_longeststreak

/**
 * Streak indicator showing current and optionally longest streak.
 *
 * Displays a fire emoji followed by the current streak count.
 * If the longest streak is greater than current, shows it in parentheses.
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
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fire emoji
        Text(
            text = "\uD83D\uDD25",
            fontSize = 20.sp,
        )

        Spacer(Modifier.width(8.dp))

        // Current streak text
        val streakText =
            when {
                currentStreak == 0 && longestStreak > 0 -> "Best: $longestStreak day streak"
                currentStreak == 1 -> "1 day streak"
                else -> "$currentStreak day streak"
            }
        Text(
            text = streakText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Show longest streak if greater than current and current > 0
        if (longestStreak > currentStreak && currentStreak > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(Res.string.home_best_longeststreak, longestStreak),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
