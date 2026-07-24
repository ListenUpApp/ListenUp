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
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_playback_speed
import listenup.composeapp.generated.resources.player_reset_to_default
import listenup.composeapp.generated.resources.player_speed_value
import org.jetbrains.compose.resources.stringResource

/** Playback speed presets and formatting utilities. */
object PlaybackSpeedPresets {
    val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.0f
    const val STEP = 0.05f
    const val DEFAULT_SPEED = 1.0f

    /** Format speed for display (e.g., "1.25x", "2.0x"). */
    fun format(speed: Float): String =
        if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}.0x"
        } else {
            val formatted = "%.2f".format(speed).trimEnd('0').trimEnd('.')
            "${formatted}x"
        }

    /** Snap a speed value to the nearest 0.05 increment. */
    fun snap(speed: Float): Float = (speed / STEP).roundToInt() * STEP
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
    defaultSpeed: Float = PlaybackSpeedPresets.DEFAULT_SPEED,
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
                val snapped = PlaybackSpeedPresets.snap(newSpeed)
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
                    Text(stringResource(Res.string.player_reset_to_default, PlaybackSpeedPresets.format(defaultSpeed)))
                }
            }
        }
    }
}

@Composable
private fun SpeedReadout(speed: Float) {
    val formatted = PlaybackSpeedPresets.format(speed)
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
            valueRange = PlaybackSpeedPresets.MIN_SPEED..PlaybackSpeedPresets.MAX_SPEED,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.player_speed_value, PlaybackSpeedPresets.MIN_SPEED.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.player_speed_value, PlaybackSpeedPresets.MAX_SPEED.toString()),
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
        PlaybackSpeedPresets.presets.forEach { preset ->
            PillChip(
                label = PlaybackSpeedPresets.format(preset),
                selected = (currentSpeed - preset).absoluteValue < 0.01f,
                onClick = { onSpeedSelected(preset) },
            )
        }
    }
}
