package com.calypsan.listenup.client.features.contributoredit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Immersive gradient backdrop using contributor's color scheme.
 */
@Composable
fun ContributorBackdrop(
    colorScheme: ContributorColorScheme,
    surfaceColor: Color,
) {
    val gradientColors =
        listOf(
            colorScheme.primaryDark,
            colorScheme.primaryMuted.copy(alpha = 0.7f),
            colorScheme.primaryMuted.copy(alpha = 0.3f),
            surfaceColor.copy(alpha = 0.95f),
            surfaceColor,
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(400.dp)
                .background(Brush.verticalGradient(gradientColors)),
    )
}
