package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.features.nowplaying.formatPlaybackSpeed
import com.calypsan.listenup.client.playback.SleepTimerState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_chapters
import listenup.composeapp.generated.resources.player_playback_speed
import org.jetbrains.compose.resources.stringResource

/** At this font scale the row wraps instead of overflowing (§5.2). */
private const val LARGE_FONT_SCALE = 1.5f

/**
 * This book's settings (spec §3.1.5, §5.2): Speed (with the rate) · Sleep · Chapters · Cast, one standard button group
 * of tonal buttons (m3.material.io/components/button-groups: standard groups for rows of actions). Each opens a sheet;
 * Cast opens the system route dialog and hides itself with no devices; while casting it names the device ([castingTo],
 * §5.5.6). At large font scale it wraps (§5.2).
 * [showsChapters] is false where the chapter list is always visible.
 */
@Suppress("LongParameterList") // one callback per control the board draws
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerSettingsRow(
    speed: Float,
    sleepTimer: SleepTimerState,
    castingTo: String?,
    showsChapters: Boolean,
    onSpeedClick: () -> Unit,
    onSleepClick: () -> Unit,
    onChaptersClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rate = formatPlaybackSpeed(speed)
    val speedName = stringResource(Res.string.player_playback_speed)
    val chaptersName = stringResource(Res.string.player_chapters)
    val speedButton: @Composable () -> Unit = {
        FilledTonalButton(
            onClick = onSpeedClick,
            shapes = ButtonDefaults.shapes(),
            modifier =
                Modifier.heightIn(min = MinTouchTarget).semantics {
                    contentDescription = speedName
                    stateDescription = rate
                },
        ) { Text(rate) }
    }
    val sleepButton: @Composable () -> Unit = {
        SleepSettingButton(timer = sleepTimer, onClick = onSleepClick, tonalWhenIdle = true)
    }
    val chaptersButton: @Composable () -> Unit = {
        FilledTonalIconButton(
            onClick = onChaptersClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(MinTouchTarget),
        ) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = chaptersName)
        }
    }
    val castButton: @Composable () -> Unit = {
        CastButton(connectedDeviceName = castingTo, modifier = Modifier.size(MinTouchTarget))
    }
    val controls =
        listOfNotNull(
            speedButton,
            sleepButton,
            chaptersButton.takeIf { showsChapters },
            castButton,
        )

    if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { controls.forEach { it() } }
    } else {
        ButtonGroup(
            overflowIndicator = {},
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.fillMaxWidth(),
        ) {
            controls.forEach { control -> customItem(buttonGroupContent = { control() }, menuContent = {}) }
        }
    }
}
