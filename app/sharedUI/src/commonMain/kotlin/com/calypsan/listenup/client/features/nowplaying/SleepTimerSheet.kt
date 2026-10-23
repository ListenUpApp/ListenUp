package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.listenUpOutlinedBorder
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import com.calypsan.listenup.client.features.nowplaying.components.SleepCustomStepper
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerChapter
import com.calypsan.listenup.client.presentation.player.SleepTimerChoices
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_cancel_timer
import listenup.composeapp.generated.resources.player_chapter_number
import listenup.composeapp.generated.resources.player_end_of_chapter
import listenup.composeapp.generated.resources.player_extend_minutes
import listenup.composeapp.generated.resources.player_sheet_durations
import listenup.composeapp.generated.resources.player_sheet_end_of_chapter_after
import listenup.composeapp.generated.resources.player_sheet_fades_out
import listenup.composeapp.generated.resources.player_sleep_timer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ButtonGroup
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.calypsan.listenup.client.design.components.formatPlaybackTime
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.features.nowplaying.components.SleepFadingCard
import com.calypsan.listenup.client.features.nowplaying.components.rememberClockTimeFormatter
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.player_sheet_add_time
import listenup.composeapp.generated.resources.player_sheet_meter_a11y
import listenup.composeapp.generated.resources.player_sheet_pauses_at_timer
import listenup.composeapp.generated.resources.player_sheet_until_end_of
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
 * custom time. Starting a timer closes the sheet (§3.8).
 *
 * Running: the countdown in big m:ss (not a live region, §8), when it pauses by the device's clock, a gold meter of what
 * is left, +5 / +10 / +15 for a duration, and Cancel timer, which keeps the sheet open and returns to the choices (S13).
 * Fading: the same gold card Now Playing shows, with Keep listening (PT7). Adaptive sheet/dialog via
 * [PlayerPanelScaffold].
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
                RunningTimer(
                    timer = timer,
                    state = state,
                    onExtend = onExtendTimer,
                    onCancel = onCancelTimer,
                )
            }

            SleepTimerState.FadingOut -> {
                SleepFadingCard(onKeepListening = onKeepListening)
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

/**
 * A running timer (PT3–PT6): the countdown, "Pauses at 11:42 PM · 30 min timer" (or, for End of chapter, "Until the end
 * of Bridge Four · 8m"), the meter, the extend group for a duration ([SleepTimerChoices.canExtend]), and Cancel timer.
 */
@Composable
private fun RunningTimer(
    timer: SleepTimerState.Active,
    state: SleepSheetState,
    onExtend: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Countdown(timer = timer, state = state)
        if (SleepTimerChoices.canExtend(timer)) ExtendGroup(onExtend = onExtend)
        OutlinedButton(
            onClick = onCancel,
            border = listenUpOutlinedBorder(),
            modifier = Modifier.fillMaxWidth().heightIn(min = RUNNING_BUTTON_HEIGHT),
        ) { Text(stringResource(Res.string.player_cancel_timer)) }
    }
}

/** The countdown, its line and its meter: the one leaf the tick recomposes. */
@Composable
private fun Countdown(
    timer: SleepTimerState.Active,
    state: SleepSheetState,
) {
    val countdownMs = state.countdownMs() ?: 0L
    val countdown = countdownMs.milliseconds.formatPlaybackTime()
    val fraction = state.meterFraction()
    val formatClock = rememberClockTimeFormatter()
    val mode = timer.mode
    val line =
        when (mode) {
            is SleepTimerMode.Duration -> {
                stringResource(
                    Res.string.player_sheet_pauses_at_timer,
                    formatClock(state.nowMs() + countdownMs),
                    mode.label,
                )
            }

            SleepTimerMode.EndOfChapter -> {
                stringResource(
                    Res.string.player_sheet_until_end_of,
                    state.chapter.sleepName(),
                    shortDuration(countdownMs),
                )
            }
        }
    val totalMs =
        when (mode) {
            is SleepTimerMode.Duration -> timer.totalMs
            SleepTimerMode.EndOfChapter -> if (fraction > 0f) (countdownMs / fraction).toLong() else countdownMs
        }
    val meterLabel =
        stringResource(Res.string.player_sheet_meter_a11y, countdown, totalMs.milliseconds.formatPlaybackTime())
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = countdown,
            style =
                MaterialTheme.typography.displayMedium.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
        )
        Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        LinearProgressIndicator(
            progress = { fraction },
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier =
                Modifier
                    .padding(top = Spacing.sm)
                    .fillMaxWidth()
                    .height(METER_HEIGHT)
                    .semantics { contentDescription = meterLabel },
        )
    }
}

/**
 * +5 / +10 / +15: a standard button group, because these are a row of actions, not a choice
 * (m3.material.io/components/button-groups/guidelines).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExtendGroup(onExtend: (Int) -> Unit) {
    val groupLabel = stringResource(Res.string.player_sheet_add_time)
    ButtonGroup(
        overflowIndicator = {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = groupLabel },
    ) {
        SleepTimerChoices.EXTEND_MINUTES.forEach { minutes ->
            customItem(
                buttonGroupContent = {
                    val interactions = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = { onExtend(minutes) },
                        shapes = ButtonDefaults.shapes(),
                        interactionSource = interactions,
                        modifier =
                            Modifier
                                .weight(1f)
                                .animateWidth(interactions)
                                .heightIn(min = RUNNING_BUTTON_HEIGHT),
                    ) { Text(stringResource(Res.string.player_extend_minutes, minutes)) }
                },
                menuContent = {},
            )
        }
    }
}

/** The running view's buttons on the board. */
private val RUNNING_BUTTON_HEIGHT = 56.dp

/** The gold meter's thickness on the board. */
private val METER_HEIGHT = 8.dp
