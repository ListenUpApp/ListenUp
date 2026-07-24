package com.calypsan.listenup.client.features.contributoredit.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * Rich color scheme derived from contributor's avatar hue.
 * More saturated than detail screen for editing atmosphere.
 */
data class ContributorColorScheme(
    val primary: Color,
    val primaryDark: Color,
    val primaryMuted: Color,
    val onPrimary: Color,
)

@Composable
fun rememberContributorColorScheme(contributorId: String): ContributorColorScheme =
    remember(contributorId) {
        val baseColor = Color.hsl((contributorId.hashCode() and 0x7FFFFFFF).rem(360).toFloat(), 0.4f, 0.65f)

        ContributorColorScheme(
            primary = baseColor,
            primaryDark =
                baseColor.copy(
                    red = baseColor.red * 0.6f,
                    green = baseColor.green * 0.6f,
                    blue = baseColor.blue * 0.6f,
                ),
            primaryMuted =
                baseColor.copy(
                    red = baseColor.red * 0.8f,
                    green = baseColor.green * 0.8f,
                    blue = baseColor.blue * 0.8f,
                    alpha = 0.7f,
                ),
            onPrimary = Color.White,
        )
    }
