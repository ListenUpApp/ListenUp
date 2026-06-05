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
 * Header section for the Home screen.
 *
 * Displays a time-aware greeting (e.g., "Good morning, Simon") as an emphasized hero line — the
 * Home content's header, since the shell top bar already carries search and the account menu.
 * Scales up on wide windows to anchor the desktop layout.
 *
 * @param greeting The personalized greeting text
 * @param isWide Whether the current window is medium+ (drives the display size)
 * @param modifier Optional modifier
 */
@Composable
fun HomeHeader(
    greeting: String,
    isWide: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = greeting,
            style =
                (if (isWide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge)
                    .copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp,
                    ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
