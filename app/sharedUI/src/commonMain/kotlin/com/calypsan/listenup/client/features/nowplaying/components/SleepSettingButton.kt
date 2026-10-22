package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.features.nowplaying.MiniPlayerGlyphSize
import com.calypsan.listenup.client.playback.SleepTimerState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_mini_sleep_left
import listenup.composeapp.generated.resources.player_sleep_timer
import org.jetbrains.compose.resources.stringResource

/**
 * Sleep: a moon; armed, the countdown on `tertiaryContainer` (§3.9, §5.1 M16, §5.2). Shared by Now Playing's settings
 * row, where the idle moon is a tonal button ([tonalWhenIdle]), and the wide toolbar, where it is a plain icon.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SleepSettingButton(
    timer: SleepTimerState,
    onClick: () -> Unit,
    tonalWhenIdle: Boolean,
) {
    if (timer is SleepTimerState.Active) {
        val left = timer.formatRemaining()
        val armedLabel = stringResource(Res.string.player_mini_sleep_left, left)
        FilledTonalButton(
            onClick = onClick,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            modifier = Modifier.heightIn(min = MinTouchTarget).semantics { contentDescription = armedLabel },
        ) {
            Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(left)
        }
    } else if (tonalWhenIdle) {
        FilledTonalIconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(MinTouchTarget),
        ) {
            Icon(Icons.Default.Bedtime, contentDescription = stringResource(Res.string.player_sleep_timer))
        }
    } else {
        Ctrl(
            icon = Icons.Default.Bedtime,
            contentDescription = stringResource(Res.string.player_sleep_timer),
            onClick = onClick,
            size = MinTouchTarget,
            iconSize = MiniPlayerGlyphSize,
            tint = LocalContentColor.current,
        )
    }
}
