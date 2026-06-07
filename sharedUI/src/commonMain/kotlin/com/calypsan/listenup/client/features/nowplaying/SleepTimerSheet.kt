package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_add_more_time
import listenup.composeapp.generated.resources.player_cancel_timer
import listenup.composeapp.generated.resources.player_end_of_chapter
import listenup.composeapp.generated.resources.player_end_of_chapter_subtitle
import listenup.composeapp.generated.resources.player_extend_minutes
import listenup.composeapp.generated.resources.player_fading_out
import listenup.composeapp.generated.resources.player_sleep_timer
import listenup.composeapp.generated.resources.player_until_end_of_chapter
import listenup.composeapp.generated.resources.player_until_end_of_chapter_detail
import org.jetbrains.compose.resources.stringResource

private val DURATION_OPTIONS = listOf(15, 30, 45, 60, 120)
private val EXTEND_OPTIONS = listOf(5, 10, 15)

/**
 * Sleep-timer panel. Inactive: outlined duration chips plus a prominent "End of chapter" card.
 * Active: countdown + progress + extend chips + cancel. FadingOut: a fading indicator. Adaptive
 * sheet/dialog via [PlayerPanelScaffold].
 *
 * @param currentState The current [SleepTimerState].
 * @param onSetTimer Start a timer with the chosen [SleepTimerMode].
 * @param onCancelTimer Cancel the running timer.
 * @param onExtendTimer Add the given number of minutes to a running duration timer.
 * @param onDismiss Dismiss the panel.
 */
@Composable
fun SleepTimerSheet(
    currentState: SleepTimerState,
    onSetTimer: (SleepTimerMode) -> Unit,
    onCancelTimer: () -> Unit,
    onExtendTimer: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerPanelScaffold(
        title = stringResource(Res.string.player_sleep_timer),
        onDismiss = onDismiss,
        dialogWidth = 520.dp,
    ) {
        when (currentState) {
            is SleepTimerState.Inactive -> {
                SleepTimerOptions(onSetTimer = onSetTimer)
            }

            is SleepTimerState.Active -> {
                ActiveTimerDisplay(
                    state = currentState,
                    onExtend = onExtendTimer,
                    onCancel = {
                        onCancelTimer()
                        onDismiss()
                    },
                )
            }

            is SleepTimerState.FadingOut -> {
                FadingOutDisplay()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerOptions(onSetTimer: (SleepTimerMode) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DURATION_OPTIONS.forEach { minutes ->
            PillChip(
                label = SleepTimerMode.Duration(minutes).label,
                onClick = { onSetTimer(SleepTimerMode.Duration(minutes)) },
            )
        }
    }
    Spacer(Modifier.height(18.dp))
    EndOfChapterCard(onClick = { onSetTimer(SleepTimerMode.EndOfChapter) })
}

@Composable
private fun EndOfChapterCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Bedtime,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.player_end_of_chapter),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(Res.string.player_end_of_chapter_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ActiveTimerDisplay(
    state: SleepTimerState.Active,
    onExtend: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state.mode) {
            is SleepTimerMode.Duration -> DurationCountdown(state = state, onExtend = onExtend)
            is SleepTimerMode.EndOfChapter -> EndOfChapterCountdown()
        }
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.player_cancel_timer))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationCountdown(
    state: SleepTimerState.Active,
    onExtend: (Int) -> Unit,
) {
    Text(
        text = state.formatRemaining(),
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(
        progress = { state.progress },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(Res.string.player_add_more_time),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EXTEND_OPTIONS.forEach { minutes ->
            PillChip(
                label = stringResource(Res.string.player_extend_minutes, minutes),
                onClick = { onExtend(minutes) },
            )
        }
    }
}

@Composable
private fun EndOfChapterCountdown() {
    Icon(
        imageVector = Icons.Default.Bedtime,
        contentDescription = null,
        modifier = Modifier.size(56.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(Res.string.player_until_end_of_chapter),
        style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(Res.string.player_until_end_of_chapter_detail),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FadingOutDisplay() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ListenUpLoadingIndicator()
            Spacer(Modifier.height(16.dp))
            Text(text = stringResource(Res.string.player_fading_out), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
