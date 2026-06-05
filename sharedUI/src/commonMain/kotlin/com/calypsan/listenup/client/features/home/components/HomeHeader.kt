package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Header for the Home screen — a muted time-of-day line over the user's name as an emphasized hero,
 * matching the design's two-line greeting. Scales up on wide windows to anchor the desktop layout.
 *
 * @param timeGreeting Time-of-day line, e.g. "Good evening".
 * @param userName The user's first name; when blank, only the greeting line shows.
 * @param isWide Whether the current window is medium+ (drives the display size).
 * @param modifier Optional modifier.
 */
@Composable
fun HomeHeader(
    timeGreeting: String,
    userName: String,
    isWide: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 0.dp, bottom = 8.dp),
    ) {
        Text(
            text = if (userName.isNotBlank()) "$timeGreeting," else timeGreeting,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (userName.isNotBlank()) {
            Text(
                text = userName,
                style =
                    (if (isWide) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall)
                        .copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1.5).sp,
                        ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
