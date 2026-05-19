package com.calypsan.listenup.client.features.nowplaying

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Playback speed presets and formatting utilities.
 */
object PlaybackSpeedPresets {
    val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.0f
    const val STEP = 0.05f
    const val DEFAULT_SPEED = 1.0f

    /**
     * Format speed for display (e.g., "1.25x", "2.0x").
     */
    fun format(speed: Float): String =
        if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}.0x"
        } else {
            val formatted = "%.2f".format(speed).trimEnd('0').trimEnd('.')
            "${formatted}x"
        }

    /**
     * Snap a speed value to the nearest 0.05 increment.
     */
    fun snap(speed: Float): Float = (speed / STEP).roundToInt() * STEP
}

/**
 * Bottom sheet for selecting playback speed.
 *
 * Features:
 * - Large current speed display
 * - Slider for fine control (0.5x - 3.0x, 0.05 increments)
 * - Preset chips for quick selection
 * - Reset to universal default button (when using custom speed)
 *
 * Haptic feedback on interactions for tactile response.
 *
 * @param currentSpeed Current playback speed
 * @param defaultSpeed Universal default speed from settings
 * @param onSpeedChange Called when user selects a new speed (marks as custom)
 * @param onResetToDefault Called when user taps "Reset to default" (marks as using default)
 * @param onDismiss Called when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaybackSpeedSheet(
    currentSpeed: Float,
    defaultSpeed: Float = PlaybackSpeedPresets.DEFAULT_SPEED,
    onSpeedChange: (Float) -> Unit,
    onResetToDefault: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val view = LocalView.current

    // Local state for slider to allow smooth dragging
    var sliderSpeed by remember(currentSpeed) { mutableFloatStateOf(currentSpeed) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .animateContentSize(
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Text(
                text = "Playback Speed",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            // Current speed - large display
            Text(
                text = PlaybackSpeedPresets.format(sliderSpeed),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(24.dp))

            // Slider for fine control
            SpeedSlider(
                speed = sliderSpeed,
                onSpeedChange = { newSpeed ->
                    val snapped = PlaybackSpeedPresets.snap(newSpeed)
                    if ((snapped - sliderSpeed).absoluteValue >= 0.01f) {
                        sliderSpeed = snapped
                        view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                    }
                },
                onSpeedChangeFinished = {
                    onSpeedChange(sliderSpeed)
                },
            )

            Spacer(Modifier.height(24.dp))

            // Preset chips
            SpeedPresetChips(
                currentSpeed = sliderSpeed,
                onSpeedSelected = { preset ->
                    sliderSpeed = preset
                    onSpeedChange(preset)
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                },
            )

            Spacer(Modifier.height(16.dp))

            // Reset to universal default button - shows when not already using default
            val showReset = (sliderSpeed - defaultSpeed).absoluteValue > 0.01f
            if (showReset) {
                TextButton(
                    onClick = {
                        sliderSpeed = defaultSpeed
                        onResetToDefault()
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    },
                ) {
                    Text("Reset to default (${PlaybackSpeedPresets.format(defaultSpeed)})")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Slider for fine-grained speed control.
 */
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

        // Min/max labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${PlaybackSpeedPresets.MIN_SPEED}x",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${PlaybackSpeedPresets.MAX_SPEED}x",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Flow layout of preset speed chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedPresetChips(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlaybackSpeedPresets.presets.forEach { preset ->
            val isSelected = (currentSpeed - preset).absoluteValue < 0.01f

            FilterChip(
                selected = isSelected,
                onClick = { onSpeedSelected(preset) },
                label = { Text(PlaybackSpeedPresets.format(preset)) },
            )
        }
    }
}
