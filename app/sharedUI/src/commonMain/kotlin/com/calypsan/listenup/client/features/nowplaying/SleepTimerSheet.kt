package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.design.components.listenUpOutlinedBorder
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import com.calypsan.listenup.client.features.nowplaying.components.SleepCustomStepper
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerChapter
import com.calypsan.listenup.client.presentation.player.SleepTimerChoices
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_add_more_time
import listenup.composeapp.generated.resources.player_cancel_timer
import listenup.composeapp.generated.resources.player_chapter_number
import listenup.composeapp.generated.resources.player_end_of_chapter
import listenup.composeapp.generated.resources.player_extend_minutes
import listenup.composeapp.generated.resources.player_fading_out
import listenup.composeapp.generated.resources.player_sheet_durations
import listenup.composeapp.generated.resources.player_sheet_end_of_chapter_after
import listenup.composeapp.generated.resources.player_sheet_fades_out
import listenup.composeapp.generated.resources.player_sleep_timer
import listenup.composeapp.generated.resources.player_until_end_of_chapter
import listenup.composeapp.generated.resources.player_until_end_of_chapter_detail
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription

/**
 * What the sleep sheet draws (spec §5.5.2), built by the host from the loaded state. Everything that moves with the
 * position is a lambda, read in the leaf that shows it, so the 4 Hz tick never recomposes the sheet.
 *
 * @property timer The sleep timer: which view the sheet shows.
 * @property chapter The current chapter, for "Pauses after Bridge Four" and whether End of chapter is offered.
 * @property chapterRemainingMs Content time left in the chapter, for "in 8m" (S10).
 * @property countdownMs Wall-clock time until the timer pauses (`SleepCountdowns.remainingMs`); null with no timer.
 * @property meterFraction The share of the timer still to run (`SleepCountdowns.meterFraction`).
 * @property nowMs The wall clock, injected so "Pauses at" is testable.
 */
@Immutable
internal data class SleepSheetState(
    val timer: SleepTimerState,
    val chapter: PlayerChapter,
    val chapterRemainingMs: () -> Long,
    val countdownMs: () -> Long?,
    val meterFraction: () -> Float,
    val nowMs: () -> Long,
)

/**
 * The Sleep sheet (spec §5.5.2, PT1–PT8; `Sheets-Android`). With no timer: End of chapter first and largest, in the
 * timer's gold, with the chapter and how long it has left (hidden for a chapterless book); then the durations; then a
 * custom time. Starting a timer closes the sheet (§3.8). Adaptive sheet/dialog via [PlayerPanelScaffold].
 *
 * @param state What to draw.
 * @param onSetTimer Start a timer with the chosen [SleepTimerMode].
 * @param onCancelTimer Cancel the running timer.
 * @param onExtendTimer Add the given number of minutes to a running duration timer.
 * @param onKeepListening Cancel the timer during its fade; full volume returns (PT7).
 * @param onDismiss Dismiss the panel.
 */
@Composable
internal fun SleepTimerSheet(
    state: SleepSheetState,
    onSetTimer: (SleepTimerMode) -> Unit,
    onCancelTimer: () -> Unit,
    onExtendTimer: (Int) -> Unit,
    onKeepListening: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerPanelScaffold(title = stringResource(Res.string.player_sleep_timer), onDismiss = onDismiss) {
        when (val timer = state.timer) {
            SleepTimerState.Inactive -> {
                SleepChoices(state = state, onSetTimer = onSetTimer)
            }

            is SleepTimerState.Active -> {
                ActiveTimerDisplay(
                    state = timer,
                    onExtend = onExtendTimer,
                    onCancel = {
                        onCancelTimer()
                        onDismiss()
                    },
                )
            }

            SleepTimerState.FadingOut -> {
                FadingOutDisplay()
            }
        }
    }
}

/** No timer yet: End of chapter, the durations, a custom time, and what happens at the end. */
@Composable
private fun SleepChoices(
    state: SleepSheetState,
    onSetTimer: (SleepTimerMode) -> Unit,
) {
    var customMinutes by rememberSaveable { mutableIntStateOf(SleepTimerChoices.CUSTOM_DEFAULT_MINUTES) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (SleepTimerChoices.offersEndOfChapter(state.chapter)) {
            EndOfChapterChoice(
                chapterName = state.chapter.sleepName(),
                chapterRemainingMs = state.chapterRemainingMs,
                onClick = { onSetTimer(SleepTimerMode.EndOfChapter) },
            )
        }
        DurationGrid(onPick = { minutes -> onSetTimer(SleepTimerMode.Duration(minutes)) })
        SleepCustomStepper(
            minutes = customMinutes,
            onStep = { up -> customMinutes = SleepTimerChoices.stepCustom(customMinutes, up) },
            onStart = { onSetTimer(SleepTimerMode.Duration(customMinutes)) },
        )
        Text(
            text = stringResource(Res.string.player_sheet_fades_out),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The sheet's one emphasised choice, in the gold of the running timer (§3.9): the moon, "End of chapter" and "Pauses
 * after Bridge Four · in 8m" — content time, like every chapter remainder (S10).
 */
@Composable
private fun EndOfChapterChoice(
    chapterName: String,
    chapterRemainingMs: () -> Long,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().heightIn(min = END_OF_CHAPTER_MIN_HEIGHT).semantics { role = Role.Button },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(24.dp))
            Column {
                Text(stringResource(Res.string.player_end_of_chapter), style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        stringResource(
                            Res.string.player_sheet_end_of_chapter_after,
                            chapterName,
                            shortDuration(chapterRemainingMs()),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * The durations, three to a row (`Sheets-Android`). They are actions, not a selection — a tap starts the timer — so
 * they carry no selection semantics (m3.material.io/components/button-groups/guidelines).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DurationGrid(onPick: (minutes: Int) -> Unit) {
    val groupLabel = stringResource(Res.string.player_sheet_durations)
    Column(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = groupLabel },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SleepTimerChoices.DURATION_MINUTES.chunked(DURATIONS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { minutes ->
                    FilledTonalButton(
                        onClick = { onPick(minutes) },
                        shapes = ButtonDefaults.shapes(),
                        colors =
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        modifier = Modifier.weight(1f).heightIn(min = DURATION_HEIGHT),
                    ) { Text(SleepTimerMode.Duration(minutes).label) }
                }
                repeat(DURATIONS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The chapter as the sleep sheet names it: its own title, or "Chapter 6" for an untitled one. */
@Composable
private fun PlayerChapter.sleepName(): String =
    title.ifBlank { stringResource(Res.string.player_chapter_number, number) }

/** The End of chapter choice's least height: the board's largest row. */
private val END_OF_CHAPTER_MIN_HEIGHT = 64.dp

/** The durations' height on the board. */
private val DURATION_HEIGHT = 56.dp

/** The board's three-column grid of durations. */
private const val DURATIONS_PER_ROW = 3

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
        OutlinedButton(onClick = onCancel, border = listenUpOutlinedBorder(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.player_cancel_timer))
        }
    }
}

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
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
    Spacer(Modifier.height(Spacing.xl))
    Text(
        text = stringResource(Res.string.player_add_more_time),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SleepTimerChoices.EXTEND_MINUTES.forEach { minutes ->
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
