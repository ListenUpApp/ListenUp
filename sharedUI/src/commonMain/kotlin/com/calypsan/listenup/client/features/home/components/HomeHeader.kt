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
 * Displays a time-aware greeting (e.g., "Good morning, Simon")
 * with balanced typography that doesn't dominate the screen.
 *
 * @param greeting The personalized greeting text
 * @param modifier Optional modifier
 */
@Composable
fun HomeHeader(
    greeting: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(
            text = greeting,
            style =
                MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
