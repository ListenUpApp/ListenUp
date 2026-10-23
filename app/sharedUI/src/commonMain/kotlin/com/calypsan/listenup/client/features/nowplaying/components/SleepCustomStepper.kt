package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.player.SleepTimerChoices
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_sheet_custom
import listenup.composeapp.generated.resources.player_sheet_custom_less
import listenup.composeapp.generated.resources.player_sheet_custom_minutes
import listenup.composeapp.generated.resources.player_sheet_custom_more
import listenup.composeapp.generated.resources.player_sheet_start
import org.jetbrains.compose.resources.stringResource

/**
 * The sleep sheet's custom time (PT8, `Sheets-Android`): "Custom", then − N min + in [SleepTimerChoices]' steps, and
 * Start. The minutes are a polite live region, so each press is spoken; − and + stop at the bounds. At a large font
 * Start wraps under the stepper rather than running off the sheet.
 *
 * @param minutes The minutes the stepper shows.
 * @param onStep − ([up] false) or + ([up] true) was pressed.
 * @param onStart Start was pressed: set a timer of [minutes].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun SleepCustomStepper(
    minutes: Int,
    onStep: (up: Boolean) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(Res.string.player_sheet_custom), style = MaterialTheme.typography.labelLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StepButton(
                        up = false,
                        enabled = minutes > SleepTimerChoices.CUSTOM_STEP_MINUTES,
                        onStep = onStep,
                    )
                    Text(
                        text = stringResource(Res.string.player_sheet_custom_minutes, minutes),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier.weight(1f).widthIn(min = MinTouchTarget).semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                    StepButton(
                        up = true,
                        enabled = minutes < SleepTimerChoices.CUSTOM_MAX_MINUTES,
                        onStep = onStep,
                    )
                }
                Button(
                    onClick = onStart,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) { Text(stringResource(Res.string.player_sheet_start)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepButton(
    up: Boolean,
    enabled: Boolean,
    onStep: (up: Boolean) -> Unit,
) {
    FilledTonalIconButton(
        onClick = { onStep(up) },
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier.size(MinTouchTarget),
    ) {
        Icon(
            imageVector = if (up) Icons.Default.Add else Icons.Default.Remove,
            contentDescription =
                stringResource(if (up) Res.string.player_sheet_custom_more else Res.string.player_sheet_custom_less),
        )
    }
}
