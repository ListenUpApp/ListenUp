package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.presentation.nowplaying.PLAYBACK_SPEED_MAX
import com.calypsan.listenup.client.presentation.nowplaying.PLAYBACK_SPEED_MIN
import com.calypsan.listenup.client.presentation.nowplaying.PLAYBACK_SPEED_STEPS
import com.calypsan.listenup.client.presentation.nowplaying.snapPlaybackSpeed
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import kotlin.math.absoluteValue
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_playback_speed
import listenup.composeapp.generated.resources.player_reset_to_default
import listenup.composeapp.generated.resources.player_speed_value
import org.jetbrains.compose.resources.stringResource

/**
 * A speed as this UI writes it: `1.0x`, `1.25x`, `2.0x`.
 *
 * Deliberately *not* shared with the browser's own `formatSpeed`, which renders the same rate as
 * `1` and `1.25` with the `×` supplied separately. That is not drift — web's transport control is
 * forty pixels wide, and `1.00x` spends a third of it saying nothing. The two differ because their
 * space budgets differ; the ladder, bounds and increment they draw from do not, and those now live
 * in one place ([PLAYBACK_SPEED_STEPS] and friends).
 *
 * The whole-number case is spelled out rather than trimmed: a lone `1x` beside `1.25x` reads as a
 * different kind of value, and the column of pills is easier to scan when every label has a
 * decimal point in it.
 */
fun formatPlaybackSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) {
        "${speed.toInt()}.0x"
    } else {
        val formatted = "%.2f".format(speed).trimEnd('0').trimEnd('.')
        "${formatted}x"
    }

/**
 * Playback-speed panel: a large brand readout, a fine-control slider (0.5x-3.0x, 0.05 steps), a row
 * of preset chips, and a reset-to-default action shown only when the current speed differs from the
 * universal default. Adaptive sheet/dialog via [PlayerPanelScaffold].
 *
 * @param currentSpeed Current playback speed.
 * @param defaultSpeed Universal default speed from settings.
 * @param onSpeedChange Called when the user picks a new speed (marks it custom).
 * @param onResetToDefault Called when the user taps reset (marks it as using the default).
 * @param onDismiss Called when the panel is dismissed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackSpeedSheet(
    currentSpeed: Float,
    defaultSpeed: Float = PlaybackPreferences.DEFAULT_PLAYBACK_SPEED,
    onSpeedChange: (Float) -> Unit,
    onResetToDefault: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var sliderSpeed by remember(currentSpeed) { mutableFloatStateOf(currentSpeed) }

    PlayerPanelScaffold(
        title = stringResource(Res.string.player_playback_speed),
        onDismiss = onDismiss,
        dialogWidth = 520.dp,
    ) {
        SpeedReadout(sliderSpeed)
        Spacer(Modifier.height(24.dp))
        SpeedSlider(
            speed = sliderSpeed,
            onSpeedChange = { newSpeed ->
                val snapped = snapPlaybackSpeed(newSpeed)
                if ((snapped - sliderSpeed).absoluteValue >= 0.01f) sliderSpeed = snapped
            },
            onSpeedChangeFinished = { onSpeedChange(sliderSpeed) },
        )
        Spacer(Modifier.height(22.dp))
        SpeedPresetRow(
            currentSpeed = sliderSpeed,
            onSpeedSelected = { preset ->
                sliderSpeed = preset
                onSpeedChange(preset)
            },
        )
        if ((sliderSpeed - defaultSpeed).absoluteValue > 0.01f) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(
                    onClick = {
                        sliderSpeed = defaultSpeed
                        onResetToDefault()
                    },
                ) {
                    Text(stringResource(Res.string.player_reset_to_default, formatPlaybackSpeed(defaultSpeed)))
                }
            }
        }
    }
}

@Composable
private fun SpeedReadout(speed: Float) {
    val formatted = formatPlaybackSpeed(speed)
    val text =
        buildAnnotatedString {
            val xIndex = formatted.lastIndexOf('x')
            append(formatted.substring(0, xIndex))
            withStyle(SpanStyle(fontSize = 40.sp)) { append("x") }
        }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style =
                MaterialTheme.typography.displayLarge.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SpeedSlider(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onSpeedChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = speed,
            onValueChange = onSpeedChange,
            onValueChangeFinished = onSpeedChangeFinished,
            valueRange = PLAYBACK_SPEED_MIN..PLAYBACK_SPEED_MAX,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.player_speed_value, PLAYBACK_SPEED_MIN.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.player_speed_value, PLAYBACK_SPEED_MAX.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedPresetRow(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PLAYBACK_SPEED_STEPS.forEach { preset ->
            PillChip(
                label = formatPlaybackSpeed(preset),
                selected = (currentSpeed - preset).absoluteValue < 0.01f,
                onClick = { onSpeedSelected(preset) },
            )
        }
    }
}
