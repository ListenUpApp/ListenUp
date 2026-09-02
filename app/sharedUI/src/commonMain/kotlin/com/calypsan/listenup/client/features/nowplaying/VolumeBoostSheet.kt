package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import com.calypsan.listenup.client.presentation.nowplaying.isSameVolumeBoost
import com.calypsan.listenup.domain.VolumeBoostLimits
import kotlin.math.roundToInt
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_boost_db
import listenup.composeapp.generated.resources.player_boost_off
import listenup.composeapp.generated.resources.player_boost_use_default
import listenup.composeapp.generated.resources.player_volume_boost
import org.jetbrains.compose.resources.stringResource

/** Volume-boost presets and formatting utilities. */
object VolumeBoostPresets {
    /**
     * Discrete boost presets spanning the full [VolumeBoostLimits] range, 3 dB apart.
     *
     * Read from the contract rather than generated here: the same ladder is rendered by web's
     * `BoostPicker` and iOS's `BoostPickerSheet`, and three independent derivations of it is three
     * places for the browser to start offering a rung the phone does not.
     */
    val presets: List<Float> get() = VolumeBoostLimits.PRESETS_DB

    /**
     * Format a boost value from pre-resolved localized strings: [offLabel] when [db] is at the
     * floor (no boost), [dbLabel] otherwise — already substituted with the rounded dB value by the
     * caller, e.g. via `stringResource(Res.string.player_boost_db, db.roundToInt())`.
     */
    fun format(
        db: Float,
        offLabel: String,
        dbLabel: String,
    ): String = if (db <= VolumeBoostLimits.MIN_DB) offLabel else dbLabel
}

/**
 * Volume-boost panel: a large brand readout and a row of discrete dB preset chips (3 dB steps
 * across the full [VolumeBoostLimits] range), plus a reset-to-default action shown only when the
 * current boost differs from the universal default. Unlike [PlaybackSpeedSheet] there is no
 * fine-control slider — boost is a small, discrete catalogue by design (iOS parity). Adaptive
 * sheet/dialog via [PlayerPanelScaffold].
 *
 * @param currentBoostDb Current per-book boost, in dB.
 * @param defaultBoostDb Universal default boost from settings, in dB.
 * @param onBoostChange Called when the user picks a new boost (marks it custom).
 * @param onResetToDefault Called when the user taps reset (marks it as using the default).
 * @param onDismiss Called when the panel is dismissed.
 */
@Composable
fun VolumeBoostSheet(
    currentBoostDb: Float,
    defaultBoostDb: Float,
    onBoostChange: (Float) -> Unit,
    onResetToDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedBoostDb by remember(currentBoostDb) { mutableFloatStateOf(currentBoostDb) }
    val offLabel = stringResource(Res.string.player_boost_off)

    PlayerPanelScaffold(
        title = stringResource(Res.string.player_volume_boost),
        onDismiss = onDismiss,
        dialogWidth = 520.dp,
    ) {
        BoostReadout(selectedBoostDb, offLabel)
        Spacer(Modifier.height(24.dp))
        BoostPresetRow(
            currentBoostDb = selectedBoostDb,
            offLabel = offLabel,
            onBoostSelected = { preset ->
                selectedBoostDb = preset
                onBoostChange(preset)
            },
        )
        if (!isSameVolumeBoost(selectedBoostDb, defaultBoostDb)) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val defaultBoostLabel =
                    VolumeBoostPresets.format(
                        db = defaultBoostDb,
                        offLabel = offLabel,
                        dbLabel = stringResource(Res.string.player_boost_db, defaultBoostDb.roundToInt()),
                    )
                TextButton(
                    onClick = {
                        selectedBoostDb = defaultBoostDb
                        onResetToDefault()
                    },
                ) {
                    Text(stringResource(Res.string.player_boost_use_default, defaultBoostLabel))
                }
            }
        }
    }
}

@Composable
private fun BoostReadout(
    boostDb: Float,
    offLabel: String,
) {
    val label =
        VolumeBoostPresets.format(
            db = boostDb,
            offLabel = offLabel,
            dbLabel = stringResource(Res.string.player_boost_db, boostDb.roundToInt()),
        )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = label,
            style =
                MaterialTheme.typography.displayLarge.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoostPresetRow(
    currentBoostDb: Float,
    offLabel: String,
    onBoostSelected: (Float) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VolumeBoostPresets.presets.forEach { preset ->
            val label =
                VolumeBoostPresets.format(
                    db = preset,
                    offLabel = offLabel,
                    dbLabel = stringResource(Res.string.player_boost_db, preset.roundToInt()),
                )
            PillChip(
                label = label,
                selected = isSameVolumeBoost(currentBoostDb, preset),
                onClick = { onBoostSelected(preset) },
            )
        }
    }
}
