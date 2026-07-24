package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_pause
import listenup.composeapp.generated.resources.player_play
import org.jetbrains.compose.resources.stringResource

/**
 * Squircle play/pause FAB with primary background; shows the wavy circular progress indicator while
 * buffering.
 *
 * @param shadowElevation Drop-shadow depth. Defaults to 8.dp for the full-screen player; pass 0.dp
 * inside a clipping container (the mini-player bars) where the shadow would be cut off by the card.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayPauseFab(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    size: Dp,
    shadowElevation: Dp = 8.dp,
) {
    val haptics = LocalHaptics.current
    Surface(
        onClick = {
            haptics.toggle(on = !isPlaying)
            onClick()
        },
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = shadowElevation,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isBuffering) {
                // Thinner than the default wavy stroke so it reads as a light indicator, not a chunky ring.
                val density = LocalDensity.current
                val wavyStroke =
                    remember(density) { with(density) { Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round) } }
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(size * 0.5f),
                    color = MaterialTheme.colorScheme.onPrimary,
                    trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.24f),
                    stroke = wavyStroke,
                    trackStroke = wavyStroke,
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription =
                        stringResource(
                            if (isPlaying) Res.string.player_pause else Res.string.player_play,
                        ),
                    modifier = Modifier.size(size * 0.54f),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * Round transparent icon-button.
 *
 * @param icon The icon vector to display.
 * @param contentDescription Accessibility label.
 * @param onClick Click callback.
 * @param size Touch-target diameter.
 * @param tint Icon colour.
 */
@Composable
fun Ctrl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp = 60.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(size * 0.5f),
            tint = tint,
        )
    }
}

/** Pill-shaped speed label button with [surfaceContainerHigh] background, 40dp tall. */
@Composable
fun SpeedPill(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier =
                Modifier
                    .height(40.dp)
                    .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
