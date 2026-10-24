package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.design.components.ButtonGroupChoice
import com.calypsan.listenup.client.design.components.ConnectedSelectButtonGroup
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import com.calypsan.listenup.client.presentation.player.isSameVolumeBoost
import com.calypsan.listenup.domain.VolumeBoostLimits
import kotlin.math.roundToInt
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_boost_db
import listenup.composeapp.generated.resources.player_boost_off
import listenup.composeapp.generated.resources.player_boost_pill_db
import listenup.composeapp.generated.resources.player_sheet_boost_level
import listenup.composeapp.generated.resources.player_sheet_boost_note
import listenup.composeapp.generated.resources.player_sheet_reset_boost
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
 * The Boost sheet (spec §5.5.3, PB1–PB5; `Sheets-Android`): the level as the sheet's display figure ("+6 dB"), what
 * boost is for, the five levels as one connected single-select group (m3.material.io/components/button-groups/guidelines:
 * connected groups for selection), and reset to the listener's own default. A discrete catalogue by design — no slider
 * (iOS parity). It stays open while the listener compares levels (§3.8). Adaptive sheet/dialog via [PlayerPanelScaffold].
 *
 * @param currentBoostDb This book's boost, in dB.
 * @param defaultBoostDb The listener's default boost, in dB, which reset returns to.
 * @param onBoostChange A level was chosen.
 * @param onResetToDefault Reset was tapped.
 * @param onDismiss The sheet was dismissed.
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

    PlayerPanelScaffold(title = stringResource(Res.string.player_volume_boost), onDismiss = onDismiss) {
        Text(
            text = boostLabel(selectedBoostDb),
            style =
                MaterialTheme.typography.displayMedium.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.player_sheet_boost_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.md),
        )
        ConnectedSelectButtonGroup(
            choices =
                VolumeBoostPresets.presets.map { preset ->
                    ButtonGroupChoice<Float?>(preset, pillLabel(preset), boostLabel(preset))
                },
            selected = VolumeBoostPresets.presets.firstOrNull { isSameVolumeBoost(it, selectedBoostDb) },
            onSelect = { preset ->
                if (preset != null) {
                    selectedBoostDb = preset
                    onBoostChange(preset)
                }
            },
            groupLabel = stringResource(Res.string.player_sheet_boost_level),
        )
        if (!isSameVolumeBoost(selectedBoostDb, defaultBoostDb)) {
            TextButton(
                onClick = {
                    selectedBoostDb = defaultBoostDb
                    onResetToDefault()
                },
                modifier = Modifier.padding(top = Spacing.sm).heightIn(min = MinTouchTarget),
            ) {
                Text(stringResource(Res.string.player_sheet_reset_boost, boostLabel(defaultBoostDb)))
            }
        }
    }
}

/** "Off" or "+6 dB": the readout, the reset, and what a screen reader hears for a level. */
@Composable
private fun boostLabel(db: Float): String =
    VolumeBoostPresets.format(
        db = db,
        offLabel = stringResource(Res.string.player_boost_off),
        dbLabel = stringResource(Res.string.player_boost_db, db.roundToInt()),
    )

/** "Off" or "+6": a level's short label in the group, where "dB" would crowd five buttons. */
@Composable
private fun pillLabel(db: Float): String =
    VolumeBoostPresets.format(
        db = db,
        offLabel = stringResource(Res.string.player_boost_off),
        dbLabel = stringResource(Res.string.player_boost_pill_db, db.roundToInt()),
    )
