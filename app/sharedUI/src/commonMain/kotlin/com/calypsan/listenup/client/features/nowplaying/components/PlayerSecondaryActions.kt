package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.settings.PlaybackSpeedPresets
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_chapters
import listenup.composeapp.generated.resources.player_sleep_timer
import org.jetbrains.compose.resources.stringResource

/**
 * Secondary action row with speed pill, sleep pill, and chapters pill.
 *
 * All three controls use a pill shape ([CircleShape]) with [surfaceContainerHigh] background,
 * matching the M3 Expressive design reference. The speed pill shows a text label;
 * the sleep and chapters controls are icon-only pills.
 *
 * Bookmark and equalizer controls are intentionally omitted per design decision.
 *
 * @param playbackSpeed Current playback speed, formatted as a label (e.g. "1.0×").
 * @param onSpeedClick Called when the speed pill is tapped.
 * @param onSleepClick Called when the sleep/bedtime pill is tapped.
 * @param onChaptersClick Called when the chapters list pill is tapped.
 */
@Composable
fun PlayerSecondaryActions(
    playbackSpeed: Float,
    onSpeedClick: () -> Unit,
    onSleepClick: () -> Unit,
    onChaptersClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedPill(
            label = PlaybackSpeedPresets.format(playbackSpeed),
            onClick = onSpeedClick,
        )

        PillIcon(
            icon = Icons.Default.Bedtime,
            contentDescription = stringResource(Res.string.player_sleep_timer),
            onClick = onSleepClick,
        )

        PillIcon(
            icon = Icons.AutoMirrored.Filled.List,
            contentDescription = stringResource(Res.string.player_chapters),
            onClick = onChaptersClick,
        )
    }
}

/**
 * Pill-shaped icon-only button with [surfaceContainerHigh] background, 40dp square.
 *
 * @param icon Icon vector to display.
 * @param contentDescription Accessibility label.
 * @param onClick Click callback.
 */
@Composable
fun PillIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
