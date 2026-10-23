package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.design.components.ButtonGroupChoice
import com.calypsan.listenup.client.design.components.ConnectedSelectButtonGroup
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import com.calypsan.listenup.client.presentation.player.PLAYBACK_SPEED_MAX
import com.calypsan.listenup.client.presentation.player.PLAYBACK_SPEED_MIN
import com.calypsan.listenup.client.presentation.player.PLAYBACK_SPEED_STEPS
import com.calypsan.listenup.client.presentation.player.isSamePlaybackSpeed
import com.calypsan.listenup.client.presentation.player.snapPlaybackSpeed
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_playback_speed
import listenup.composeapp.generated.resources.player_sheet_faster
import listenup.composeapp.generated.resources.player_sheet_left_at_speed
import listenup.composeapp.generated.resources.player_sheet_reset_speed
import listenup.composeapp.generated.resources.player_sheet_slower
import listenup.composeapp.generated.resources.player_sheet_speed_presets
import listenup.composeapp.generated.resources.player_sheet_this_book_only
import listenup.composeapp.generated.resources.player_sheet_speed_times
import org.jetbrains.compose.resources.stringResource

/**
 * A speed as this UI writes it: `1.0×`, `1.25×`, `2.0×` — with the multiplication sign, matching iOS's
 * `PlaybackRates.format`, web's speed words and every board (C8).
 *
 * The whole-number case is spelled out rather than trimmed: a lone `1×` beside `1.25×` reads as a different kind of
 * value, and the column of presets is easier to scan when every label has a decimal point in it.
 */
fun formatPlaybackSpeed(speed: Float): String = "${speedNumber(speed)}×"

/**
 * The number alone ("1.25"), for "1.25 times". Plain arithmetic, not String.format: commonMain may not reach
 * java.util, and a locale-aware format put a comma in "1,25" beside "1.0" on a comma-decimal phone.
 */
private fun speedNumber(speed: Float): String {
    val hundredths = (speed * 100).roundToInt()
    val fraction =
        (hundredths % 100)
            .toString()
            .padStart(2, '0')
            .trimEnd('0')
            .ifEmpty { "0" }
    return "${hundredths / 100}.$fraction"
}

/**
 * The Speed sheet (spec §5.5.1, PS1–PS5; `Sheets-Android`): the rate as the sheet's one display figure, beside how long
 * the book has left at it (S10's single wall-time exception); − slider + in 0.05 steps (an M3 slider with tonal icon
 * buttons at its ends, m3.material.io/components/sliders/guidelines); the nine presets as one connected single-select
 * group (m3.material.io/components/button-groups/guidelines: connected groups for selection); reset to the listener's
 * own default; and that the speed belongs to this book. It stays open while the listener adjusts (§3.8).
 *
 * @param currentSpeed This book's speed.
 * @param defaultSpeed The listener's default speed, which reset returns to.
 * @param bookRemainingAtSpeedMs The book's remainder at [currentSpeed], read in a leaf so the position tick recomposes
 *   only that line.
 * @param onSpeedChange A preset was chosen, or the slider was released on a rate.
 * @param onStep − ([up] false) or + ([up] true): one 0.05 step through the shared, integer-stepped rule.
 * @param onResetToDefault Reset was tapped.
 * @param onDismiss The sheet was dismissed.
 */
@Composable
fun PlaybackSpeedSheet(
    currentSpeed: Float,
    defaultSpeed: Float,
    bookRemainingAtSpeedMs: () -> Long,
    onSpeedChange: (Float) -> Unit,
    onStep: (up: Boolean) -> Unit,
    onResetToDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    var sliderSpeed by remember(currentSpeed) { mutableFloatStateOf(currentSpeed) }

    PlayerPanelScaffold(title = stringResource(Res.string.player_playback_speed), onDismiss = onDismiss) {
        SpeedReadout(speed = sliderSpeed, bookRemainingAtSpeedMs = bookRemainingAtSpeedMs)
        Spacer(Modifier.height(Spacing.md))
        FineSpeedControl(
            speed = sliderSpeed,
            onSlide = { sliderSpeed = snapPlaybackSpeed(it) },
            onSlideFinished = { onSpeedChange(sliderSpeed) },
            onStep = onStep,
        )
        Spacer(Modifier.height(Spacing.md))
        ConnectedSelectButtonGroup(
            choices = PLAYBACK_SPEED_STEPS.map { ButtonGroupChoice<Float?>(it, formatPlaybackSpeed(it)) },
            selected = PLAYBACK_SPEED_STEPS.firstOrNull { isSamePlaybackSpeed(it, sliderSpeed) },
            onSelect = { preset ->
                if (preset != null) {
                    sliderSpeed = preset
                    onSpeedChange(preset)
                }
            },
            groupLabel = stringResource(Res.string.player_sheet_speed_presets),
            rowLength = PRESETS_PER_ROW,
        )
        if (!isSamePlaybackSpeed(sliderSpeed, defaultSpeed)) {
            TextButton(
                onClick = {
                    sliderSpeed = defaultSpeed
                    onResetToDefault()
                },
                modifier = Modifier.padding(top = Spacing.sm).heightIn(min = MinTouchTarget),
            ) {
                Text(stringResource(Res.string.player_sheet_reset_speed, formatPlaybackSpeed(defaultSpeed)))
            }
        }
        Text(
            text = stringResource(Res.string.player_sheet_this_book_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

/** "1.25×", the sheet's one display moment (F6), beside "32h 24m left at this speed". */
@Composable
private fun SpeedReadout(
    speed: Float,
    bookRemainingAtSpeedMs: () -> Long,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatPlaybackSpeed(speed),
            style =
                MaterialTheme.typography.displayMedium.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        LeftAtSpeed(bookRemainingAtSpeedMs)
    }
}

/** The position-dependent leaf: only this line recomposes on the 4 Hz tick. */
@Composable
private fun LeftAtSpeed(bookRemainingAtSpeedMs: () -> Long) {
    Text(
        text =
            stringResource(
                Res.string.player_sheet_left_at_speed,
                DurationFormatter.hoursMinutes(bookRemainingAtSpeedMs().coerceAtLeast(0).milliseconds),
            ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
    )
}

/** − slider +, with the bounds under the slider. The slider commits on release, as it always has. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FineSpeedControl(
    speed: Float,
    onSlide: (Float) -> Unit,
    onSlideFinished: () -> Unit,
    onStep: (up: Boolean) -> Unit,
) {
    val sliderName = stringResource(Res.string.player_playback_speed)
    val spokenSpeed = stringResource(Res.string.player_sheet_speed_times, speedNumber(speed))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { onStep(false) },
            enabled = speed > PLAYBACK_SPEED_MIN && !isSamePlaybackSpeed(speed, PLAYBACK_SPEED_MIN),
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(MinTouchTarget),
        ) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(Res.string.player_sheet_slower))
        }
        Slider(
            value = speed,
            onValueChange = onSlide,
            onValueChangeFinished = onSlideFinished,
            valueRange = PLAYBACK_SPEED_MIN..PLAYBACK_SPEED_MAX,
            steps = SLIDER_STEPS,
            modifier =
                Modifier.weight(1f).semantics {
                    contentDescription = sliderName
                    stateDescription = spokenSpeed
                },
        )
        FilledTonalIconButton(
            onClick = { onStep(true) },
            enabled = speed < PLAYBACK_SPEED_MAX && !isSamePlaybackSpeed(speed, PLAYBACK_SPEED_MAX),
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(MinTouchTarget),
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.player_sheet_faster))
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MinTouchTarget + Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX).forEach { bound ->
            Text(
                text = formatPlaybackSpeed(bound),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The slider's stops between 0.5× and 3.0×: fifty 0.05 intervals, so 49 stops between the ends. */
private const val SLIDER_STEPS = 49

/** The board's 5 + 4 grid of presets; at a large font they wrap into connected pairs. */
private const val PRESETS_PER_ROW = 5
