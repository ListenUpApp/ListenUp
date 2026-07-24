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
import com.calypsan.listenup.client.design.components.cookieScallopShape
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_best_value
import org.jetbrains.compose.resources.stringResource

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
        if (currentStreak == 0 && longestStreak > 0) {
            "Best: ${dayCount(longestStreak)} streak"
        } else {
            "${dayCount(currentStreak)} streak"
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
                    .clip(cookieScallopShape())
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
                maxLines = 1,
            )
            if (longestStreak > 0 && currentStreak > 0) {
                Text(
                    text = stringResource(Res.string.home_best_value, dayCount(longestStreak)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Formats a day count with correct singular/plural casing: "1 Day", "2 Days". */
private fun dayCount(days: Int): String = "$days " + if (days == 1) "Day" else "Days"
